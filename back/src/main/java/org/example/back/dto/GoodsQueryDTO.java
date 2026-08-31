package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class GoodsQueryDTO extends PageQuery {

    private String goodsName;

    /** 货品类型: material/product（D41） */
    private String type;

    private String productName;

    private String category;

    private Long supplierId;

    private Integer status;

    private Boolean warningOnly;

    /**
     * warningType: low | zero
     */
    private String warningType;
}