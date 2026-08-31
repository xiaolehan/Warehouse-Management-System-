package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BOM 明细（D41）：一行 = 一个组件/物料 + 单台用量 + 规格/材质/备注。
 * goodsId 可空：关联即参与齐套预警；未关联(type=material 之外/不在库)为说明行，仅展示。
 */
@Data
@TableName("biz_bom_detail")
public class BizBomDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long bomId;

    /** 行序号 */
    private Integer sortNo;

    /** 关联物料 goods_id(type=material)，可空（说明行） */
    private Long goodsId;

    /** 组件/物料名称(冗余，展示与检索用) */
    private String componentName;

    /** 规格 */
    private String spec;

    /** 单台用量 */
    private BigDecimal quantity;

    /** 材质 */
    private String material;

    /** 备注(含外购标记等) */
    private String remark;

    /** 是否参考行(不参与齐套): 0-否, 1-是 */
    private Integer isReference;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}