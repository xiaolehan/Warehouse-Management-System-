package org.example.back.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseRequestDetailDTO {

    @NotNull(message = "商品不能为空")
    private Long goodsId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity;

    /**
     * 采购单价(可选，入库时可补填)
     */
    private BigDecimal unitPrice;

    /**
     * 行序号，可选，不传则按列表顺序自动补序
     */
    private Integer sortNo;
}
