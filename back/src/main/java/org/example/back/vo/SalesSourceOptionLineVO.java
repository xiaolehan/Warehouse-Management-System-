package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退货来源销售单选项的明细行（D110）：行级可退量 = 原行量 - 该行被有效退货（正常+已确认入库）累计。
 */
@Data
public class SalesSourceOptionLineVO {

    /** 来源销售明细行 id（biz_sales_detail.id） */
    private Long salesDetailId;

    private Long goodsId;

    private String goodsName;

    /** 原行数量 */
    private Integer quantity;

    /** 原行单价 */
    private BigDecimal unitPrice;

    /** 该行被有效退货累计 */
    private Integer returnedQuantity;

    /** 可退数量 = quantity - returnedQuantity */
    private Integer returnableQuantity;
}
