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
 * 机位故障改排：自检失败/故障后，把当天受影响患者筛出来，
 * 由护士长逐条决策（延后/换机/改下午班/协商调换），
 * 并把患者通知、家属接送、机位消毒、耗材领用、到院补偿重新串成闭环。
 */
@Service
@RequiredArgsConstructor
public class RescheduleService {

    private final ReschedulePlanRepository planRepo;
    private final RescheduleActionRepository actionRepo;
    private final SessionRepository sessionRepo;
    private final MachineRepository machineRepo;
    private final ConsumableRepository consumableRepo;
    private final ConsumableTxnRepository txnRepo;
    private final DisinfectionRecordRepository disinfectionRepo;
    private final SchedulingService schedulingService;
    private final ConsumableService consumableService;
    private final ExceptionService exceptionService;

    /** 受影响治疗单：当天该机位上尚未上机的 */
    private static final List<SessionStatus> NOT_AFFECTED = List.of(
            SessionStatus.ON_DIALYSIS, SessionStatus.OFF_DIALYSIS, SessionStatus.FINISHED,
            SessionStatus.ABSENT, SessionStatus.CANCELLED);
    private static final List<SessionStatus> SWAPPABLE = List.of(SessionStatus.SCHEDULED, SessionStatus.ASSESSED);

    // ---------------------------------------------------------------- 方案

    /**
     * 故障申报并创建改排方案：机位置为故障、筛出当天受影响患者、
     * 生成待决策动作、上报异常并下达修复后消毒任务。
     */
    @Transactional
    public Map<String, Object> createPlan(Long machineId, LocalDate date, String reason,
                                          LocalDateTime expectedRepairAt, String operator) {
        if (machineId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择故障机位");
        }
        DialysisMachine machine = machineRepo.findById(machineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));
        LocalDate faultDate = date != null ? date : LocalDate.now();
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写故障原因");
        }
        if (planRepo.existsByMachineIdAndFaultDateAndStatus(machineId, faultDate, ReschedulePlanStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该机位当天已有进行中的改排方案，请直接处理");
        }

        machine.setStatus(MachineStatus.FAULT);
        machineRepo.save(machine);

        ReschedulePlan plan = new ReschedulePlan();
        plan.setMachine(machine);
        plan.setFaultDate(faultDate);
        plan.setReason(reason.trim());
        plan.setExpectedRepairAt(expectedRepairAt);
        plan.setCreatedBy(operator);
        plan = planRepo.save(plan);

        // 当天受影响患者（未上机的治疗单）
        List<DialysisSession> affected = sessionRepo
                .findBySessionDateAndMachineIdAndStatusNotIn(faultDate, machineId, NOT_AFFECTED);
        for (DialysisSession s : affected) {
            RescheduleAction action = createAction(plan, s);
            actionRepo.save(action);
        }

        String affectedNames = affected.stream()
                .map(s -> s.getPatient().getName() + "(" + s.getShift() + ")")
                .collect(Collectors.joining("、"));
        exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, machine,
                "机位 " + machine.getCode() + " 故障改排方案已创建：" + reason
                        + (affected.isEmpty() ? "。当天无受影响患者" : "。当天受影响患者：" + affectedNames),
                operator);
        // 消毒计划：修复后须消毒合格方可重新启用
        exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, machine,
                "消毒计划：机位 " + machine.getCode() + " 修复后须完成热消毒并登记合格记录，方可重新排班启用",
                operator);
        return detail(plan.getId());
    }

    private RescheduleAction createAction(ReschedulePlan plan, DialysisSession s) {
        RescheduleAction action = new RescheduleAction();
        action.setPlan(plan);
        action.setSession(s);
        action.setPatientName(s.getPatient().getName());
        action.setInfectionMarker(s.getPatient().getInfectionMarker());
        return action;
    }

    /** 方案列表（含处理进度） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPlans(LocalDate date, ReschedulePlanStatus status) {
        List<ReschedulePlan> plans = date != null
                ? planRepo.findByFaultDateOrderByCreatedAtDesc(date)
                : (status != null ? planRepo.findByStatusOrderByCreatedAtDesc(status)
                        : planRepo.findTop20ByOrderByCreatedAtDesc());
        return plans.stream().map(p -> {
            List<RescheduleAction> actions = actionRepo.findByPlanIdOrderById(p.getId());
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("plan", p);
            m.put("actionTotal", actions.size());
            m.put("decidedCount", actions.stream().filter(a -> a.getStatus() == RescheduleActionStatus.DONE).count());
            m.put("notifiedCount", actions.stream().filter(a -> a.getNotifyStatus() == NotifyStatus.DONE).count());
            m.put("arrivedCount", actions.stream().filter(RescheduleAction::getPatientArrived).count());
            return m;
        }).collect(Collectors.toList());
    }

    /** 登记/更新预计修复时间 */
    @Transactional
    public Map<String, Object> updateRepairTime(Long planId, LocalDateTime expectedRepairAt, String operator) {
        ReschedulePlan plan = getPlan(planId);
        requireOpen(plan);
        if (expectedRepairAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预计修复时间不能为空");
        }
        plan.setExpectedRepairAt(expectedRepairAt);
        planRepo.save(plan);
        exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, plan.getMachine(),
                "机位 " + plan.getMachine().getCode() + " 预计修复时间更新为 " + expectedRepairAt, operator);
        return detail(planId);
    }

    // ---------------------------------------------------------------- 决策

    /**
     * 护士长决策：延后（等同机修复）/ 换机（备用机位）/ 改到下午班。
     * 换机与改班会联动归还并重新预领耗材。
     */
    @Transactional
    public Map<String, Object> decide(Long actionId, RescheduleActionType type, Long targetMachineId,
                                      Integer delayMinutes, String note, String operator) {
        RescheduleAction action = getAction(actionId);
        ReschedulePlan plan = action.getPlan();
        requireOpen(plan);
        if (action.getStatus() == RescheduleActionStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该患者已完成改排决策");
        }
        if (type == null || type == RescheduleActionType.SWAP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请使用对应接口提交决策（调换走协商调换入口）");
        }
        DialysisSession session = action.getSession();
        if (!SWAPPABLE.contains(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "治疗单当前状态不能改排");
        }

        StringBuilder desc = new StringBuilder();
        switch (type) {
            case DELAY -> delay(plan, action, session, delayMinutes, desc);
            case CHANGE_MACHINE -> changeMachine(action, session, targetMachineId, session.getShift(), desc, operator);
            case SHIFT_PM -> shiftToPm(action, session, targetMachineId, desc, operator);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的改排方式");
        }

        sessionRepo.save(session);
        appendNote(session, "[故障改排] " + desc);

        action.setActionType(type);
        action.setStatus(RescheduleActionStatus.DONE);
        action.setDecidedBy(operator);
        action.setDecidedAt(LocalDateTime.now());
        action.setNote(note);
        actionRepo.save(action);

        exceptionService.raise(ExceptionType.MACHINE_FAULT, session.getPatient(), session, session.getMachine(),
                "改排决策（机位 " + plan.getMachine().getCode() + " 故障）：" + desc
                        + (note != null && !note.isBlank() ? "。备注：" + note : ""),
                operator);
        return detail(plan.getId());
    }

    /** 延后：留在原机位，等修复后推迟上机 */
    private void delay(ReschedulePlan plan, RescheduleAction action, DialysisSession session,
                       Integer delayMinutes, StringBuilder desc) {
        if (delayMinutes == null || delayMinutes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "延后方式必须填写延后分钟数");
        }
        LocalDateTime newStart = session.getPlannedStart().plusMinutes(delayMinutes);
        if (plan.getExpectedRepairAt() != null && newStart.isBefore(plan.getExpectedRepairAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "延后后的上机时间 " + newStart + " 早于预计修复时间 " + plan.getExpectedRepairAt()
                            + "，请加大延后时长或先更新预计修复时间");
        }
        // 与当天后续治疗（同机位、同班次之后）消毒间隔提示
        DialysisMachine machine = session.getMachine();
        List<DialysisSession> later = sessionRepo
                .findBySessionDateAndMachineIdAndStatusNotIn(plan.getFaultDate(), machine.getId(), NOT_AFFECTED)
                .stream()
                .filter(s -> !s.getId().equals(session.getId()))
                .filter(s -> s.getPlannedStart() != null && s.getPlannedStart().isAfter(session.getPlannedStart()))
                .sorted(Comparator.comparing(DialysisSession::getPlannedStart))
                .toList();
        String squeeze = "";
        if (!later.isEmpty()) {
            LocalDateTime newEnd = newStart.plusMinutes(session.getPlannedDurationMin() + machine.getDisinfectBufferMin());
            DialysisSession next = later.get(0);
            if (newEnd.isAfter(next.getPlannedStart())) {
                squeeze = "；注意：与后续治疗（" + next.getPatient().getName() + " "
                        + next.getPlannedStart().toLocalTime() + "）消毒间隔不足，请一并处理该患者的改排";
            }
        }
        session.setPlannedStart(newStart);
        action.setDelayMinutes(delayMinutes);
        action.setTargetMachineId(machine.getId());
        action.setTargetMachineCode(machine.getCode());
        action.setTargetShift(session.getShift());
        desc.append("患者「").append(session.getPatient().getName()).append("」延后 ").append(delayMinutes)
                .append(" 分钟，留在机位 ").append(machine.getCode()).append("，预计 ")
                .append(newStart.toLocalTime()).append(" 上机").append(squeeze);
    }

    /** 换机/改班公共逻辑：校验目标机位、计算上机时刻与顺序、联动耗材 */
    private void moveTo(RescheduleAction action, DialysisSession session, DialysisMachine target,
                        Shift targetShift, StringBuilder desc, String operator) {
        if (!Boolean.TRUE.equals(target.getActive()) || target.getStatus() == MachineStatus.FAULT
                || target.getStatus() == MachineStatus.MAINTENANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "目标机位 " + target.getCode() + " 不可用（故障/维护/停用）");
        }
        if (!SchedulingService.compatibleZones(session.getPatient().getInfectionMarker())
                .contains(target.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "感染分区不匹配：患者标记 " + session.getPatient().getInfectionMarker()
                            + "，不能安排到 " + target.getZone() + " 区机位 " + target.getCode());
        }
        LocalDate date = session.getSessionDate();
        LocalDateTime slot = schedulingService.nextSlot(target, date, targetShift, List.of());
        int seqNo = schedulingService.nextSeqNo(target.getId(), date, targetShift, List.of());

        session.setMachine(target);
        session.setShift(targetShift);
        session.setPlannedStart(slot);
        session.setSeqNo(seqNo);

        action.setTargetMachineId(target.getId());
        action.setTargetMachineCode(target.getCode());
        action.setTargetShift(targetShift);

        int returned = relinkConsumables(session, operator);
        desc.append("，改至机位 ").append(target.getCode()).append("（")
                .append(targetShift).append("），预计 ").append(slot.toLocalTime())
                .append(" 上机；已归还原预领耗材 ").append(returned).append(" 项并按新机位重新预领");
    }

    private void changeMachine(RescheduleAction action, DialysisSession session, Long targetMachineId,
                               Shift targetShift, StringBuilder desc, String operator) {
        if (targetMachineId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "换机方式必须选择目标机位");
        }
        DialysisMachine target = machineRepo.findById(targetMachineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标机位不存在"));
        desc.append("患者「").append(session.getPatient().getName()).append("」换机");
        moveTo(action, session, target, targetShift, desc, operator);
    }

    /** 改到下午班：自动挑选下午班最早可用的同分区机位（也可指定） */
    private void shiftToPm(RescheduleAction action, DialysisSession session, Long targetMachineId,
                           StringBuilder desc, String operator) {
        if (session.getShift() == Shift.PM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "患者本就在下午班，请选择换机");
        }
        DialysisMachine target;
        if (targetMachineId != null) {
            target = machineRepo.findById(targetMachineId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标机位不存在"));
        } else {
            LocalDate date = session.getSessionDate();
            List<MachineZone> zones = SchedulingService.compatibleZones(session.getPatient().getInfectionMarker());
            target = machineRepo.findByActiveTrueOrderByCode().stream()
                    .filter(m -> zones.contains(m.getZone()))
                    .filter(m -> m.getStatus() != MachineStatus.FAULT && m.getStatus() != MachineStatus.MAINTENANCE)
                    .min(Comparator.comparing(m -> schedulingService.nextSlot(m, date, Shift.PM, List.of())))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "下午班无可用的 " + zones + " 机位，请考虑延后或协商调换"));
        }
        desc.append("患者「").append(session.getPatient().getName()).append("」改到下午班");
        moveTo(action, session, target, Shift.PM, desc, operator);
    }

    // ---------------------------------------------------------------- 协商调换

    /**
     * 协商调换：本患者与对方患者互换机位/时段，双方确认留痕。
     * 对方将落到故障机位，因此要求已登记预计修复时间且对方新上机时间不早于修复时间。
     */
    @Transactional
    public Map<String, Object> swap(Long actionId, Long otherSessionId, String confirmByA,
                                    String confirmByB, String confirmNote, String operator) {
        RescheduleAction action = getAction(actionId);
        ReschedulePlan plan = action.getPlan();
        requireOpen(plan);
        if (action.getStatus() == RescheduleActionStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该患者已完成改排决策");
        }
        if (confirmByA == null || confirmByA.isBlank() || confirmByB == null || confirmByB.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "协商调换必须记录双方确认人（本患者与对方患者），防止口头换班争议");
        }
        if (plan.getExpectedRepairAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先登记故障机位预计修复时间，再协商调换（对方将落到该机位）");
        }
        if (otherSessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择对方患者的治疗单");
        }

        DialysisSession mine = action.getSession();
        DialysisSession other = sessionRepo.findById(otherSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对方治疗单不存在"));
        if (other.getId().equals(mine.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能与自己调换");
        }
        if (!other.getSessionDate().equals(plan.getFaultDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能与当天治疗单调换");
        }
        if (!SWAPPABLE.contains(mine.getStatus()) || !SWAPPABLE.contains(other.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "双方治疗单都须处于未上机状态才能调换");
        }
        DialysisMachine faultMachine = plan.getMachine();
        DialysisMachine otherMachine = other.getMachine();
        if (otherMachine.getStatus() == MachineStatus.FAULT || otherMachine.getStatus() == MachineStatus.MAINTENANCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对方机位也不可用，无法调换");
        }
        // 双向分区校验
        if (!SchedulingService.compatibleZones(mine.getPatient().getInfectionMarker()).contains(otherMachine.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分区不匹配：本患者不能上 " + otherMachine.getZone() + " 区机位 " + otherMachine.getCode());
        }
        if (!SchedulingService.compatibleZones(other.getPatient().getInfectionMarker()).contains(faultMachine.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分区不匹配：对方患者不能上 " + faultMachine.getZone() + " 区机位 " + faultMachine.getCode());
        }
        if (mine.getPlannedStart().isBefore(plan.getExpectedRepairAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "对方调换后的上机时间 " + mine.getPlannedStart() + " 早于预计修复时间 "
                            + plan.getExpectedRepairAt() + "，请先延后本治疗单或更新修复时间");
        }

        // 互换机位 / 班次 / 上机时刻 / 顺序
        DialysisMachine m1 = mine.getMachine();
        Shift sh1 = mine.getShift();
        LocalDateTime st1 = mine.getPlannedStart();
        Integer sq1 = mine.getSeqNo();

        mine.setMachine(other.getMachine());
        mine.setShift(other.getShift());
        mine.setPlannedStart(other.getPlannedStart());
        mine.setSeqNo(other.getSeqNo());

        other.setMachine(m1);
        other.setShift(sh1);
        other.setPlannedStart(st1);
        other.setSeqNo(sq1);

        sessionRepo.save(mine);
        sessionRepo.save(other);

        // 耗材领用随新机位重新串
        relinkConsumables(mine, operator);
        relinkConsumables(other, operator);

        appendNote(mine, "[协商调换] 与患者「" + other.getPatient().getName() + "」互换机位/时段");
        appendNote(other, "[协商调换] 与患者「" + mine.getPatient().getName() + "」互换机位/时段");

        action.setActionType(RescheduleActionType.SWAP);
        action.setTargetMachineId(mine.getMachine().getId());
        action.setTargetMachineCode(mine.getMachine().getCode());
        action.setTargetShift(mine.getShift());
        action.setSwapSessionId(other.getId());
        action.setSwapPatientName(other.getPatient().getName());
        action.setSwapConfirmByA(confirmByA.trim());
        action.setSwapConfirmByB(confirmByB.trim());
        action.setSwapConfirmNote(confirmNote);
        action.setStatus(RescheduleActionStatus.DONE);
        action.setDecidedBy(operator);
        action.setDecidedAt(LocalDateTime.now());
        actionRepo.save(action);

        String text = "协商调换：患者「" + mine.getPatient().getName() + "」（治疗单#" + mine.getId() + "）与患者「"
                + other.getPatient().getName() + "」（治疗单#" + other.getId() + "）互换机位/时段——"
                + mine.getPatient().getName() + " → " + mine.getMachine().getCode() + " "
                + mine.getPlannedStart().toLocalTime() + "，"
                + other.getPatient().getName() + " → " + other.getMachine().getCode() + " "
                + other.getPlannedStart().toLocalTime()
                + "。双方确认：本患者侧 " + confirmByA.trim() + "，对方患者侧 " + confirmByB.trim()
                + (confirmNote != null && !confirmNote.isBlank() ? "。备注：" + confirmNote : "");
        exceptionService.raise(ExceptionType.MACHINE_FAULT, mine.getPatient(), mine, mine.getMachine(), text, operator);
        exceptionService.raise(ExceptionType.MACHINE_FAULT, other.getPatient(), other, other.getMachine(), text, operator);
        return detail(plan.getId());
    }

    /** 调换候选：当天其他未上机治疗单，标注双向分区匹配与修复时间冲突 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> swapCandidates(Long actionId) {
        RescheduleAction action = getAction(actionId);
        ReschedulePlan plan = action.getPlan();
        DialysisSession mine = action.getSession();
        DialysisMachine faultMachine = plan.getMachine();

        return sessionRepo.findBySessionDateOrderByShiftAscSeqNoAsc(plan.getFaultDate()).stream()
                .filter(s -> !s.getId().equals(mine.getId()))
                .filter(s -> SWAPPABLE.contains(s.getStatus()))
                .filter(s -> !s.getMachine().getId().equals(faultMachine.getId()))
                .filter(s -> s.getMachine().getStatus() != MachineStatus.FAULT
                        && s.getMachine().getStatus() != MachineStatus.MAINTENANCE)
                .map(s -> {
                    boolean zoneOk = SchedulingService.compatibleZones(mine.getPatient().getInfectionMarker())
                            .contains(s.getMachine().getZone())
                            && SchedulingService.compatibleZones(s.getPatient().getInfectionMarker())
                            .contains(faultMachine.getZone());
                    boolean repairConflict = plan.getExpectedRepairAt() != null
                            && mine.getPlannedStart() != null
                            && mine.getPlannedStart().isBefore(plan.getExpectedRepairAt());
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("sessionId", s.getId());
                    m.put("patientName", s.getPatient().getName());
                    m.put("infectionMarker", s.getPatient().getInfectionMarker().name());
                    m.put("machineCode", s.getMachine().getCode());
                    m.put("zone", s.getMachine().getZone().name());
                    m.put("shift", s.getShift().name());
                    m.put("plannedStart", s.getPlannedStart());
                    m.put("zoneOk", zoneOk);
                    m.put("repairConflict", repairConflict);
                    return m;
                })
                .sorted(Comparator.comparing(m -> (LocalDateTime) m.get("plannedStart"),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- 通知 / 到院登记

    /** 登记患者通知（含家属接送安排） */
    @Transactional
    public Map<String, Object> notify(Long actionId, String channel, String note,
                                      String familyPickupNote, String operator) {
        RescheduleAction action = getAction(actionId);
        if (action.getStatus() != RescheduleActionStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成改排决策，再登记患者通知");
        }
        if (channel == null || channel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写通知渠道（电话/短信/家属转告）");
        }
        action.setNotifyStatus(NotifyStatus.DONE);
        action.setNotifyChannel(channel.trim());
        action.setNotifyNote(note);
        action.setFamilyPickupNote(familyPickupNote);
        action.setNotifiedBy(operator);
        action.setNotifiedAt(LocalDateTime.now());
        actionRepo.save(action);
        appendNote(action.getSession(), "[患者通知] " + channel.trim()
                + (note != null && !note.isBlank() ? "：" + note : ""));
        sessionRepo.save(action.getSession());
        return detail(action.getPlan().getId());
    }

    /** 前台登记：患者已到院的等候补偿与交通调整 */
    @Transactional
    public Map<String, Object> arrival(Long actionId, String compensationNote,
                                       String transportAdjustNote, String operator) {
        RescheduleAction action = getAction(actionId);
        requireOpen(action.getPlan());
        if ((compensationNote == null || compensationNote.isBlank())
                && (transportAdjustNote == null || transportAdjustNote.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少填写等候补偿或交通调整其中一项");
        }
        action.setPatientArrived(true);
        action.setCompensationNote(compensationNote);
        action.setTransportAdjustNote(transportAdjustNote);
        action.setFrontDeskBy(operator);
        action.setFrontDeskAt(LocalDateTime.now());
        actionRepo.save(action);
        exceptionService.raise(ExceptionType.MACHINE_FAULT, action.getSession().getPatient(), action.getSession(),
                action.getSession().getMachine(),
                "到院患者登记（机位故障改排）：等候补偿[" + (compensationNote == null ? "-" : compensationNote)
                        + "]，交通调整[" + (transportAdjustNote == null ? "-" : transportAdjustNote) + "]",
                operator);
        return detail(action.getPlan().getId());
    }

    // ---------------------------------------------------------------- 关闭

    /** 关闭方案：要求全部动作已决策且已通知患者 */
    @Transactional
    public Map<String, Object> close(Long planId, String operator) {
        ReschedulePlan plan = getPlan(planId);
        requireOpen(plan);
        List<RescheduleAction> actions = actionRepo.findByPlanIdOrderById(planId);
        List<String> pending = new ArrayList<>();
        for (RescheduleAction a : actions) {
            if (a.getStatus() != RescheduleActionStatus.DONE) {
                pending.add(a.getPatientName() + "：待改排决策");
            } else if (a.getNotifyStatus() != NotifyStatus.DONE) {
                pending.add(a.getPatientName() + "：待通知患者");
            }
        }
        if (!pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "尚有未闭环事项：" + String.join("；", pending));
        }
        plan.setStatus(ReschedulePlanStatus.DONE);
        plan.setClosedAt(LocalDateTime.now());
        plan.setClosedBy(operator);
        planRepo.save(plan);
        exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, plan.getMachine(),
                "机位 " + plan.getMachine().getCode() + " 故障改排方案闭环：" + actions.size()
                        + " 名患者均已完成改排并通知", operator);
        return detail(planId);
    }

    // ---------------------------------------------------------------- 详情与串联视图

    /**
     * 方案详情：受影响患者 + 备用机位建议 + 每条动作的串联状态
     * （患者通知 / 家属接送 / 机位消毒 / 耗材领用 / 到院补偿）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long planId) {
        ReschedulePlan plan = getPlan(planId);
        List<RescheduleAction> actions = actionRepo.findByPlanIdOrderById(planId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", plan);

        List<Map<String, Object>> actionViews = new ArrayList<>();
        for (RescheduleAction a : actions) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("action", a);
            view.put("chain", chain(a));
            if (a.getStatus() == RescheduleActionStatus.PENDING) {
                view.put("backupOptions", backupOptions(a, plan.getFaultDate()));
            }
            actionViews.add(view);
        }
        result.put("actions", actionViews);
        result.put("decidedCount", actions.stream().filter(a -> a.getStatus() == RescheduleActionStatus.DONE).count());
        result.put("notifiedCount", actions.stream().filter(a -> a.getNotifyStatus() == NotifyStatus.DONE).count());
        return result;
    }

    /** 单条动作的串联视图：通知、接送、机位消毒、耗材领用、到院补偿 */
    private Map<String, Object> chain(RescheduleAction a) {
        DialysisSession session = a.getSession();
        DialysisMachine current = session.getMachine();
        Map<String, Object> chain = new LinkedHashMap<String, Object>();

        // 患者通知 + 家属接送
        Map<String, Object> notify = new LinkedHashMap<String, Object>();
        notify.put("status", a.getNotifyStatus().name());
        notify.put("channel", a.getNotifyChannel());
        notify.put("note", a.getNotifyNote());
        notify.put("by", a.getNotifiedBy());
        notify.put("at", a.getNotifiedAt());
        notify.put("familyPickupNote", a.getFamilyPickupNote());
        notify.put("transportMode", session.getPatient().getTransportMode().name());
        notify.put("transportWindow", session.getPatient().getTransportWindow());
        notify.put("patientPhone", session.getPatient().getPhone());
        chain.put("notify", notify);

        // 机位消毒：当前机位状态 + 故障日消毒记录
        Map<String, Object> machine = new LinkedHashMap<String, Object>();
        machine.put("machineId", current.getId());
        machine.put("code", current.getCode());
        machine.put("zone", current.getZone().name());
        machine.put("status", current.getStatus().name());
        machine.put("lastDisinfectedAt", current.getLastDisinfectedAt());
        machine.put("disinfectBufferMin", current.getDisinfectBufferMin());
        chain.put("machine", machine);
        LocalDate day = session.getSessionDate();
        chain.put("disinfectionsToday", disinfectionRepo.findByMachineIdOrderByDoneAtDesc(current.getId())
                .stream().filter(r -> r.getDoneAt().toLocalDate().equals(day)).limit(5).toList());

        // 耗材领用流水（该治疗单）
        List<ConsumableTxn> txns = txnRepo.findBySessionId(session.getId());
        chain.put("consumables", txns.stream()
                .sorted(Comparator.comparing(ConsumableTxn::getCreatedAt).reversed())
                .limit(12).toList());

        // 到院补偿与交通调整
        Map<String, Object> arrival = new LinkedHashMap<String, Object>();
        arrival.put("patientArrived", a.getPatientArrived());
        arrival.put("compensationNote", a.getCompensationNote());
        arrival.put("transportAdjustNote", a.getTransportAdjustNote());
        arrival.put("frontDeskBy", a.getFrontDeskBy());
        arrival.put("frontDeskAt", a.getFrontDeskAt());
        chain.put("arrival", arrival);

        // 治疗单现状
        Map<String, Object> sessionNow = new LinkedHashMap<String, Object>();
        sessionNow.put("sessionId", session.getId());
        sessionNow.put("status", session.getStatus().name());
        sessionNow.put("shift", session.getShift().name());
        sessionNow.put("plannedStart", session.getPlannedStart());
        sessionNow.put("seqNo", session.getSeqNo());
        chain.put("session", sessionNow);
        return chain;
    }

    /** 备用机位建议：按患者感染分区过滤，给出同班次与下午班最早可上机时刻 */
    private List<Map<String, Object>> backupOptions(RescheduleAction action, LocalDate date) {
        List<MachineZone> zones = SchedulingService.compatibleZones(action.getInfectionMarker());
        DialysisSession session = action.getSession();
        return machineRepo.findByActiveTrueOrderByCode().stream()
                .filter(m -> zones.contains(m.getZone()))
                .filter(m -> m.getStatus() != MachineStatus.FAULT && m.getStatus() != MachineStatus.MAINTENANCE)
                .map(m -> {
                    Map<String, Object> o = new LinkedHashMap<String, Object>();
                    o.put("machineId", m.getId());
                    o.put("code", m.getCode());
                    o.put("zone", m.getZone().name());
                    o.put("status", m.getStatus().name());
                    o.put("sameShiftSlot", schedulingService.nextSlot(m, date, session.getShift(), List.of()));
                    o.put("pmSlot", schedulingService.nextSlot(m, date, Shift.PM, List.of()));
                    return o;
                })
                .sorted(Comparator.comparing(o -> (LocalDateTime) o.get("sameShiftSlot")))
                .collect(Collectors.toList());
    }

    /** 耗材重新串联：归还原预领并按新安排重新预领，返回归还条数 */
    private int relinkConsumables(DialysisSession session, String operator) {
        List<ConsumableTxn> preDrawn = txnRepo.findBySessionId(session.getId()).stream()
                .filter(t -> t.getType() == TxnType.PRE_DRAW).toList();
        for (ConsumableTxn t : preDrawn) {
            Consumable c = consumableRepo.findById(t.getConsumableId()).orElse(null);
            if (c != null) {
                consumableService.transact(c, TxnType.RETURN, -t.getQty(), session.getId(),
                        session.getPatient().getName(), operator, "故障改排归还原预领");
            }
        }
        consumableService.preDrawForSession(session, operator,
                "故障改排后重新预领（治疗单#" + session.getId() + "）");
        return preDrawn.size();
    }

    private void appendNote(DialysisSession session, String text) {
        session.setNote((session.getNote() == null ? "" : session.getNote() + " ") + text);
    }

    private ReschedulePlan getPlan(Long id) {
        return planRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "改排方案不存在"));
    }

    private RescheduleAction getAction(Long id) {
        return actionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "改排动作不存在"));
    }

    private void requireOpen(ReschedulePlan plan) {
        if (plan.getStatus() != ReschedulePlanStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "方案已关闭，不能再操作");
        }
    }
}
