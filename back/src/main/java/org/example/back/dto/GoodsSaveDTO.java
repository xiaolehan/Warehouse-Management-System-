package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class GoodsSaveDTO {

    @NotBlank(message = "物料名称不能为空")
    private String goodsName;

    private String productName;

    private String category;

    private String brand;

    @NotNull(message = "供应商不能为空")
    private Long supplierId;

    // 进价/售价为采购维护字段：仓储建物料时可空（采购编辑时由 Service 校验进价>0）
    private BigDecimal purchasePrice;

    private BigDecimal salePrice;

    private Integer stock;

    private Integer warningStock;

    private String unit;

    private Integer status;

    private String description;
}