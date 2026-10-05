package com.hd.center.entity;

import com.hd.center.entity.Enums.ReschedulePlanStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 机位故障改排方案：一次机位自检失败/故障事件对应一个方案。
 * 方案下按受影响治疗单逐条决策（延后/换机/改下午班/协商调换）。
 */
@Data
@Entity
@Table(name = "reschedule_plan")
public class ReschedulePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "machine_id")
    private DialysisMachine machine;

    /** 故障发生日期（影响筛查范围=当天该机位未上机的治疗单） */
    @Column(nullable = false)
    private LocalDate faultDate;

    /** 故障原因，如：班前自检失败-电导度异常 */
    @Column(nullable = false, length = 500)
    private String reason;

    /** 预计修复时间：评估“延后”与“协商调换”是否可行的依据 */
    private LocalDateTime expectedRepairAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReschedulePlanStatus status = ReschedulePlanStatus.OPEN;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;

    @Column(length = 64)
    private String closedBy;

    @Column(length = 500)
    private String note;
}
