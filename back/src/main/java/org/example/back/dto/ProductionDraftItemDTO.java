package org.example.back.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ProductionDraftItemDTO {
    /** 对应BOM明细id（生产缺料行来源） */
    private Long bomDetailId;

    /** 申请数量覆盖值（不传则用缺口）；>0 才生效 */
    @Min(value = 1, message = "申请数量必须大于0")
    private Integer quantity;
}
