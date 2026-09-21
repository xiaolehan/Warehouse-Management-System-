package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseReturnSaveDTO {

    @NotEmpty(message = "退货明细不能为空")
    @Valid
    private List<LineDTO> lines;

    private LocalDateTime operationTime;

    private String remark;

    @Data
    public static class LineDTO {

        /** 来源进货明细行ID(biz_purchase_detail.id) */
        @NotNull(message = "来源进货明细行不能为空")
        private Long sourceDetailId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity;
    }
}
