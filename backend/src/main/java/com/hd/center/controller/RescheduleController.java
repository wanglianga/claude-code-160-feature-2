package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.RescheduleRecord;
import com.hd.center.service.RescheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 机位故障改排：筛查受影响患者与备用机位，执行延后/换机/改班次/协商调换，
 * 并串联患者通知、家属接送、机位消毒、耗材领用。
 */
@RestController
@RequestMapping("/api/reschedule")
@RequiredArgsConstructor
public class RescheduleController {

    private final RescheduleService rescheduleService;

    /** 故障改排筛查：受影响患者（含传染病分区要求）+ 可用备用机位 */
    @GetMapping("/options")
    public Map<String, Object> options(@RequestParam Long machineId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return rescheduleService.options(machineId, date);
    }

    /** 执行改排 */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody RescheduleService.ApplyRequest request,
                                     @AuthenticationPrincipal CurrentUser current) {
        return rescheduleService.apply(request, current.name());
    }

    /** 改排台账 */
    @GetMapping("/records")
    public List<RescheduleRecord> records(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return rescheduleService.records(date);
    }

    /** 协商调换候选治疗单 */
    @GetMapping("/swap-candidates")
    public List<Map<String, Object>> swapCandidates(@RequestParam Long sessionId) {
        return rescheduleService.swapCandidates(sessionId);
    }

    /** 联动链：患者通知 / 家属接送 / 机位消毒 / 耗材领用（到院时含补偿） */
    @GetMapping("/{id}/chain")
    public Map<String, Object> chain(@PathVariable Long id) {
        return rescheduleService.chain(id);
    }
}
