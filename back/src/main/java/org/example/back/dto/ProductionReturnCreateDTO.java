package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ProductionReturnCreateDTO {

    private String remark;

    @NotNull(message = "退料明细不能为空")
    @Size(min = 1, message = "至少添加一条退料明细")
    @Valid
    private List<ProductionReturnItemDTO> items;
}
