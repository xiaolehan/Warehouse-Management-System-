package org.example.back.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品下拉选项 VO（带库存与单位）。
 * 用于销售/领料等出库类单据选商品时展示「可售数量」，区别于通用 {@link OptionVO}（仅 id+name，被部门/供应商选项复用）。
 * salePrice 为标准售价，供销售建单时比对价格偏离。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GoodsOptionVO {

    private Long id;

    private String name;

    private Integer stock;

    private String unit;

    /** 规格/材质（ADR-0003：下拉带规格防同名混选） */
    private String spec;

    private String material;

    private BigDecimal salePrice;

    /** D111：物料最近进价参考（进货多行编辑器选物料时带出/预填） */
    private BigDecimal purchasePrice;

    /** D41 货品类型: material/product */
    private String type;
}
