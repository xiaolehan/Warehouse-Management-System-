package org.example.back.vo;

import lombok.Data;

@Data
public class StocktakeDetailVO {

    private Long id;

    private Long goodsId;

    private String goodsCode;

    private String goodsName;

    private String spec;

    private String material;

    private String unit;

    private String goodsType;

    /**
     * 建单账面快照（展示参考——D82）
     */
    private Integer bookQty;

    /**
     * 负责人（D85：建单逐行指定，员工限录本人行）
     */
    private Long assigneeId;

    private String assigneeName;

    /**
     * 实盘数(null=未盘)
     */
    private Integer actualQty;

    /**
     * 实际录入人（D85：可能与负责人不同——如 admin 代录）
     */
    private String counterName;

    /**
     * 录入时间
     */
    private java.time.LocalDateTime countTime;

    /**
     * 生效时账面（审核后可见）
     */
    private Integer finalBookQty;

    /**
     * 差异=实盘-生效时账面（审核后可见）
     */
    private Integer diffQty;

    /**
     * 是否未盘行（提交/审核时 actual_qty 仍为空）
     */
    private Boolean unscanned;
}
