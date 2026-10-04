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

    /** 成品处置：待处置（已入库未出库，仓储二选一） */
    public static final int SPLIT_PENDING_HANDLE = 1;
    /** 成品处置：已保留成品 */
    public static final int SPLIT_KEPT = 2;
    /** 成品处置：已发起拆分 */
    public static final int SPLIT_INITIATED = 3;
    /** 成品处置：拆分完成（含放弃回库完成） */
    public static final int SPLIT_DONE = 4;

    /**
     * 成品处置状态(行终止后,ADR-0020): NULL-未触发(旧数据/未终止行), 1-待处置, 2-已保留成品, 3-已发起拆分, 4-拆分完成(含放弃回库完成)
     */
    private Integer splitStatus;

    /**
     * 当前关联拆分单ID(已发起拆分后,ADR-0020)
     */
    private Long splitOrderId;

    /**
     * 保留成品时间(ADR-0020)
     */
    private LocalDateTime splitKeepTime;

    /**
     * 保留成品操作人ID(ADR-0020)
     */
    private Long splitKeepBy;

    /**
     * 保留成品操作人姓名(冗余,ADR-0020)
     */
    private String splitKeepName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
