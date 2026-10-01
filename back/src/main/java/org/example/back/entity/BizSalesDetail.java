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

    /** 行终止状态：正常（需求一 Q21 行级终止） */
    public static final int TERMINATE_NORMAL = 1;
    /** 行终止状态：已终止（客户取消；全部行终止时头单 biz_status 派生为 4-已终止，ADR-0019） */
    public static final int TERMINATE_TERMINATED = 2;

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

    /**
     * 行终止状态: 1-正常, 2-已终止（需求一 Q21 行级终止；全部行终止 → 头单 biz_status=4 已终止）
     */
    private Integer terminateStatus;

    /**
     * 行终止原因（必填留痕，对齐生产终止原因口径）
     */
    private String terminateReason;

    /**
     * 行终止时间
     */
    private LocalDateTime terminateTime;

    /**
     * 行终止操作人ID
     */
    private Long terminatorId;

    /**
     * 行终止操作人姓名
     */
    private String terminatorName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
