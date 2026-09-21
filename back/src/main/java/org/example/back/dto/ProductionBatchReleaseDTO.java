package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * D113：按销售单批量下达生产任务单——从一张多成品销售单的明细行批量生成任务单。
 * 数量预填订单行数量（可改）；无 BOM/已有在途单等行不阻断其他行。
 */
@Data
public class ProductionBatchReleaseDTO {

    @NotNull(message = "销售单ID不能为空")
    private Long salesOrderId;

    @NotEmpty(message = "请勾选要下达的明细行")
    @Valid
    private List<BatchItemDTO> items;

    @Data
    public static class BatchItemDTO {

        @NotNull(message = "销售明细行ID不能为空")
        private Long salesDetailId;

        /** 生产数量：预填订单行数量，允许调整 */
        @NotNull(message = "生产数量不能为空")
        @Min(value = 1, message = "生产数量必须大于0")
        private Integer quantity;
    }
}
