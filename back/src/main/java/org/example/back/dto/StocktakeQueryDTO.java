package org.example.back.dto;

import lombok.Data;

@Data
public class StocktakeQueryDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    private Integer status;

    private String stocktakeNo;
}
