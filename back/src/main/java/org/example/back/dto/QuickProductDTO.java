package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * D121：销售端「+新品」快速建品入参——仅四项；类型/库存/进价/状态等其余字段服务端强制（ADR-0017）。
 */
@Data
public class QuickProductDTO {

    @NotBlank(message = "成品名称不能为空")
    private String goodsName;

    /** 规格（选填） */
    private String spec;

    @NotBlank(message = "单位不能为空")
    private String unit;

    /** 售价必填（表单默认 0 可改；快速建品不强制 >0，区别于 D68 售价编辑口径「售价必须大于0」） */
    @NotNull(message = "售价不能为空")
    private BigDecimal salePrice;
}