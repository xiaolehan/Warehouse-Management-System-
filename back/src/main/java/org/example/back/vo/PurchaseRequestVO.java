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

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;

    /**
     * 明细列表
     */
    private List<PurchaseRequestDetailVO> details;
}
