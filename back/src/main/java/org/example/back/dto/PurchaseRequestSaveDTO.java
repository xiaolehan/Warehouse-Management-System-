package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PurchaseRequestSaveDTO {

    private String remark;

    @NotNull(message = "采购申请明细不能为空")
    @Size(min = 1, message = "至少添加一条采购申请明细")
    @Valid
    private List<PurchaseRequestDetailDTO> details;
}
