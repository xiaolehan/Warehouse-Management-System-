package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SupplierSaveDTO {

    @NotBlank(message = "供应商名称不能为空")
    private String supplierName;

    @NotEmpty(message = "请至少填写一位联系人")
    @Valid
    private List<SupplierContactDTO> contacts;

    private String address;

    private Integer status;

    private String description;
}