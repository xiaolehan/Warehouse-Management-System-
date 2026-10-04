package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成品拆分单头表（ADR-0020，D116）：销售明细行终止后「已入库未出库」成品的跨部门拆解处置单据。
 * 状态机：1待生产领取 → 2待仓储确认成品出库 → 3待生产确认收货 → 4拆分中 → 5已完成；
 * 旁支：7待仓储确认成品回库（生产放弃拆分，仓储确认才加成品库存）→ 5（完成方式=放弃回库）；
 * 6已作废（仓储，仅限未动库存的 1/2 态）。
 */
@Data
@TableName("biz_split_order")
public class BizSplitOrder {

    /** 状态：待生产领取 */
    public static final int STATUS_PENDING_CLAIM = 1;
    /** 状态：待仓储确认成品出库 */
    public static final int STATUS_PENDING_OUTBOUND = 2;
    /** 状态：待生产确认收货 */
    public static final int STATUS_PENDING_RECEIPT = 3;
    /** 状态：拆分中（生产已领到成品，RETURN 退料单流转中） */
    public static final int STATUS_SPLITTING = 4;
    /** 状态：已完成 */
    public static final int STATUS_DONE = 5;
    /** 状态：已作废 */
    public static final int STATUS_VOIDED = 6;
    /** 状态：待仓储确认成品回库（生产放弃拆分） */
    public static final int STATUS_PENDING_RESTOCK = 7;

    /** 完成方式：退料完成（物料退回仓库） */
    public static final int FINISH_TYPE_RETURN = 1;
    /** 完成方式：放弃回库（成品回仓） */
    public static final int FINISH_TYPE_ABANDON = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String splitNo;

    private Long salesOrderId;

    private String salesOrderNo;

    private Long salesDetailId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    /**
     * 拆分数量
     */
    private Integer quantity;

    /**
     * 状态: 1-待生产领取, 2-待仓储确认成品出库, 3-待生产确认收货, 4-拆分中, 5-已完成, 6-已作废, 7-待仓储确认成品回库
     */
    private Integer status;

    private Long initiatorId;

    private String initiatorName;

    private LocalDateTime initTime;

    private Long claimUserId;

    private String claimUserName;

    private LocalDateTime claimTime;

    private Long outboundConfirmUserId;

    private String outboundConfirmUserName;

    private LocalDateTime outboundConfirmTime;

    private Long receiptConfirmUserId;

    private String receiptConfirmUserName;

    private LocalDateTime receiptConfirmTime;

    private Long abandonUserId;

    private String abandonUserName;

    private LocalDateTime abandonTime;

    /**
     * 关联RETURN退料单ID(biz_pick_list)
     */
    private Long returnPickListId;

    /**
     * 完成方式: 1-退料完成, 2-放弃回库
     */
    private Integer finishType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
