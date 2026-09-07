package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 齐套预警中的单条物料匹配结果（D42）。
 */
@Data
public class KitShortageVO {

    private Long goodsId;

    /** 对应BOM明细id(方案先行回挂用) */
    private Long bomDetailId;

    private String goodsName;

    /** 规格/材质/备注快照（自 BOM 明细行，ADR-0003；补料明细快照与齐套展示共用） */
    private String spec;

    private String material;

    private String remark;

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