package org.example.back.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DraftConfirmItemDTO {
    @NotNull(message = "明细不能为空")
    private Long detailId;

    @NotNull(message = "请选择物料")
    private Long goodsId;
}
