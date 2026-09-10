package org.example.back.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProductionOrderSaveDTO {

    /** 成品 goods_id(type=product) */
    @NotNull(message = "成品不能为空")
    private Long goodsId;

    @NotNull(message = "生产数量不能为空")
    @Min(value = 1, message = "生产数量至少为1")
    private Integer quantity;

    /** D70：关联销售单 id（选填；通用备货单留空）。须为同成品、正常且待出库的销售单 */
    private Long salesOrderId;

    private String remark;
}