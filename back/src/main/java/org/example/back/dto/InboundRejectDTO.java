package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * D107：仓储驳回成品入库申请（附原因，通知生产提交人重新提交）。
 */
@Data
public class InboundRejectDTO {

    @NotBlank(message = "驳回原因不能为空")
    @Size(max = 200, message = "驳回原因不能超过200字")
    private String reason;
}
