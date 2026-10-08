package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PickListVO {

    private Long id;

    private String pickNo;

    /**
     * 领料类型: PICK-领料, SUPPLY-补料, RETURN-退料
     */
    private String pickType;

    /**
     * 类型文本
     */
    private String pickTypeText;

    private Long sourceSalesId;

    /**
     * 关联生产任务单ID（生产来源单据有值）
     */
    private Long productionOrderId;

    /**
     * 关联生产任务单状态（1-待生产…7-已终止；终止退料单前端据此隐藏驳回/撤销）
     */
    private Integer productionOrderStatus;

    /**
     * 关联成品拆分单ID（拆分退料 RETURN 单专用，会话 67：前端据此打「拆分退料」标记）
     */
    private Long splitOrderId;

    /**
     * 状态: 1-待发料/待收料, 2-已发料/已收料, 3-已完成, 4-已驳回（RETURN 类型按收料口径显示）
     */
    private Integer status;

    /**
     * 状态文本
     */
    private String statusText;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime confirmTime;

    private String rejectReason;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;

    /**
     * 明细列表
     */
    private List<PickListDetailVO> details;
}
