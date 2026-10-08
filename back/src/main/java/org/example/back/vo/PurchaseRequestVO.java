package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseRequestVO {

    private Long id;

    private String requestNo;

    /**
     * 状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回, 5-待入库确认
     */
    private Integer status;

    /**
     * 状态文本
     */
    private String statusText;

    /**
     * 来源: production-生产缺料补料, warehouse-仓储手动
     */
    private String sourceType;

    /**
     * 来源生产任务单id(仅production来源有值)
     */
    private Long productionOrderId;

    /** 会话 58：关联任务单被销售取消冻结（前端禁用认领/到货提交按钮用） */
    private Boolean salesFrozen;

    /** 会话 58：冻结原因作为短文案 */
    private String salesFrozenReason;

    /** D114：销售冻结豁免（生产终止未撤销在途补料申请→1，采购可继续认领/到货） */
    private Integer freezeExempt;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime arriveTime;

    private LocalDateTime receiveTime;

    private Long confirmerId;

    private String confirmerName;

    private LocalDateTime confirmTime;

    private String rejectReason;

    /**
     * 会话 67：撤销留痕（status=6「已撤销」时有值）
     */
    private String revokeReason;

    private Long revokerId;

    private String revokerName;

    private LocalDateTime revokeTime;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;

    /**
     * 明细列表
     */
    private List<PurchaseRequestDetailVO> details;
}
