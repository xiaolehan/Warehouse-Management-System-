package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProductionDraftCreateDTO {
    @NotNull(message = "生产任务单不能为空")
    private Long productionOrderId;

    private String remark;

    @Valid
    private List<ProductionDraftItemDTO> details;   // 可选：按 bomDetailId 覆盖申请数量
}
