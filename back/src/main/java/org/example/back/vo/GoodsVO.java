package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsVO {

    private Long id;

    private String goodsCode;

    /** 货品类型: material/product（D41） */
    private String type;

    private String goodsName;

    private String productName;

    private String category;

    private String brand;

    private Long supplierId;

    private String supplierName;

    /** D123 物料「最新供应商」：最近一张已入库+正常进货单的头级供应商名；无进货记录回退绑定供应商 */
    private String latestSupplierName;

    /** D123：latestSupplierName 是否为回退值（true 时前端标「默认」tag） */
    private Boolean latestSupplierDefault;

    private BigDecimal purchasePrice;

    private BigDecimal salePrice;

    private BigDecimal price;

    private Integer stock;

    private Integer warningStock;

    private String unit;

    /** 规格/材质（物料固有属性，ADR-0003） */
    private String spec;

    private String material;

    private Integer status;

    private String description;

    private LocalDateTime createTime;
}