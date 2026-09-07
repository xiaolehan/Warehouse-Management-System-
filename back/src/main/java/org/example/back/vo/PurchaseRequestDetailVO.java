package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseRequestDetailVO {

    private Long id;

    private Long requestId;

    private Long goodsId;

    /** 对应BOM明细id */
    private Long bomDetailId;

    private String goodsName;

    /** 规格/材质/备注快照（D60：生产补料提交时自 BOM 行带入） */
    private String spec;

    private String material;

    private String remark;

    /** 新物料标记（D60）：0-已有物料缺口，1-未知物料自动建档 */
    private Integer isNewMaterial;

    private Integer quantity;

    private Integer arriveQuantity;

    private BigDecimal unitPrice;

    private Integer sortNo;
}
