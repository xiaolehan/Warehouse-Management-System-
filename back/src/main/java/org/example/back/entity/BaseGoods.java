package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("base_goods")
public class BaseGoods {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String goodsCode;

    /** 货品类型: material-物料/零件, product-成品（D41） */
    private String type;

    private String goodsName;

    private String productName;

    private String category;

    private String brand;

    private Long supplierId;

    private BigDecimal purchasePrice;

    private BigDecimal salePrice;

    private Integer stock;

    private Integer warningStock;

    private String unit;

    /** 规格（物料固有属性，ADR-0003；物料按「名称+规格」唯一） */
    private String spec;

    /** 材质（物料固有属性，ADR-0003） */
    private String material;

    private Integer status;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}