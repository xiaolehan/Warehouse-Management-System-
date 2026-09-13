package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class StocktakeCreateDTO {

    /**
     * 盘点行（商品 + 负责人——D85：建单逐行指定负责人，每行必选）
     */
    @NotEmpty(message = "请选择至少一个盘点商品")
    @Valid
    private List<Item> items;

    private String remark;

    @Data
    public static class Item {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        @NotNull(message = "每行必须指定负责人")
        private Long assigneeId;
    }
}
