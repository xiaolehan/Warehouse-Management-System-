package org.example.back.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * D121：销售端「+新品」快速建品返回——字段对齐 GoodsOptionVO（name/stock/unit/spec/salePrice/type/hasBom）
 * 便于前端直接并入成品下拉选项；existing=true 表示同名成品已存在、本次直接选用未新建（成品名唯一铁律）。
 */
@Data
@AllArgsConstructor
public class QuickProductVO {

    private Long id;

    private String name;

    private Integer stock;

    private String unit;

    private String spec;

    private BigDecimal salePrice;

    /** 恒 product（服务端强制） */
    private String type;

    /** D112 口径：是否已建档有效 BOM */
    private Boolean hasBom;

    /** true=同名成品已存在、直接选用 */
    private Boolean existing;
}