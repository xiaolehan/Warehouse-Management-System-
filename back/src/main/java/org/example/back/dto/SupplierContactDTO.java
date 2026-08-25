package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SupplierContactDTO {

    @NotBlank(message = "联系人姓名不能为空")
    private String contactPerson;

    private String contactPhone;

    private String position;
}