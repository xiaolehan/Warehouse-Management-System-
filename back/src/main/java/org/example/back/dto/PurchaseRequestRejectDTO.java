package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PurchaseRequestRejectDTO {

    @NotBlank(message = "驳回原因不能为空")
    private String reason;
}
