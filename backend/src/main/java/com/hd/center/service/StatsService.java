package com.hd.center.service;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.*;
import com.hd.center.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理端统计：机位利用率、异常率、耗材损耗、消毒合规。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

    private final SessionRepository sessionRepo;
    private final MachineRepository machineRepo;
    private final ExceptionEventRepository exceptionRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableRepository consumableRepo;
    private final DisinfectionRecordRepository disinfectionRepo;
    private final ReschedulePlanRepository reschedulePlanRepo;

    /** 今日工作台总览 */
    public Map<String, Object> dashboard() {
        LocalDate today = LocalDate.now();
        Map<String, Object> result = new LinkedHashMap<>();

        List<DialysisSession> todaySessions = sessionRepo.findBySessionDateOrderByShiftAscSeqNoAsc(today);
        Map<String, Long> byStatus = todaySessions.stream()
                .collect(Collectors.groupingBy(s -> s.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("todaySessionTotal", todaySessions.size());
        result.put("todayByStatus", byStatus);

        List<DialysisMachine> machines = machineRepo.findByActiveTrueOrderByCode();
        Map<String, Long> machineByStatus = machines.stream()
                .collect(Collectors.groupingBy(m -> m.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("machineTotal", machines.size());
        result.put("machineByStatus", machineByStatus);

        List<ExceptionEvent> open = exceptionRepo.findByStatusOrderByCreatedAtDesc(ExceptionStatus.OPEN);
        List<ExceptionEvent> processing = exceptionRepo.findByStatusOrderByCreatedAtDesc(ExceptionStatus.PROCESSING);
        result.put("openExceptionCount", open.size());
        result.put("processingExceptionCount", processing.size());
        List<ExceptionEvent> latest = new ArrayList<>();
        latest.addAll(open);
        latest.addAll(processing);
        latest.sort(Comparator.comparing(ExceptionEvent::getCreatedAt).reversed());
        result.put("latestExceptions", latest.stream().limit(8).toList());

        List<Map<String, Object>> lowStock = consumableRepo.findAllByOrderByCode().stream()
                .filter(c -> c.getStock() <= c.getSafeStock())
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", c.getName());
                    m.put("stock", c.getStock());
                    m.put("safeStock", c.getSafeStock());
                    m.put("unit", c.getUnit());
                    return m;
                }).toList();
        result.put("lowStock", lowStock);

        // 进行中的机位故障改排方案
        result.put("openReschedulePlans",
                reschedulePlanRepo.countByStatus(com.hd.center.entity.Enums.ReschedulePlanStatus.OPEN));
        return result;
    }

    /** 区间统计 */
    public Map<String, Object> overview(LocalDate from, LocalDate to) {
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.atTime(LocalTime.MAX);

        List<DialysisSession> sessions = sessionRepo.findBySessionDateBetween(from, to).stream()
                .filter(s -> s.getStatus() != SessionStatus.CANCELLED)
                .toList();
        List<DialysisMachine> machines = machineRepo.findByActiveTrueOrderByCode();

        // ---- 机位利用率：已排时长 / (机位 * 天数 * 每日两班 * 300分钟) ----
        long days = Math.max(1, to.toEpochDay() - from.toEpochDay() + 1);
        double capacityPerMachine = days * 2 * 300.0;
        List<Map<String, Object>> utilization = new ArrayList<>();
        double totalUsed = 0;
        for (DialysisMachine m : machines) {
            int used = sessions.stream()
                    .filter(s -> s.getMachine().getId().equals(m.getId()) && s.getStatus() != SessionStatus.ABSENT)
                    .mapToInt(DialysisSession::getPlannedDurationMin).sum();
            totalUsed += used;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("machineCode", m.getCode());
            row.put("zone", m.getZone().name());
            row.put("usedMinutes", used);
            row.put("utilization", Math.round(used / capacityPerMachine * 1000.0) / 10.0);
            utilization.add(row);
        }
        result.put("utilization", utilization);
        result.put("overallUtilization",
                machines.isEmpty() ? 0 : Math.round(totalUsed / (capacityPerMachine * machines.size()) * 1000.0) / 10.0);

        // ---- 异常统计 ----
        List<ExceptionEvent> exceptions = exceptionRepo.findByCreatedAtBetween(fromTs, toTs);
        Map<String, Long> exceptionsByType = exceptions.stream()
                .collect(Collectors.groupingBy(e -> e.getType().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("exceptionTotal", exceptions.size());
        result.put("exceptionsByType", exceptionsByType);
        result.put("exceptionRate",
                sessions.isEmpty() ? 0 : Math.round(exceptions.size() * 1000.0 / sessions.size()) / 10.0);
        result.put("sessionTotal", sessions.size());
        Map<String, Long> sessionsByStatus = sessions.stream()
                .collect(Collectors.groupingBy(s -> s.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("sessionsByStatus", sessionsByStatus);

        // ---- 耗材损耗 ----
        List<ConsumableTxn> txns = txnRepo.findByCreatedAtBetween(fromTs, toTs);
        Map<String, Integer> waste = new LinkedHashMap<>();
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (ConsumableTxn t : txns) {
            if (t.getType() == TxnType.WASTE) {
                waste.merge(t.getConsumableName(), -t.getQty(), Integer::sum);
            } else if (t.getType() == TxnType.USE || t.getType() == TxnType.PRE_DRAW) {
                usage.merge(t.getConsumableName(), -t.getQty(), Integer::sum);
            }
        }
        result.put("consumableWaste", waste);
        result.put("consumableUsage", usage);
        int wasteTotal = waste.values().stream().mapToInt(Integer::intValue).sum();
        int usageTotal = usage.values().stream().mapToInt(Integer::intValue).sum();
        result.put("wasteRate", usageTotal == 0 ? 0 : Math.round(wasteTotal * 1000.0 / usageTotal) / 10.0);

        // ---- 消毒合规：每日有消毒记录的机位占比 ----
        List<DisinfectionRecord> records = disinfectionRepo.findByDoneAtBetween(fromTs, toTs);
        Map<String, Long> byType = records.stream()
                .collect(Collectors.groupingBy(r -> r.getType().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("disinfectionByType", byType);
        Set<String> machineDayCovered = records.stream()
                .filter(r -> r.getType() == DisinfectionType.DAILY || r.getType() == DisinfectionType.HEAT
                        || r.getType() == DisinfectionType.CITRIC_ACID)
                .map(r -> r.getMachineId() + "@" + r.getDoneAt().toLocalDate())
                .collect(Collectors.toSet());
        double compliance = machines.isEmpty() ? 0
                : machineDayCovered.size() * 100.0 / (machines.size() * days);
        result.put("disinfectionCompliance", Math.round(Math.min(100.0, compliance) * 10.0) / 10.0);
        result.put("disinfectionRecordTotal", records.size());
        return result;
    }
}
