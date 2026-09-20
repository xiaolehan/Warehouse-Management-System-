package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 销售单明细行 VO（D110）。
 */
@Data
public class SalesDetailVO {

    private Long id;

    private Long salesId;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private Integer sortNo;

    /** D110：当前库存快照（列表批量填充，仓储确认页据此标缺货行） */
    private Integer stock;

    /** 行级缺货标识：库存 < 行数量 */
    private Boolean shortage;
}
