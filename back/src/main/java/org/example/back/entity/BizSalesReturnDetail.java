package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客退单明细行（D110）：按来源销售明细行退，行级可退量=原行量-该行已退累计。
 */
@Data
@TableName("biz_sales_return_detail")
public class BizSalesReturnDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 退货单ID(biz_sales_return.id)
     */
    private Long returnId;

    /**
     * 来源销售明细行ID(biz_sales_detail.id)
     */
    private Long sourceSalesDetailId;

    private Long goodsId;

    /**
     * 商品名称(冗余字段)
     */
    private String goodsName;

    /**
     * 行退货数量
     */
    private Integer quantity;

    /**
     * 行退货单价
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
     * 成本来源: SOURCE_SALE/RECENT_PURCHASE/GOODS_PRICE/ZERO_FALLBACK
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
