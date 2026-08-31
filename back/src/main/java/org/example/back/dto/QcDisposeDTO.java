package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QcDisposeDTO {

    @NotNull(message = "生产任务单不能为空")
    private Long orderId;

    /** 测点: first-首测, final-成品测（针对该测点最新的 NG 记录处置） */
    @NotBlank(message = "测点不能为空")
    private String testPoint;

    /** 处置: REWORK-返工, SCRAP-报废 */
    @NotBlank(message = "处置方式不能为空")
    private String disposition;
}