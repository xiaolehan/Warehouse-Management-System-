package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_stocktake_detail")
public class BizStocktakeDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long stocktakeId;

    private Long goodsId;

    /**
     * 商品编码（建单快照，盲盘导出/xlsx 回填匹配键）
     */
    private String goodsCode;

    /**
     * 建单快照（对齐 D60/D63 范式）
     */
    private String goodsName;

    private String spec;

    private String material;

    private String unit;

    /**
     * 商品类型快照: material-物料, product-成品
     */
    private String goodsType;

    /**
     * 建单账面快照（展示参考，非差异基准——D82）
     */
    private Integer bookQty;

    /**
     * 负责人（D85：建单逐行指定，员工限录本人行，admin 兜底可录任意行；盘点中可改派）
     */
    private Long assigneeId;

    private String assigneeName;

    /**
     * 实盘数(NULL=未盘)
     */
    private Integer actualQty;

    /**
     * 实际录入人（D85：页面录入/xlsx 导入回填均盖章，可能与负责人不同——如 admin 代录）
     */
    private Long counterId;

    private String counterName;

    private LocalDateTime countTime;

    /**
     * 生效时账面（审核时写，差异基准——D82）
     */
    private Integer finalBookQty;

    /**
     * 差异=实盘-生效时账面（审核时写）
     */
    private Integer diffQty;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
