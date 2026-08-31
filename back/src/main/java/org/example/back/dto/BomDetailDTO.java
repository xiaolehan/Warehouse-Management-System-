package org.example.back.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BomDetailDTO {

    /** 关联物料 goods_id(type=material)，可空——空则视为说明行(仅展示) */
    private Long goodsId;

    /** 组件/物料名称 */
    private String componentName;

    /** 规格 */
    private String spec;

    @NotNull(message = "单台用量不能为空")
    @DecimalMin(value = "0", message = "单台用量不能为负数")
    private BigDecimal quantity;

    /** 材质 */
    private String material;

    private String remark;

    /** 是否参考行(不参与齐套) */
    private Boolean isReference;
}