package com.hd.center.repository;

import com.hd.center.entity.RescheduleAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RescheduleActionRepository extends JpaRepository<RescheduleAction, Long> {

    List<RescheduleAction> findByPlanIdOrderById(Long planId);
}
