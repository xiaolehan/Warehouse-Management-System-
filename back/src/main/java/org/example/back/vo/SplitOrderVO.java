package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * D116（ADR-0020）：成品拆分单 VO。
 * 状态机：1待生产领取 → 2待仓储确认成品出库 → 3待生产确认收货 → 4拆分中 → 5已完成；
 * 7待仓储确认成品回库（生产放弃）→ 5（完成方式=放弃回库）；6已作废。
 */
@Data
public class SplitOrderVO {

    private Long id;

    private String splitNo;

    private Long salesOrderId;

    private String salesOrderNo;

    private Long salesDetailId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    /** 拆分数量 */
    private Integer quantity;

    /** 状态: 1-待生产领取, 2-待仓储确认成品出库, 3-待生产确认收货, 4-拆分中, 5-已完成, 6-已作废, 7-待仓储确认成品回库 */
    private Integer status;

    /** 状态文本 */
    private String statusText;

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

    /** 关联 RETURN 退料单ID（biz_pick_list） */
    private Long returnPickListId;

    /** 关联 RETURN 退料单号（列表标注「拆分退料」用） */
    private String returnPickNo;

    /** 完成方式: 1-退料完成, 2-放弃回库 */
    private Integer finishType;

    /** 完成方式文本 */
    private String finishTypeText;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** BOM×拆分数量快照明细 */
    private List<SplitOrderDetailVO> details;
}
