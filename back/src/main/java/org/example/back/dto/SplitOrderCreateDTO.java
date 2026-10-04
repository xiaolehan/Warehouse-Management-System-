package org.example.back.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * D116（ADR-0020）：仓储发起成品拆分入参。
 * 拆分数量默认=已入库未出库量，允许部分拆分；同一明细行同时只允许一张在途拆分单。
 */
@Data
public class SplitOrderCreateDTO {

    /** 销售明细行ID（必须已行终止且已入库未出库量>0） */
    @NotNull(message = "销售明细行不能为空")
    private Long salesDetailId;

    /** 拆分数量（1 ≤ quantity ≤ 已入库未出库量） */
    @NotNull(message = "拆分数量不能为空")
    @Min(value = 1, message = "拆分数量必须大于0")
    private Integer quantity;
}
