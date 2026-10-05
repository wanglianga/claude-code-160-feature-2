package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.Enums.RescheduleActionType;
import com.hd.center.entity.Enums.ReschedulePlanStatus;
import com.hd.center.service.RescheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 机位故障改排：自检失败 -> 影响筛查 -> 护士长决策 -> 通知/补偿/调换 -> 闭环串联。
 */
@RestController
@RequestMapping("/api/reschedule")
@RequiredArgsConstructor
public class RescheduleController {

    private final RescheduleService rescheduleService;

    public record CreatePlanRequest(Long machineId,
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    String reason,
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expectedRepairAt) {}

    /** 故障申报：机位自检失败，创建改排方案并筛出当天受影响患者 */
    @PostMapping("/plans")
    public Map<String, Object> createPlan(@RequestBody CreatePlanRequest request,
                                          @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.createPlan(request.machineId(), request.date(), request.reason(),
                request.expectedRepairAt(), current.name());
    }

    /** 方案列表（默认最近 20 条，可按日期/状态过滤） */
    @GetMapping("/plans")
    public List<Map<String, Object>> plans(@RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) ReschedulePlanStatus status) {
        return rescheduleService.listPlans(date, status);
    }

    /** 方案详情：受影响患者 + 备用机位建议 + 通知/接送/消毒/耗材/补偿串联视图 */
    @GetMapping("/plans/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return rescheduleService.detail(id);
    }

    public record RepairTimeRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expectedRepairAt) {}

    /** 登记/更新预计修复时间 */
    @PostMapping("/plans/{id}/repair-time")
    public Map<String, Object> repairTime(@PathVariable Long id, @RequestBody RepairTimeRequest request,
                                          @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.updateRepairTime(id, request.expectedRepairAt(), current.name());
    }

    public record DecideRequest(RescheduleActionType actionType, Long targetMachineId,
                                Integer delayMinutes, String note) {}

    /** 护士长决策：延后 / 换机 / 改到下午班（联动耗材与消毒计划） */
    @PostMapping("/actions/{id}/decide")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody DecideRequest request,
                                      @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.decide(id, request.actionType(), request.targetMachineId(),
                request.delayMinutes(), request.note(), current.name());
    }

    public record SwapRequest(Long otherSessionId, String confirmByA, String confirmByB, String confirmNote) {}

    /** 协商调换：与其他患者互换机位/时段，记录双方确认 */
    @PostMapping("/actions/{id}/swap")
    public Map<String, Object> swap(@PathVariable Long id, @RequestBody SwapRequest request,
                                    @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.swap(id, request.otherSessionId(), request.confirmByA(),
                request.confirmByB(), request.confirmNote(), current.name());
    }

    /** 调换候选列表（含双向分区匹配与修复时间冲突标记） */
    @GetMapping("/actions/{id}/swap-candidates")
    public List<Map<String, Object>> swapCandidates(@PathVariable Long id) {
        return rescheduleService.swapCandidates(id);
    }

    public record NotifyRequest(String channel, String note, String familyPickupNote) {}

    /** 登记患者通知与家属接送安排 */
    @PostMapping("/actions/{id}/notify")
    public Map<String, Object> notify(@PathVariable Long id, @RequestBody NotifyRequest request,
                                      @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.notify(id, request.channel(), request.note(),
                request.familyPickupNote(), current.name());
    }

    public record ArrivalRequest(String compensationNote, String transportAdjustNote) {}

    /** 前台登记：患者已到院的等候补偿与交通调整 */
    @PostMapping("/actions/{id}/arrival")
    public Map<String, Object> arrival(@PathVariable Long id, @RequestBody ArrivalRequest request,
                                       @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.arrival(id, request.compensationNote(),
                request.transportAdjustNote(), current.name());
    }

    /** 关闭方案：全部患者完成改排并通知后闭环 */
    @PostMapping("/plans/{id}/close")
    public Map<String, Object> close(@PathVariable Long id, @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.close(id, current.name());
    }
}
