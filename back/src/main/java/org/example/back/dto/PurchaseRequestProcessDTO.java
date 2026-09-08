package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购认领/修改到货计划 DTO（D61 行级化）：按明细行填写预计到货时间（必填）与到货备注（选填）。
 */
@Data
public class PurchaseRequestProcessDTO {

    @NotEmpty(message = "请填写各行预计到货时间")
    @Valid
    private List<ProcessItemDTO> items;

    @Data
    public static class ProcessItemDTO {

        @NotNull(message = "明细ID不能为空")
        private Long detailId;

        @NotNull(message = "预计到货时间必填")
        private LocalDateTime expectedArrivalTime;

        @Size(max = 200, message = "到货备注不能超过200字")
        private String arrivalRemark;
    }
}
