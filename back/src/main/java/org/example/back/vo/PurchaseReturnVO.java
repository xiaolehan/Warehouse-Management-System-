package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 进货退货单 VO（D111 头行结构）：头汇总 + 明细行列表。
 */
@Data
public class PurchaseReturnVO {

    private Long id;

    private String returnNo;

    private Long sourcePurchaseId;

    private String sourcePurchaseNo;

    /** D123：来源进货单头级供应商名称（仅展示，随来源单带出；存量来源单无则为 null） */
    private String supplierName;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    /** 商品汇总描述：单行 "SMC105"，多行 "SMC105 等 3 种" */
    private String goodsSummary;

    private List<PurchaseReturnDetailVO> details;

    private LocalDateTime operationTime;

    private LocalDateTime returnDate;

    private String operatorName;

    private String operator;

    private String remark;

    private Integer bizStatus;

    private Integer confirmStatus;

    private String confirmStatusText;

    private String confirmerName;

    private LocalDateTime confirmTime;

    private String completerName;

    private LocalDateTime completeTime;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private Integer isDeleted;

    private Integer approvalStatus;

    private String approvalRequestAction;
}
