package org.example.back.vo;

import lombok.Data;

@Data
public class PickListDetailVO {

    private Long id;

    private Long pickListId;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private Integer sortNo;
}
