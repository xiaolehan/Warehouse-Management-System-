package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BomDetailVO {

    private Long id;

    private Long bomId;

    /** 行序号 */
    private Integer sortNo;

    /** 关联物料 goods_id(type=material)，可空（说明行） */
    private Long goodsId;

    /** 组件/物料名称 */
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
}