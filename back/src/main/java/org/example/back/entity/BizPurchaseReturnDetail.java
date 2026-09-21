package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 进货退货单明细行（D111）：一退货单 N 行，按来源进货明细行退（行级可退量=该行入库量−该行已退累计）。
 */
@Data
@TableName("biz_purchase_return_detail")
public class BizPurchaseReturnDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 退货单ID(biz_purchase_return.id)
     */
    private Long returnId;

    /**
     * 来源进货单ID(行级冗余)
     */
    private Long sourcePurchaseId;

    /**
     * 来源进货明细行ID(biz_purchase_detail.id)
     */
    private Long sourceDetailId;

    private Long goodsId;

    /**
     * 商品名称(冗余快照)
     */
    private String goodsName;

    /**
     * 规格(冗余快照)
     */
    private String spec;

    /**
     * 材质(冗余快照)
     */
    private String material;

    /**
     * 行退货数量
     */
    private Integer quantity;

    /**
     * 行退货单价
     */
    private BigDecimal unitPrice;

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
