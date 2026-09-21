package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退货来源进货单选项的明细行（D111）：行级可退量 = 行入库量 - 该行被有效退货（正常+已出库）累计。
 */
@Data
public class PurchaseSourceOptionLineVO {

    /** 来源进货明细行 id（biz_purchase_detail.id） */
    private Long purchaseDetailId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    /** 原行入库数量 */
    private Integer quantity;

    /** 原行单价 */
    private BigDecimal unitPrice;

    /** 该行被有效退货累计 */
    private Integer returnedQuantity;

    /** 可退数量 = quantity - returnedQuantity */
    private Integer returnableQuantity;
}
