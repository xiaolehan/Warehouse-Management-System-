package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 进货退货单明细行 VO（D111）。
 */
@Data
public class PurchaseReturnDetailVO {

    private Long id;

    private Long returnId;

    private Long sourcePurchaseId;

    private Long sourceDetailId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private Integer sortNo;
}
