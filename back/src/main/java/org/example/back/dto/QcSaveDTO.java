package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QcSaveDTO {

    @NotNull(message = "生产任务单不能为空")
    private Long orderId;

    /** 测点: first-首测, final-成品测 */
    @NotBlank(message = "测点不能为空")
    private String testPoint;

    /** 结果: OK-合格, NG-不合格 */
    @NotBlank(message = "测试结果不能为空")
    private String result;

    /** NG 原因（NG 时必填） */
    private String reason;
}