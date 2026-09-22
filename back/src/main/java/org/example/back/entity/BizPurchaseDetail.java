package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 进货单明细行（D111）：一单可含 N 种物料，同一物料一单只允许一行。
 */
@Data
@TableName("biz_purchase_detail")
public class BizPurchaseDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 进货单ID(biz_purchase.id)
     */
    private Long purchaseId;

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
     * 行进货数量
     */
    private Integer quantity;

    /**
     * 行进货单价
     */
    private BigDecimal unitPrice;

    /**
     * D131 行级供应商ID（权威口径）：手动进货=头级供应商统一填入；
     * 采购申请渠道=到货提交逐行选定（一行可各不相同），头级留空。
     */
    private Long supplierId;

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
