package com.hd.center.entity;

import com.hd.center.entity.Enums.NotifyChannel;
import com.hd.center.entity.Enums.RescheduleAction;
import com.hd.center.entity.Enums.Shift;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 机位故障改排记录：一次故障改排的完整台账。
 * 串起患者通知、家属接送、到院补偿、机位消毒与耗材领用，协商调换时记录双方确认。
 */
@Data
@Entity
@Table(name = "reschedule_record", indexes = {
        @Index(name = "idx_resched_date", columnList = "sessionDate"),
        @Index(name = "idx_resched_session", columnList = "sessionId")
})
public class RescheduleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被改排的治疗单 */
    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long patientId;

    @Column(nullable = false, length = 64)
    private String patientName;

    /** 故障机位 */
    private Long faultMachineId;

    @Column(length = 16)
    private String faultMachineCode;

    @Column(nullable = false)
    private LocalDate sessionDate;

    // ---- 改排前 ----
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Shift fromShift;

    @Column(length = 16)
    private String fromMachineCode;

    private LocalDateTime fromPlannedStart;

    // ---- 改排方式与结果 ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RescheduleAction action;

    private Long toMachineId;

    @Column(length = 16)
    private String toMachineCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Shift toShift;

    private LocalDateTime toPlannedStart;

    // ---- 患者已到院：前台记录等候补偿与交通调整 ----
    @Column(nullable = false)
    private Boolean arrived = false;

    /** 等候补偿，如「等候 40 分钟，补偿交通费 20 元」 */
    @Column(length = 255)
    private String compensation;

    /** 交通调整，如「班车改约 15:00 接回」 */
    @Column(length = 255)
    private String transportAdjust;

    // ---- 患者通知与家属接送 ----
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private NotifyChannel notifyChannel;

    @Column(length = 500)
    private String notifyNote;

    /** 家属/班车接送已通知 */
    @Column(nullable = false)
    private Boolean familyNotified = false;

    // ---- 协商调换（双方确认，防止口头换班争议） ----
    private Long swapSessionId;

    @Column(length = 64)
    private String swapPatientName;

    /** 本患者确认人 */
    @Column(length = 64)
    private String confirmA;

    /** 对方患者确认人 */
    @Column(length = 64)
    private String confirmB;

    @Column(length = 64)
    private String operator;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
