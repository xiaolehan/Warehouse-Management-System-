package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseSaveDTO {

    @NotEmpty(message = "进货明细不能为空")
    @Valid
    private List<LineDTO> lines;

    private LocalDateTime operationTime;

    /**
     * D123 头级供应商：手动进货必填（Service 校验存在性）；
     * 采购申请批次入库（createInternal）无供应商来源，可空。
     */
    private Long supplierId;

    private String remark;

    @Data
    public static class LineDTO {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity;

        @DecimalMin(value = "0.01", message = "单价必须大于0")
        private BigDecimal unitPrice;
    }
}
