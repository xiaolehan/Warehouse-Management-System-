package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    /** 行终止状态: 1-正常, 2-已终止（需求一 Q21） */
    private Integer terminateStatus;
    /** 行终止原因（必填留痕） */
    private String terminateReason;
    /** 行终止时间 */
    private LocalDateTime terminateTime;
    /** 行终止操作人姓名 */
    private String terminatorName;

    /** D110：当前库存快照（列表批量填充，仓储确认页据此标缺货行） */
    private Integer stock;
    /** D112：行成品当前库存为 0（零库存橙标，不拦下单） */
    private Boolean zeroStock;

    /** 行级缺货标识：库存 < 行数量 */
    private Boolean shortage;
    /** D112：行成品是否已建档有效 BOM（false=无 BOM 红标，不拦下单；生产端批量下达将跳过并给建档指引） */
    private Boolean hasBom;
}
