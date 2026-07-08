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

    private BigDecimal salePrice;
}
