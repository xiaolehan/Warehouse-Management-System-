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

    /** D131 行级供应商ID（手动单=头级统一值；申请单=各行选定值；存量行可空） */
    private Long supplierId;

    /** D131 行级供应商名（批量填充） */
    private String supplierName;

    private Integer sortNo;
}
