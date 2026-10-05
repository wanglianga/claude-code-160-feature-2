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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 机位故障改排：自检失败后筛出受影响患者、传染病分区要求与可用备用机位，
 * 支持延后 / 换机 / 改班次 / 协商调换（双方确认），
 * 并把患者通知、家属接送、机位消毒、耗材领用重新串成联动链。
 */
@Service
@RequiredArgsConstructor
public class RescheduleService {

    private final SessionRepository sessionRepo;
    private final MachineRepository machineRepo;
    private final RescheduleRecordRepository recordRepo;
    private final ConsumableTxnRepository txnRepo;
    private final DisinfectionRecordRepository disinfectionRepo;
    private final ExceptionEventRepository exceptionRepo;
    private final SchedulingService schedulingService;

    private static final List<SessionStatus> RESCHEDULABLE = List.of(SessionStatus.SCHEDULED, SessionStatus.ASSESSED);
    private static final List<SessionStatus> INACTIVE = List.of(SessionStatus.CANCELLED, SessionStatus.ABSENT);

    /**
     * 故障改排筛查：某机位当天受影响患者（含传染病分区要求）与可用备用机位。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> options(Long machineId, LocalDate date) {
        DialysisMachine machine = getMachine(machineId);

        // 受影响治疗单：当日该机位上尚未开始透析的治疗单
        List<DialysisSession> daySessions = sessionRepo.findBySessionDateAndMachineIdAndStatusNotIn(
                date, machineId, INACTIVE);
        List<DialysisSession> affected = daySessions.stream()
                .filter(s -> RESCHEDULABLE.contains(s.getStatus()))
                .sorted(Comparator.comparing(DialysisSession::getPlannedStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<DialysisSession> onDialysis = daySessions.stream()
                .filter(s -> s.getStatus() == SessionStatus.ON_DIALYSIS)
                .toList();

        List<Map<String, Object>> affectedPatients = affected.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            Patient p = s.getPatient();
            m.put("sessionId", s.getId());
            m.put("patientName", p.getName());
            m.put("infectionMarker", p.getInfectionMarker().name());
            // 传染病分区要求：该患者可上机的分区
            m.put("requiredZones", SchedulingService.compatibleZones(p.getInfectionMarker())
                    .stream().map(MachineZone::name).toList());
            m.put("riskLevel", p.getRiskLevel().name());
            m.put("shift", s.getShift().name());
            m.put("plannedStart", s.getPlannedStart());
            m.put("plannedDurationMin", s.getPlannedDurationMin());
            m.put("status", s.getStatus().name());
            m.put("nurseName", s.getNurseName());
            m.put("transportMode", p.getTransportMode().name());
            m.put("transportWindow", p.getTransportWindow());
            return m;
        }).collect(Collectors.toList());

        List<Map<String, Object>> onDialysisPatients = onDialysis.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sessionId", s.getId());
            m.put("patientName", s.getPatient().getName());
            m.put("status", s.getStatus().name());
            return m;
        }).collect(Collectors.toList());

        // 可用备用机位：启用中、非故障/维护，给出各班次下一可用上机时刻
        List<Map<String, Object>> spareMachines = machineRepo.findByActiveTrueOrderByCode().stream()
                .filter(m -> !m.getId().equals(machineId))
                .filter(m -> m.getStatus() != MachineStatus.FAULT && m.getStatus() != MachineStatus.MAINTENANCE)
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("machineId", m.getId());
                    row.put("code", m.getCode());
                    row.put("zone", m.getZone().name());
                    row.put("status", m.getStatus().name());
                    row.put("disinfectBufferMin", m.getDisinfectBufferMin());
                    row.put("todaySessions", (int) sessionRepo
                            .findBySessionDateAndMachineIdAndStatusNotIn(date, m.getId(), INACTIVE).size());
                    Map<String, Object> slots = new LinkedHashMap<String, Object>();
                    for (Shift shift : Shift.values()) {
                        slots.put(shift.name(), schedulingService.nextSlot(m, date, shift, List.of()));
                    }
                    row.put("nextSlots", slots);
                    return row;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("machine", machine);
        result.put("date", date.toString());
        result.put("affectedSessions", affectedPatients);
        result.put("onDialysisSessions", onDialysisPatients);
        result.put("spareMachines", spareMachines);
        return result;
    }

    /** 改排请求 */
    public record ApplyRequest(Long sessionId, RescheduleAction action,
                               Long faultMachineId,
                               Long toMachineId, Shift toShift, Integer delayMinutes,
                               Long swapSessionId, String confirmA, String confirmB,
                               Boolean arrived, String compensation, String transportAdjust,
                               NotifyChannel notifyChannel, String notifyNote, Boolean familyNotified) {}

    /**
     * 执行故障改排：延后 / 换机 / 改班次 / 协商调换。
     * 同时落台账、联动故障异常单，并返回改排后的联动链。
     */
    @Transactional
    public Map<String, Object> apply(ApplyRequest req, String operator) {
        if (req.sessionId() == null || req.action() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少治疗单或改排方式");
        }
        DialysisSession session = getSession(req.sessionId());
        if (!RESCHEDULABLE.contains(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "治疗已开始或结束，不能改排");
        }
        boolean arrived = Boolean.TRUE.equals(req.arrived());
        if (arrived && (req.compensation() == null || req.compensation().isBlank()
                || req.transportAdjust() == null || req.transportAdjust().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "患者已到院，请前台记录等候补偿与交通调整后再提交");
        }

        // 故障机位：优先取请求指定（改排前机器可能已换过），否则取治疗单当前机位
        DialysisMachine faultMachine = req.faultMachineId() != null
                ? getMachine(req.faultMachineId()) : session.getMachine();

        RescheduleRecord record = new RescheduleRecord();
        record.setSessionId(session.getId());
        record.setPatientId(session.getPatient().getId());
        record.setPatientName(session.getPatient().getName());
        record.setFaultMachineId(faultMachine.getId());
        record.setFaultMachineCode(faultMachine.getCode());
        record.setSessionDate(session.getSessionDate());
        record.setFromShift(session.getShift());
        record.setFromMachineCode(session.getMachine().getCode());
        record.setFromPlannedStart(session.getPlannedStart());
        record.setAction(req.action());

        List<String> warnings = new ArrayList<>();
        DialysisSession swapSession = null;

        switch (req.action()) {
            case DELAY -> {
                int minutes = req.delayMinutes() == null ? 0 : req.delayMinutes();
                if (minutes <= 0 || minutes > 480) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "延后时长需在 1-480 分钟之间");
                }
                LocalDateTime oldStart = session.getPlannedStart() != null ? session.getPlannedStart()
                        : SchedulingService.shiftStart(session.getSessionDate(), session.getShift());
                session.setPlannedStart(oldStart.plusMinutes(minutes));
                sessionRepo.save(session);
                record.setToMachineId(session.getMachine().getId());
                record.setToMachineCode(session.getMachine().getCode());
                record.setToShift(session.getShift());
                record.setToPlannedStart(session.getPlannedStart());
                warnings.addAll(delayOverlapWarnings(session, oldStart));
            }
            case SWITCH_MACHINE -> {
                if (req.toMachineId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择备用机位");
                }
                schedulingService.move(session.getId(), req.toMachineId(),
                        session.getSessionDate(), session.getShift());
                record.setToMachineId(session.getMachine().getId());
                record.setToMachineCode(session.getMachine().getCode());
                record.setToShift(session.getShift());
                record.setToPlannedStart(session.getPlannedStart());
            }
            case SHIFT_CHANGE -> {
                if (req.toShift() == null || req.toShift() == session.getShift()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不同的目标班次");
                }
                DialysisMachine target = req.toMachineId() != null
                        ? getMachine(req.toMachineId())
                        : pickSpareMachine(session, req.toShift());
                schedulingService.move(session.getId(), target.getId(),
                        session.getSessionDate(), req.toShift());
                record.setToMachineId(session.getMachine().getId());
                record.setToMachineCode(session.getMachine().getCode());
                record.setToShift(session.getShift());
                record.setToPlannedStart(session.getPlannedStart());
            }
            case SWAP -> {
                if (req.swapSessionId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择协商调换的对方治疗单");
                }
                if (req.confirmA() == null || req.confirmA().isBlank()
                        || req.confirmB() == null || req.confirmB().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "协商调换必须记录双方确认人，防止口头换班争议");
                }
                swapSession = getSession(req.swapSessionId());
                swap(session, swapSession);
                record.setSwapSessionId(swapSession.getId());
                record.setSwapPatientName(swapSession.getPatient().getName());
                record.setConfirmA(req.confirmA().trim());
                record.setConfirmB(req.confirmB().trim());
                record.setToMachineId(session.getMachine().getId());
                record.setToMachineCode(session.getMachine().getCode());
                record.setToShift(session.getShift());
                record.setToPlannedStart(session.getPlannedStart());
            }
        }

        // 通知与到院处置
        record.setArrived(arrived);
        record.setCompensation(arrived && req.compensation() != null ? req.compensation().trim() : null);
        record.setTransportAdjust(arrived && req.transportAdjust() != null ? req.transportAdjust().trim() : null);
        record.setNotifyChannel(req.notifyChannel());
        record.setNotifyNote(req.notifyNote());
        record.setFamilyNotified(Boolean.TRUE.equals(req.familyNotified()));
        record.setOperator(operator);
        record = recordRepo.save(record);

        // 联动：故障异常单转处理中
        linkFaultExceptions(record, faultMachine, operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", record);
        result.put("session", session);
        if (swapSession != null) {
            result.put("swapSession", swapSession);
        }
        result.put("warnings", warnings);
        result.put("chain", chain(record.getId()));
        return result;
    }

    /** 协商调换：两条治疗单互换机位/班次/计划开始/顺序，双向校验分区与机位状态 */
    private void swap(DialysisSession a, DialysisSession b) {
        if (a.getId().equals(b.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能与同一治疗单调换");
        }
        if (!RESCHEDULABLE.contains(b.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对方治疗单当前状态不能调换");
        }
        DialysisMachine machineA = a.getMachine();
        DialysisMachine machineB = b.getMachine();
        if (machineA.getStatus() == MachineStatus.FAULT || machineA.getStatus() == MachineStatus.MAINTENANCE
                || machineB.getStatus() == MachineStatus.FAULT || machineB.getStatus() == MachineStatus.MAINTENANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "调换涉及故障/维护机位，请先将故障机位上的治疗单改排到备用机位，再协商调换");
        }
        if (!SchedulingService.compatibleZones(a.getPatient().getInfectionMarker()).contains(machineB.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "患者「" + a.getPatient().getName() + "」与对方机位 " + machineB.getCode()
                            + "（" + machineB.getZone() + " 区）分区不匹配，不能调换");
        }
        if (!SchedulingService.compatibleZones(b.getPatient().getInfectionMarker()).contains(machineA.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "对方患者「" + b.getPatient().getName() + "」与机位 " + machineA.getCode()
                            + "（" + machineA.getZone() + " 区）分区不匹配，不能调换");
        }
        // 暂存 a 的排位，再双向交换
        Shift shiftA = a.getShift();
        LocalDateTime startA = a.getPlannedStart();
        Integer seqA = a.getSeqNo();

        a.setMachine(machineB);
        a.setShift(b.getShift());
        a.setPlannedStart(b.getPlannedStart());
        a.setSeqNo(b.getSeqNo());

        b.setMachine(machineA);
        b.setShift(shiftA);
        b.setPlannedStart(startA);
        b.setSeqNo(seqA);

        sessionRepo.save(a);
        sessionRepo.save(b);
    }

    /** 改排台账：某日全部改排记录 */
    @Transactional(readOnly = true)
    public List<RescheduleRecord> records(LocalDate date) {
        return recordRepo.findBySessionDateOrderByCreatedAtDesc(date);
    }

    /** 协商调换候选：当日可互换的治疗单，标注分区/机位是否兼容 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> swapCandidates(Long sessionId) {
        DialysisSession session = getSession(sessionId);
        return sessionRepo.findBySessionDateOrderByShiftAscSeqNoAsc(session.getSessionDate()).stream()
                .filter(s -> !s.getId().equals(sessionId))
                .filter(s -> RESCHEDULABLE.contains(s.getStatus()))
                .map(s -> {
                    boolean zoneOk = SchedulingService.compatibleZones(session.getPatient().getInfectionMarker())
                            .contains(s.getMachine().getZone())
                            && SchedulingService.compatibleZones(s.getPatient().getInfectionMarker())
                            .contains(session.getMachine().getZone());
                    boolean machineOk = s.getMachine().getStatus() != MachineStatus.FAULT
                            && s.getMachine().getStatus() != MachineStatus.MAINTENANCE;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("sessionId", s.getId());
                    m.put("patientName", s.getPatient().getName());
                    m.put("infectionMarker", s.getPatient().getInfectionMarker().name());
                    m.put("machineCode", s.getMachine().getCode());
                    m.put("zone", s.getMachine().getZone().name());
                    m.put("shift", s.getShift().name());
                    m.put("plannedStart", s.getPlannedStart());
                    m.put("compatible", zoneOk && machineOk);
                    m.put("reason", !machineOk ? "对方机位故障/维护中"
                            : !zoneOk ? "感染分区不匹配" : "");
                    return m;
                })
                .sorted(Comparator.<Map<String, Object>, Boolean>comparing(m -> !(Boolean) m.get("compatible"))
                        .thenComparing(m -> (LocalDateTime) m.get("plannedStart"),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 联动链：把患者通知、家属接送、机位消毒、耗材领用重新串起来（患者已到院时追加到院补偿）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> chain(Long recordId) {
        RescheduleRecord r = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "改排记录不存在"));
        DialysisSession session = sessionRepo.findById(r.getSessionId()).orElse(null);
        Patient patient = session != null ? session.getPatient() : null;

        List<Map<String, Object>> links = new ArrayList<>();

        // 1. 患者通知
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("key", "notify");
        notify.put("title", "患者通知");
        notify.put("done", r.getNotifyChannel() != null);
        notify.put("detail", r.getNotifyChannel() != null
                ? "已通过「" + notifyChannelLabel(r.getNotifyChannel()) + "」通知患者「" + r.getPatientName() + "」"
                + (r.getNotifyNote() != null && !r.getNotifyNote().isBlank() ? "：" + r.getNotifyNote() : "")
                : "尚未登记通知方式");
        notify.put("operator", r.getOperator());
        notify.put("time", r.getCreatedAt());
        links.add(notify);

        // 2. 家属接送
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("key", "family");
        family.put("title", "家属接送");
        boolean needPickup = patient != null && patient.getTransportMode() != TransportMode.SELF;
        StringBuilder familyDetail = new StringBuilder();
        if (patient != null) {
            familyDetail.append("接送方式：").append(transportLabel(patient.getTransportMode()));
            if (patient.getTransportWindow() != null) {
                familyDetail.append("（").append(patient.getTransportWindow()).append("）");
            }
        }
        if (r.getTransportAdjust() != null) {
            familyDetail.append("；交通调整：").append(r.getTransportAdjust());
        }
        family.put("done", !needPickup || Boolean.TRUE.equals(r.getFamilyNotified()));
        family.put("detail", needPickup
                ? familyDetail + (Boolean.TRUE.equals(r.getFamilyNotified()) ? "；家属/班车已通知" : "；待通知家属/班车")
                : familyDetail.toString());
        links.add(family);

        // 3. 机位消毒：故障机位修复后须消毒合格方可复用；目标机位给出消毒间隔与最近消毒
        Map<String, Object> disinfection = new LinkedHashMap<>();
        disinfection.put("key", "disinfection");
        disinfection.put("title", "机位消毒");
        List<String> disItems = new ArrayList<>();
        boolean disDone = true;
        if (r.getFaultMachineId() != null) {
            DialysisMachine fm = machineRepo.findById(r.getFaultMachineId()).orElse(null);
            DisinfectionRecord last = latestDisinfection(r.getFaultMachineId());
            disItems.add("故障机位 " + r.getFaultMachineCode() + " 当前状态 "
                    + (fm != null ? fm.getStatus() : "-") + "，修复后须消毒合格方可复用"
                    + (last != null ? "（最近消毒：" + last.getDoneAt().toLocalDate() + " "
                    + last.getDoneAt().toLocalTime().withNano(0) + " " + last.getType() + " "
                    + last.getResult() + "）" : "（暂无消毒记录）"));
            if (fm != null && (fm.getStatus() == MachineStatus.FAULT || fm.getStatus() == MachineStatus.MAINTENANCE)) {
                disDone = false;
            }
        }
        if (r.getToMachineId() != null && !r.getToMachineId().equals(r.getFaultMachineId())) {
            DialysisMachine tm = machineRepo.findById(r.getToMachineId()).orElse(null);
            DisinfectionRecord last = latestDisinfection(r.getToMachineId());
            if (tm != null) {
                disItems.add("目标机位 " + tm.getCode() + " 消毒间隔 " + tm.getDisinfectBufferMin() + " 分钟"
                        + (last != null ? "，最近消毒 " + last.getDoneAt().toLocalDate() + " "
                        + last.getDoneAt().toLocalTime().withNano(0) : ""));
            }
        }
        disinfection.put("done", disDone);
        disinfection.put("items", disItems);
        disinfection.put("detail", String.join("；", disItems));
        links.add(disinfection);

        // 4. 耗材领用：预领耗材随治疗单转移
        Map<String, Object> consumable = new LinkedHashMap<>();
        consumable.put("key", "consumable");
        consumable.put("title", "耗材领用");
        List<Map<String, Object>> preDrawn = txnRepo.findBySessionId(r.getSessionId()).stream()
                .filter(t -> t.getType() == TxnType.PRE_DRAW)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("consumableName", t.getConsumableName());
                    m.put("qty", -t.getQty());
                    return m;
                }).collect(Collectors.toList());
        consumable.put("done", !preDrawn.isEmpty());
        consumable.put("items", preDrawn);
        consumable.put("detail", preDrawn.isEmpty()
                ? "该治疗单暂无预领耗材"
                : "已预领 " + preDrawn.stream().map(i -> i.get("consumableName") + "×" + i.get("qty"))
                .collect(Collectors.joining("、")) + "，随治疗单一并转移至新机位/班次");
        links.add(consumable);

        // 5. 到院补偿（患者已到院时追加）
        if (Boolean.TRUE.equals(r.getArrived())) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("key", "compensation");
            comp.put("title", "到院补偿");
            comp.put("done", r.getCompensation() != null && !r.getCompensation().isBlank());
            comp.put("detail", "等候补偿：" + (r.getCompensation() != null ? r.getCompensation() : "未记录")
                    + "；交通调整：" + (r.getTransportAdjust() != null ? r.getTransportAdjust() : "未记录"));
            links.add(comp);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", r);
        result.put("links", links);
        return result;
    }

    // ---- 内部辅助 ----

    /** 延后后提示与后续治疗单的重叠（建议一并顺延或调班） */
    private List<String> delayOverlapWarnings(DialysisSession session, LocalDateTime oldStart) {
        LocalDateTime newEnd = session.getPlannedStart()
                .plusMinutes(session.getPlannedDurationMin() + session.getMachine().getDisinfectBufferMin());
        return sessionRepo.findBySessionDateAndMachineIdAndStatusNotIn(
                        session.getSessionDate(), session.getMachine().getId(), INACTIVE).stream()
                .filter(s -> !s.getId().equals(session.getId()))
                .filter(s -> s.getPlannedStart() != null && s.getPlannedStart().isAfter(oldStart)
                        && s.getPlannedStart().isBefore(newEnd))
                .map(s -> "后续治疗「" + s.getPatient().getName() + "」（原计划 "
                        + s.getPlannedStart().toLocalTime().withNano(0) + "）与延后时间重叠，建议一并顺延或调班")
                .collect(Collectors.toList());
    }

    /** 改班次时自动挑选分区匹配、下一可用时刻最早的备用机位 */
    private DialysisMachine pickSpareMachine(DialysisSession session, Shift shift) {
        DialysisMachine best = null;
        LocalDateTime bestSlot = null;
        for (DialysisMachine m : machineRepo.findByActiveTrueOrderByCode()) {
            if (m.getStatus() == MachineStatus.FAULT || m.getStatus() == MachineStatus.MAINTENANCE) continue;
            if (!SchedulingService.compatibleZones(session.getPatient().getInfectionMarker())
                    .contains(m.getZone())) continue;
            LocalDateTime slot = schedulingService.nextSlot(m, session.getSessionDate(), shift, List.of());
            if (bestSlot == null || slot.isBefore(bestSlot)
                    || (slot.equals(bestSlot) && m.getCode().compareTo(best.getCode()) < 0)) {
                best = m;
                bestSlot = slot;
            }
        }
        if (best == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标班次没有分区匹配的可用机位");
        }
        return best;
    }

    /** 联动：该故障机位今日未结的故障异常单转「处理中」并备注改排结果 */
    private void linkFaultExceptions(RescheduleRecord record, DialysisMachine faultMachine, String operator) {
        LocalDate date = record.getSessionDate();
        List<ExceptionEvent> open = new ArrayList<>(
                exceptionRepo.findByStatusOrderByCreatedAtDesc(ExceptionStatus.OPEN));
        open.addAll(exceptionRepo.findByStatusOrderByCreatedAtDesc(ExceptionStatus.PROCESSING));
        open.stream()
                .filter(e -> e.getType() == ExceptionType.MACHINE_FAULT)
                .filter(e -> faultMachine.getId().equals(e.getMachineId()))
                .filter(e -> e.getCreatedAt().toLocalDate().equals(date))
                .forEach(e -> {
                    e.setStatus(ExceptionStatus.PROCESSING);
                    e.setHandlerName(operator);
                    String note = "改排记录#" + record.getId() + "：患者「" + record.getPatientName() + "」"
                            + actionLabel(record.getAction()) + " → "
                            + (record.getToMachineCode() != null ? record.getToMachineCode() : "-")
                            + " " + (record.getToShift() != null ? record.getToShift() : "-")
                            + (record.getToPlannedStart() != null
                            ? " " + record.getToPlannedStart().toLocalTime().withNano(0) : "");
                    e.setHandleNote(e.getHandleNote() == null ? note : e.getHandleNote() + "；" + note);
                    exceptionRepo.save(e);
                });
    }

    private DisinfectionRecord latestDisinfection(Long machineId) {
        return disinfectionRepo.findByMachineIdOrderByDoneAtDesc(machineId).stream().findFirst().orElse(null);
    }

    private DialysisSession getSession(Long id) {
        return sessionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
    }

    private DialysisMachine getMachine(Long id) {
        return machineRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));
    }

    public static String actionLabel(RescheduleAction action) {
        return switch (action) {
            case DELAY -> "延后";
            case SWITCH_MACHINE -> "换机";
            case SHIFT_CHANGE -> "改班次";
            case SWAP -> "协商调换";
        };
    }

    private static String notifyChannelLabel(NotifyChannel channel) {
        return switch (channel) {
            case PHONE -> "电话通知";
            case SMS -> "短信通知";
            case IN_PERSON -> "现场告知";
            case FAMILY_PHONE -> "通知家属";
        };
    }

    private static String transportLabel(TransportMode mode) {
        return switch (mode) {
            case SELF -> "自行来院";
            case FAMILY -> "家属接送";
            case SHUTTLE -> "班车接送";
        };
    }
}
