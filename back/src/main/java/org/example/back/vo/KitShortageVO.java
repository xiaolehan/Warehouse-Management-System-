package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 齐套预警中的单条物料匹配结果（D42）。
 */
@Data
public class KitShortageVO {

    private Long goodsId;

    private String goodsName;

    private String unit;

    /** 单台用量 */
    private BigDecimal unitUsage;

    /** 本单需求总量 = 单台用量 × 生产数量 */
    private BigDecimal required;

    /** 当前库存 */
    private Integer stock;

    /** 缺口 = 需求 - 库存（<0 或 0 说明够） */
    private BigDecimal deficit;

    /** 匹配等级: ok-够, partial-部分缺, block-严重缺 */
    private String lineStatus;

    private String lineStatusText;
}