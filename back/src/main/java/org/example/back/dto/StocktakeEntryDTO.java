package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class StocktakeEntryDTO {

    @NotEmpty(message = "录入项不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "明细ID不能为空")
        private Long detailId;

        @NotNull(message = "实盘数不能为空")
        private Integer actualQty;
    }
}
