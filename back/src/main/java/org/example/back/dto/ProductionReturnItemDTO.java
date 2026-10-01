package org.example.back.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductionReturnItemDTO {

    @NotNull(message = "物料ID不能为空")
    private Long goodsId;

    @NotNull(message = "退料数量不能为空")
    // F2/code-review：终止退料弹窗允许 0（全部损耗场景，差异备注必填由守卫兜底）；常规退料路径仍由 filter>0 过滤
    @Min(value = 0, message = "退料数量不能小于0")
    private Integer quantity;

    /**
     * 需求二 Q15/Q16/Q7：差异备注——退料数量小于已领未退（差异>0）时必填，
     * 说明损耗/丢失等原因；随终止退料单落库到 biz_pick_list_detail.diff_reason，仓储确认时可见。
     */
    @Size(max = 200, message = "差异备注不能超过200字符")
    private String diffReason;
}
