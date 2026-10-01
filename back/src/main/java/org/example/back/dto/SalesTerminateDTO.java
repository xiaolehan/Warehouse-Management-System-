package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 需求一（行级终止）：销售订单终止入参。lines 为本次要终止的明细行集合（可一行或多行），
 * 每行必填终止原因（Q22-3 对齐生产终止原因留痕口径）；免审批立即生效（ADR-0019）。
 */
@Data
public class SalesTerminateDTO {

    @NotEmpty(message = "请选择要终止的明细行")
    @Valid
    private List<TerminateLine> lines;

    @Data
    public static class TerminateLine {

        @NotNull(message = "明细行ID不能为空")
        private Long detailId;

        @NotBlank(message = "终止原因不能为空")
        @Size(max = 200, message = "终止原因不能超过200字符")
        private String reason;
    }
}
