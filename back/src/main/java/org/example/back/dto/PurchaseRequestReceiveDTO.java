package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购入库 DTO：采购管理员填写各明细的采购单价(及可选数量)，提交后逐条转 biz_purchase 入库。
 */
@Data
public class PurchaseRequestReceiveDTO {

    @NotNull(message = "入库明细不能为空")
    @Valid
    private List<ReceiveItemDTO> items;

    @Data
    public static class ReceiveItemDTO {

        @NotNull(message = "明细ID不能为空")
        private Long detailId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity;

        @NotNull(message = "采购单价不能为空")
        @DecimalMin(value = "0.01", message = "单价必须大于0")
        private BigDecimal unitPrice;
    }
}
