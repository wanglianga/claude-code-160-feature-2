package com.hd.center.entity;

import com.hd.center.entity.Enums.InfectionMarker;
import com.hd.center.entity.Enums.NotifyStatus;
import com.hd.center.entity.Enums.RescheduleActionStatus;
import com.hd.center.entity.Enums.RescheduleActionType;
import com.hd.center.entity.Enums.Shift;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 改排动作：方案下每条受影响治疗单的处置记录。
 * 串联患者通知、家属接送、等候补偿、交通调整、耗材重领与协商调换双方确认。
 */
@Data
@Entity
@Table(name = "reschedule_action", indexes = @Index(name = "idx_ra_plan", columnList = "plan_id"))
public class RescheduleAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private ReschedulePlan plan;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id")
    private DialysisSession session;

    @Column(nullable = false, length = 64)
    private String patientName;

    /** 患者感染标记快照（决策时的分区依据） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InfectionMarker infectionMarker = InfectionMarker.NONE;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RescheduleActionType actionType;

    // ---- 决策参数 ----

    private Long targetMachineId;

    @Column(length = 16)
    private String targetMachineCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Shift targetShift;

    /** 延后分钟数（DELAY 时必填） */
    private Integer delayMinutes;

    // ---- 协商调换（SWAP）：双方确认留痕，防止口头换班争议 ----

    private Long swapSessionId;

    @Column(length = 64)
    private String swapPatientName;

    /** 本患者侧确认人（患者本人/家属） */
    @Column(length = 64)
    private String swapConfirmByA;

    /** 对方患者侧确认人 */
    @Column(length = 64)
    private String swapConfirmByB;

    @Column(length = 500)
    private String swapConfirmNote;

    // ---- 患者通知 ----

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotifyStatus notifyStatus = NotifyStatus.PENDING;

    /** 通知渠道：电话 / 短信 / 家属转告 */
    @Column(length = 16)
    private String notifyChannel;

    @Column(length = 500)
    private String notifyNote;

    @Column(length = 64)
    private String notifiedBy;

    private LocalDateTime notifiedAt;

    /** 家属接送安排 */
    @Column(length = 500)
    private String familyPickupNote;

    // ---- 患者已到院：前台登记 ----

    @Column(nullable = false)
    private Boolean patientArrived = false;

    /** 等候补偿（餐券/交通补贴等） */
    @Column(length = 500)
    private String compensationNote;

    /** 交通调整（班车改点/家属改送等） */
    @Column(length = 500)
    private String transportAdjustNote;

    @Column(length = 64)
    private String frontDeskBy;

    private LocalDateTime frontDeskAt;

    // ---- 处理状态 ----

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RescheduleActionStatus status = RescheduleActionStatus.PENDING;

    @Column(length = 64)
    private String decidedBy;

    private LocalDateTime decidedAt;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
