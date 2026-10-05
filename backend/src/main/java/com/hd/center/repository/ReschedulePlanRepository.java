package com.hd.center.repository;

import com.hd.center.entity.Enums.ReschedulePlanStatus;
import com.hd.center.entity.ReschedulePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReschedulePlanRepository extends JpaRepository<ReschedulePlan, Long> {

    List<ReschedulePlan> findByFaultDateOrderByCreatedAtDesc(LocalDate date);

    List<ReschedulePlan> findByStatusOrderByCreatedAtDesc(ReschedulePlanStatus status);

    List<ReschedulePlan> findTop20ByOrderByCreatedAtDesc();

    long countByStatus(ReschedulePlanStatus status);

    boolean existsByMachineIdAndFaultDateAndStatus(Long machineId, LocalDate faultDate, ReschedulePlanStatus status);

    Optional<ReschedulePlan> findByMachineIdAndFaultDateAndStatus(Long machineId, LocalDate faultDate, ReschedulePlanStatus status);
}
