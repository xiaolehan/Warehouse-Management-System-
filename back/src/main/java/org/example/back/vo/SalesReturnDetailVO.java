package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 销售退货明细行 VO（D110）。
 */
@Data
public class SalesReturnDetailVO {

    private Long id;

    private Long returnId;

    private Long sourceSalesDetailId;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private Integer sortNo;
}
