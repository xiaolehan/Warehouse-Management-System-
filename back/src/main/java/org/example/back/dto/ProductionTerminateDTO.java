package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * D73：生产任务单手动终止入参。reason 必填（审计留痕）；
 * items 为终止退料明细（已领未退净额预填后生产管理员可改），空=无待退物料仅终止。
 */
@Data
public class ProductionTerminateDTO {

    @NotBlank(message = "终止原因不能为空")
    private String reason;

    @Valid
    private List<ProductionReturnItemDTO> items;
}
