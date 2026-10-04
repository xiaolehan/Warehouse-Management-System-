package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * D116（ADR-0020）：成品拆分单明细行 VO（BOM×拆分数量快照）。
 */
@Data
public class SplitOrderDetailVO {

    private Long id;

    private Long splitOrderId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    /** 需求量(BOM×拆分数量) */
    private Integer requiredQuantity;

    private Integer sortNo;

    private LocalDateTime createTime;
}
