package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.DialysisMachine;
import com.hd.center.entity.DialysisSession;
import com.hd.center.entity.Enums.ExceptionType;
import com.hd.center.entity.Enums.MachineStatus;
import com.hd.center.entity.Enums.SessionStatus;
import com.hd.center.repository.MachineRepository;
import com.hd.center.repository.SessionRepository;
import com.hd.center.service.ExceptionService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/machines")
@RequiredArgsConstructor
public class MachineController {

    private final MachineRepository machineRepo;
    private final SessionRepository sessionRepo;
    private final ExceptionService exceptionService;

    @GetMapping
    public List<DialysisMachine> list() {
        return machineRepo.findAll();
    }

    @PostMapping
    public DialysisMachine create(@RequestBody DialysisMachine machine) {
        machine.setId(null);
        return machineRepo.save(machine);
    }

    @PutMapping("/{id}")
    public DialysisMachine update(@PathVariable Long id, @RequestBody DialysisMachine body) {
        DialysisMachine machine = get(id);
        machine.setCode(body.getCode());
        machine.setModel(body.getModel());
        machine.setZone(body.getZone());
        machine.setDisinfectBufferMin(body.getDisinfectBufferMin());
        machine.setActive(body.getActive());
        return machineRepo.save(machine);
    }

    public record StatusRequest(@NotNull MachineStatus status, String note) {}

    /**
     * 变更机器状态。标记故障时自动上报异常并列出今日受影响治疗单。
     */
    @PostMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable Long id, @RequestBody StatusRequest request,
                                            @AuthenticationPrincipal CurrentUser current) {
        DialysisMachine machine = get(id);
        machine.setStatus(request.status());
        machineRepo.save(machine);

        List<DialysisSession> affected = List.of();
        if (request.status() == MachineStatus.FAULT || request.status() == MachineStatus.MAINTENANCE) {
            affected = sessionRepo.findBySessionDateAndMachineIdAndStatusNotIn(
                    LocalDate.now(), id,
                    List.of(SessionStatus.CANCELLED, SessionStatus.ABSENT, SessionStatus.FINISHED, SessionStatus.OFF_DIALYSIS));
            String affectedNames = affected.stream()
                    .map(s -> s.getPatient().getName() + "(" + s.getShift() + ")")
                    .collect(Collectors.joining("、"));
            exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, machine,
                    "机位 " + machine.getCode() + " 变更为 " + request.status()
                            + (request.note() != null ? "：" + request.note() : "")
                            + (affected.isEmpty() ? "" : "。今日受影响患者：" + affectedNames + "，请安排调班"),
                    current.name());
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("machine", machine);
        result.put("affectedSessions", affected);
        if (request.status() == MachineStatus.FAULT) {
            result.put("rescheduleTip", "可前往「故障改排」页创建改排方案，统一决策受影响患者并联动通知、耗材与消毒");
        }
        return result;
    }

    private DialysisMachine get(Long id) {
        return machineRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));
    }
}
