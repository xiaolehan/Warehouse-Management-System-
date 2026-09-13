package org.example.back.dto;

import lombok.Data;

@Data
public class StocktakeCancelDTO {

    /**
     * 取消原因（选填——D83 取消是零库存影响的撤销动作）
     */
    private String reason;
}
