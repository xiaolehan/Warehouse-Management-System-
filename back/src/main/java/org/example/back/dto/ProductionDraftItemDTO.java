package org.example.back.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ProductionDraftItemDTO {
    /** 对应BOM明细id（生产缺料行来源） */
    private Long bomDetailId;

    /** 申请数量覆盖值（不传则用缺口；0表示跳过该行不采购） */
    @Min(value = 0, message = "申请数量必须大于等于0")
    private Integer quantity;
}
