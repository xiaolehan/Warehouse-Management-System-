package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BOM 明细（D41）：一行 = 一个组件/物料 + 单台用量 + 规格/材质/图片/备注。
 * goodsId 可空（方案先行）：关联即按物料库存参与齐套预警；未关联的明细行仍计入真需求——
 * 齐套时按"库存0/缺料待采购"处理，待物料在仓库建档后回挂 goodsId 即正常齐套。
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

    /** 组件图片路径(如 /uploads/2026/09/01/uuid.png) */
    private String image;

    /** 是否参考行(不参与齐套): 0-否, 1-是 */
    private Integer isReference;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}