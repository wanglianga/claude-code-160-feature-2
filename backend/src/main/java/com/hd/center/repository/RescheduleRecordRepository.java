package com.hd.center.repository;

import com.hd.center.entity.RescheduleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RescheduleRecordRepository extends JpaRepository<RescheduleRecord, Long> {

    List<RescheduleRecord> findBySessionDateOrderByCreatedAtDesc(LocalDate date);

    List<RescheduleRecord> findBySessionIdOrderByCreatedAtDesc(Long sessionId);
}
