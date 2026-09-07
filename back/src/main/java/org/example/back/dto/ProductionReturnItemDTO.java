package org.example.back.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductionReturnItemDTO {

    @NotNull(message = "物料ID不能为空")
    private Long goodsId;

    @NotNull(message = "退料数量不能为空")
    @Min(value = 1, message = "退料数量必须大于0")
    private Integer quantity;
}
