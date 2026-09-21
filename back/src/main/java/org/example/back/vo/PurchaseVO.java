package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 进货单 VO（D111 头行结构）：头汇总 + 明细行列表。
 */
@Data
public class PurchaseVO {

    private Long id;

    private String purchaseNo;

    private String orderNo;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    /** 全部行同价时为该单价，多价时为 null（列表展示用） */
    private BigDecimal avgPrice;

    /** 商品汇总描述：单行 "SMC105"，多行 "SMC105 等 3 种" */
    private String goodsSummary;

    /** 供应商汇总：全部行同一供应商为其名称，跨供应商为 "多个供应商" */
    private String supplierSummary;

    private List<PurchaseDetailVO> details;

    private LocalDateTime operationTime;

    private LocalDateTime purchaseDate;

    private String operatorName;

    private String operator;

    private String remark;

    private Integer bizStatus;

    private Integer confirmStatus;

    private String confirmStatusText;

    private LocalDateTime arriveTime;

    private String confirmerName;

    private LocalDateTime confirmTime;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private Integer isDeleted;

    private Integer approvalStatus;

    private String approvalRequestAction;
}
