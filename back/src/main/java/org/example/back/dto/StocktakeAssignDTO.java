package org.example.back.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 改派负责人（D85：盘点中 admin 可改派，人不在岗时调整）
 */
@Data
public class StocktakeAssignDTO {

    @NotNull(message = "明细行不能为空")
    private Long detailId;

    @NotNull(message = "负责人不能为空")
    private Long assigneeId;
}
