package com.hd.center.service;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.*;
import com.hd.center.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排班核心：自动排班、调班影响分析、执行调班。
 * 约束：机位数量、感染分区、透析时长、消毒间隔、交通接送窗、患者风险、护士熟悉度。
 */
@Service
@RequiredArgsConstructor
public class SchedulingService {

    private final SessionRepository sessionRepo;
    private final PatientRepository patientRepo;
    private final MachineRepository machineRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableService consumableService;

    private static final List<SessionStatus> INACTIVE = List.of(SessionStatus.CANCELLED, SessionStatus.ABSENT);

    /** 班次开始时间 */
    public static LocalDateTime shiftStart(LocalDate date, Shift shift) {
        return switch (shift) {
            case AM -> LocalDateTime.of(date, LocalTime.of(7, 30));
            case PM -> LocalDateTime.of(date, LocalTime.of(13, 30));
            case EVE -> LocalDateTime.of(date, LocalTime.of(18, 30));
        };
    }

    /** 感染标记 -> 可上机分区 */
    public static List<MachineZone> compatibleZones(InfectionMarker marker) {
        return switch (marker) {
            case NONE -> List.of(MachineZone.NORMAL);
            case HBV -> List.of(MachineZone.HBV);
            case HCV -> List.of(MachineZone.HCV);
            case TP, OTHER -> List.of(MachineZone.ISOLATION);
        };
    }

    /**
     * 自动排班：为某日某班次生成治疗单并预领耗材。
     *
     * @param ignorePattern true 时忽略固定透析日（用于演示/补班）
     */
    @Transactional
    public Map<String, Object> generate(LocalDate date, Shift shift, boolean ignorePattern, String operator) {
        int dow = date.getDayOfWeek().getValue();
        List<Patient> candidates = patientRepo.findByActiveTrueOrderByMrn().stream()
                .filter(p -> p.getUsualShift() == shift)
                .filter(p -> ignorePattern || patternContains(p.getUsualPattern(), dow))
                .filter(p -> !sessionRepo.existsByPatientIdAndSessionDateAndShiftAndStatusNotIn(
                        p.getId(), date, shift, INACTIVE))
                .sorted(patientComparator())
                .toList();

        List<DialysisMachine> machines = machineRepo.findByActiveTrueOrderByCode().stream()
                .filter(m -> m.getStatus() != MachineStatus.FAULT && m.getStatus() != MachineStatus.MAINTENANCE)
                .toList();

        List<DialysisSession> created = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();

        for (Patient patient : candidates) {
            DialysisMachine best = null;
            LocalDateTime bestSlot = null;
            for (DialysisMachine machine : machines) {
                if (!compatibleZones(patient.getInfectionMarker()).contains(machine.getZone())) continue;
                LocalDateTime slot = nextSlot(machine, date, shift, created);
                if (bestSlot == null || slot.isBefore(bestSlot)
                        || (slot.equals(bestSlot) && machine.getCode().compareTo(best.getCode()) < 0)) {
                    best = machine;
                    bestSlot = slot;
                }
            }
            if (best == null) {
                unassigned.add(patient.getName() + "（" + patient.getInfectionMarker() + " 分区机位不足）");
                continue;
            }
            DialysisSession session = new DialysisSession();
            session.setPatient(patient);
            session.setMachine(best);
            session.setSessionDate(date);
            session.setShift(shift);
            session.setPlannedStart(bestSlot);
            session.setPlannedDurationMin(patient.getDurationMin() == null ? 240 : patient.getDurationMin());
            session.setSeqNo(nextSeqNo(best.getId(), date, shift, created));
            session.setNurseName(patient.getFamiliarNurse());
            session.setStatus(SessionStatus.SCHEDULED);
            session = sessionRepo.save(session);
            consumableService.preDrawForSession(session, operator);
            created.add(session);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("shift", shift.name());
        result.put("created", created.size());
        result.put("unassigned", unassigned);
        result.put("sessions", created);
        return result;
    }

    /** 机器当日下一个可用上机时刻：班次开始 与 上一台治疗结束+消毒间隔 取较晚者 */
    public LocalDateTime nextSlot(DialysisMachine machine, LocalDate date, Shift shift, List<DialysisSession> pending) {
        LocalDateTime slot = shiftStart(date, shift);
        List<DialysisSession> daySessions = new ArrayList<>(
                sessionRepo.findBySessionDateAndMachineIdAndStatusNotIn(date, machine.getId(), INACTIVE));
        daySessions.addAll(pending.stream().filter(s -> s.getMachine().getId().equals(machine.getId())).toList());
        for (DialysisSession s : daySessions) {
            if (s.getPlannedStart() == null) continue;
            LocalDateTime end = s.getPlannedStart().plusMinutes(s.getPlannedDurationMin() + machine.getDisinfectBufferMin());
            if (end.isAfter(slot)) {
                slot = end;
            }
        }
        return slot;
    }

    public int nextSeqNo(Long machineId, LocalDate date, Shift shift, List<DialysisSession> pending) {
        int existing = sessionRepo.findBySessionDateAndShiftAndMachineId(date, shift, machineId).stream()
                .filter(s -> !INACTIVE.contains(s.getStatus()))
                .mapToInt(s -> s.getSeqNo() == null ? 0 : s.getSeqNo()).max().orElse(0);
        int pendingMax = pending.stream()
                .filter(s -> s.getMachine().getId().equals(machineId) && s.getShift() == shift)
                .mapToInt(s -> s.getSeqNo() == null ? 0 : s.getSeqNo()).max().orElse(0);
        return Math.max(existing, pendingMax) + 1;
    }

    /**
     * 调班影响分析：把某治疗单调整到目标机位/日期/班次，会影响哪些患者、
     * 哪些机器消毒时间被压缩、哪些耗材已经预领。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> impact(Long sessionId, Long targetMachineId, LocalDate targetDate, Shift targetShift) {
        DialysisSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
        DialysisMachine target = machineRepo.findById(targetMachineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // 1. 分区匹配
        if (!compatibleZones(session.getPatient().getInfectionMarker()).contains(target.getZone())) {
            warnings.add("感染分区不匹配：患者「" + session.getPatient().getName() + "」标记 "
                    + session.getPatient().getInfectionMarker() + "，目标机位 " + target.getCode()
                    + " 为 " + target.getZone() + " 区");
        }
        if (target.getStatus() == MachineStatus.FAULT || target.getStatus() == MachineStatus.MAINTENANCE) {
            warnings.add("目标机位 " + target.getCode() + " 当前状态为 " + target.getStatus() + "，不可排班");
        }

        // 2. 目标机位当日已有治疗单 -> 受影响患者
        List<DialysisSession> conflicts = sessionRepo
                .findBySessionDateAndMachineIdAndStatusNotIn(targetDate, target.getId(), INACTIVE).stream()
                .filter(s -> !s.getId().equals(sessionId))
                .sorted(Comparator.comparing(DialysisSession::getPlannedStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Map<String, Object>> affectedPatients = conflicts.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sessionId", s.getId());
            m.put("patientName", s.getPatient().getName());
            m.put("shift", s.getShift().name());
            m.put("plannedStart", s.getPlannedStart());
            m.put("status", s.getStatus().name());
            return m;
        }).collect(Collectors.toList());

        // 3. 消毒间隔被压缩的检查：把本治疗单插到队尾后，相邻治疗间隔是否小于消毒缓冲
        List<Map<String, Object>> compressed = new ArrayList<>();
        List<DialysisSession> timeline = new ArrayList<>(conflicts);
        DialysisSession probe = new DialysisSession();
        probe.setPatient(session.getPatient());
        probe.setPlannedStart(nextSlot(target, targetDate, targetShift, List.of()));
        probe.setPlannedDurationMin(session.getPlannedDurationMin());
        timeline.add(probe);
        timeline.sort(Comparator.comparing(DialysisSession::getPlannedStart,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (int i = 1; i < timeline.size(); i++) {
            DialysisSession prev = timeline.get(i - 1);
            DialysisSession curr = timeline.get(i);
            if (prev.getPlannedStart() == null || curr.getPlannedStart() == null) continue;
            long gap = java.time.Duration.between(
                    prev.getPlannedStart().plusMinutes(prev.getPlannedDurationMin()),
                    curr.getPlannedStart()).toMinutes();
            if (gap < target.getDisinfectBufferMin()) {
                Map<String, Object> c = new LinkedHashMap<String, Object>();
                c.put("machineCode", target.getCode());
                c.put("prevPatient", prev.getPatient().getName());
                c.put("currPatient", curr.getPatient().getName());
                c.put("gapMinutes", gap);
                c.put("requiredMinutes", target.getDisinfectBufferMin());
                compressed.add(c);
            }
        }

        // 4. 已预领耗材（本治疗单 + 受影响治疗单）
        List<Long> sessionIds = new ArrayList<>();
        sessionIds.add(sessionId);
        conflicts.forEach(s -> sessionIds.add(s.getId()));
        List<Map<String, Object>> preDrawn = txnRepo
                .findByTypeAndSessionIdIn(TxnType.PRE_DRAW, sessionIds).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("consumableName", t.getConsumableName());
                    m.put("qty", -t.getQty());
                    m.put("patientName", t.getPatientName());
                    m.put("sessionId", t.getSessionId());
                    return m;
                }).collect(Collectors.toList());

        // 5. 患者同日同班次是否已有其它治疗单
        if (sessionRepo.existsByPatientIdAndSessionDateAndShiftAndStatusNotIn(
                session.getPatient().getId(), targetDate, targetShift, INACTIVE)
                && !(session.getSessionDate().equals(targetDate) && session.getShift() == targetShift)) {
            warnings.add("患者在该日期该班次已有其它治疗单，请确认是否重复排班");
        }

        result.put("sessionId", sessionId);
        result.put("patientName", session.getPatient().getName());
        result.put("targetMachine", target.getCode());
        result.put("targetDate", targetDate.toString());
        result.put("targetShift", targetShift.name());
        result.put("estimatedStart", probe.getPlannedStart());
        result.put("affectedPatients", affectedPatients);
        result.put("compressedDisinfection", compressed);
        result.put("preDrawnConsumables", preDrawn);
        result.put("warnings", warnings);
        result.put("ok", warnings.isEmpty() && compressed.isEmpty());
        return result;
    }

    /** 执行调班 */
    @Transactional
    public DialysisSession move(Long sessionId, Long targetMachineId, LocalDate targetDate, Shift targetShift) {
        DialysisSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
        if (session.getStatus() == SessionStatus.ON_DIALYSIS || session.getStatus() == SessionStatus.OFF_DIALYSIS
                || session.getStatus() == SessionStatus.FINISHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "治疗已开始或结束，不能调班");
        }
        DialysisMachine target = machineRepo.findById(targetMachineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));
        if (target.getStatus() == MachineStatus.FAULT || target.getStatus() == MachineStatus.MAINTENANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标机位故障或维护中，不可排班");
        }
        if (!compatibleZones(session.getPatient().getInfectionMarker()).contains(target.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "感染分区不匹配，不能调整到 " + target.getZone() + " 区机位");
        }
        session.setMachine(target);
        session.setSessionDate(targetDate);
        session.setShift(targetShift);
        session.setPlannedStart(nextSlot(target, targetDate, targetShift, List.of()));
        session.setSeqNo(nextSeqNo(target.getId(), targetDate, targetShift, List.of()));
        return sessionRepo.save(session);
    }

    private static boolean patternContains(String pattern, int dow) {
        if (pattern == null || pattern.isBlank()) return false;
        return Arrays.stream(pattern.split(",")).map(String::trim).anyMatch(p -> p.equals(String.valueOf(dow)));
    }

    /** 排序：风险高者优先 -> 班车接送优先 -> 病历号 */
    private static Comparator<Patient> patientComparator() {
        Map<RiskLevel, Integer> riskOrder = Map.of(RiskLevel.HIGH, 0, RiskLevel.MEDIUM, 1, RiskLevel.LOW, 2);
        return Comparator.<Patient>comparingInt(p -> riskOrder.getOrDefault(p.getRiskLevel(), 2))
                .thenComparingInt(p -> p.getTransportMode() == TransportMode.SHUTTLE ? 0 : 1)
                .thenComparing(Patient::getMrn);
    }
}
