package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 进货单明细行 VO（D111）。
 */
@Data
public class PurchaseDetailVO {

    private Long id;

    private Long purchaseId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private Integer sortNo;
}
