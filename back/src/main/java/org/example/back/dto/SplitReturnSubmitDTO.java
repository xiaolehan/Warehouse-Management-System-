package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * D116（ADR-0020）：拆分中（状态4）生产提交 RETURN 退料入参。
 * 行默认按 BOM×拆分数量快照预填，生产可按实际退回量调整（差异走 Q16 差异备注）。
 */
@Data
public class SplitReturnSubmitDTO {

    @Valid
    @NotEmpty(message = "请填写退料明细")
    private List<SplitReturnItemDTO> items;

    @Data
    public static class SplitReturnItemDTO {

        /** 物料ID（须属于该拆分单的 BOM 快照） */
        @NotNull(message = "物料不能为空")
        private Long goodsId;

        /** 实际退回数量（>0） */
        @NotNull(message = "退回数量不能为空")
        @Min(value = 1, message = "退回数量必须大于0")
        private Integer quantity;

        /** 与快照需求量的差异备注（Q16） */
        private String diffReason;
    }
}
