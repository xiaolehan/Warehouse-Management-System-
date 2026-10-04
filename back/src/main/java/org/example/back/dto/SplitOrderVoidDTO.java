package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * D116（ADR-0020）：仓储作废成品拆分单入参（仅限未动库存的 1/2 态），原因必填（审计留痕）。
 */
@Data
public class SplitOrderVoidDTO {

    @NotBlank(message = "作废原因不能为空")
    private String reason;
}
