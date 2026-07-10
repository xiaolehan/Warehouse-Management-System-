package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SalesVO {

    private Long id;

    private String salesNo;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal salesPrice;

    private BigDecimal totalPrice;

    private BigDecimal totalAmount;

    private LocalDateTime operationTime;

    private LocalDateTime salesDate;

    private String operatorName;

    private String operator;

    private String remark;

    private Integer bizStatus;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    /**
     * 仓库确认状态: 1-待仓库确认, 2-已确认出库
     */
    private Integer confirmStatus;

    private String confirmStatusText;

    private LocalDateTime confirmTime;

    private String confirmerName;

    private String customerName;

    private String contractNo;

    private LocalDateTime createTime;

    private Integer isDeleted;

    private Integer approvalStatus;

    private String approvalRequestAction;

    /** 最近审批单的备注（超管通过/驳回时填写，用于销售人员查看驳回原因）。 */
    private String approvalRemark;
}
