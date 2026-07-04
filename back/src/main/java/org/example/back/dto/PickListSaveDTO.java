package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PickListSaveDTO {

    @NotBlank(message = "领料类型不能为空")
    private String pickType;

    /**
     * 关联销售单ID（可选）
     */
    private Long sourceSalesId;

    private String remark;

    @NotNull(message = "领料明细不能为空")
    @Size(min = 1, message = "至少添加一条领料明细")
    @Valid
    private List<PickListDetailDTO> details;
}
