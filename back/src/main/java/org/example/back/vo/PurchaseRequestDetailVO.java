package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseRequestDetailVO {

    private Long id;

    private Long requestId;

    private Long goodsId;

    /** 对应BOM明细id */
    private Long bomDetailId;

    private String goodsName;

    private Integer quantity;

    private Integer arriveQuantity;

    private BigDecimal unitPrice;

    private Integer sortNo;
}
