package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点建单勾选列表项（D81：带「上次盘点时间」派生，最久未盘排最前）
 */
@Data
public class StocktakeGoodsOptionVO {

    private Long goodsId;

    private String goodsCode;

    private String goodsName;

    private String type;

    private String spec;

    private String material;

    private String unit;

    private Integer stock;

    /**
     * 上次盘点时间（最近审核生效行的生效时间；null=从未盘点）
     */
    private LocalDateTime lastStocktakeTime;

    /**
     * 是否在未完结盘点单中（建单排他守卫——同一商品同一时间只允许在一张盘点中/待审核单）
     */
    private Boolean inOpenStocktake;
}
