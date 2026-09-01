package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DraftConfirmDTO {
    @NotNull(message = "转正明细不能为空")
    @Size(min = 1, message = "至少选择一条物料待转正")
    @Valid
    private List<DraftConfirmItemDTO> items;
}
