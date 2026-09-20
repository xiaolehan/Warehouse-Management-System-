package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售单明细行（D110）：一单可含一客户 N 种成品，同一成品一单只允许一行。
 */
@Data
@TableName("biz_sales_detail")
public class BizSalesDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 销售单ID(biz_sales.id)
     */
    private Long salesId;

    private Long goodsId;

    /**
     * 商品名称(冗余字段)
     */
    private String goodsName;

    /**
     * 行销售数量
     */
    private Integer quantity;

    /**
     * 行销售单价
     */
    private BigDecimal unitPrice;

    /**
     * 成本单价快照
     */
    private BigDecimal costUnitPrice;

    /**
     * 成本总额快照
     */
    private BigDecimal costTotalPrice;

    /**
     * 成本来源: RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK
     */
    private String costSource;

    /**
     * 行总金额
     */
    private BigDecimal totalPrice;

    /**
     * 行序号(同单从1递增)
     */
    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
