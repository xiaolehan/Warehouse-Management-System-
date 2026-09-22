package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * D120 采购到货 DTO：勾选本次实际到货的明细行 + 逐行采购单价。
 * 分批粒度=明细行：入参不含数量——每行按申请数量整行到货，行内数量不拆（Q8 用户拍板）。
 * D131：逐行供应商必选（预填物料绑定供应商，可改）——写入申请明细行，确认入库复制到进货明细行。
 */
@Data
public class PurchaseRequestReceiveDTO {

    @NotNull(message = "到货明细不能为空")
    @Valid
    private List<ReceiveItemDTO> items;

    @Data
    public static class ReceiveItemDTO {

        @NotNull(message = "明细ID不能为空")
        private Long detailId;

        @NotNull(message = "采购单价不能为空")
        @DecimalMin(value = "0.01", message = "单价必须大于0")
        private BigDecimal unitPrice;

        @NotNull(message = "供应商不能为空")
        private Long supplierId;
    }
}
