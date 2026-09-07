package org.example.back.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ProductionDraftItemDTO {
    /** 对应BOM明细id（生产缺料行来源） */
    private Long bomDetailId;

    /** 生产自选关联物料id(方案先行goodsId为空时由生产补料弹窗选定) */
    private Long goodsId;

    /** 申请数量覆盖值（不传则用缺口；0表示跳过该行不采购） */
    @Min(value = 0, message = "申请数量必须大于等于0")
    private Integer quantity;

    /** D60/ADR-0002：未知物料行(goodsId为空)的内联建档信息，newGoodsName 必填 */
    private String newGoodsName;

    /** 规格快照来源（预填自 BOM 行，可改） */
    private String spec;

    private String material;

    private String remark;

    /** 未知物料建档时的单位（BOM 无单位，由生产现填） */
    private String unit;
}
