package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售退货 VO（D110 头行结构）：头汇总 + 明细行列表。
 */
@Data
public class SalesReturnVO {

    private Long id;

    private String returnNo;

    private Long sourceSalesId;

    private String sourceSalesNo;

    private String orderNo;

    private String customerName;

    /** D128 客户联系人姓名快照 */
    private String customerContactName;

    /** D128 客户手机号快照 */
    private String customerPhone;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    /** 商品汇总描述：单行 "PTO153"，多行 "PTO153 等 3 种" */
    private String goodsSummary;

    private List<SalesReturnDetailVO> details;

    private LocalDateTime operationTime;

    private LocalDateTime returnDate;

    private String operatorName;

    private String operator;

    private String remark;

    private String reason;

    private Integer bizStatus;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private Integer confirmStatus;

    private String confirmStatusText;

    private LocalDateTime confirmTime;

    private String confirmerName;

    private LocalDateTime createTime;

    private Integer isDeleted;

    private Integer approvalStatus;

    private String approvalRequestAction;
}
