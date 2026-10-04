package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单履约时间线的单明细行（D110 决策⑦）：每行自己的节点链+预计可交付时间。
 */
@Data
public class SalesTimelineLineVO {

    /** 明细行 id（biz_sales_detail.id） */
    private Long salesDetailId;

    private String goodsName;

    private Integer quantity;

    /** 当前库存快照（判断是否"现货充足"） */
    private Integer stock;

    /** 8 节点时间线（现货充足且无关联生产单时仅 下单/发货 两节点） */
    private List<SalesTimelineNodeVO> nodes;

    /** 预计可交付时间（系统推算或生产手工修正；不可推算时为 null） */
    private LocalDateTime estimatedDeliveryTime;

    /**
     * 预计可交付文案：立即可发 / 预计 yyyy-MM-dd 可交付 / 待生产排产 / 待生产评估（新品未填工期）/
     * 缺料待采购确认到货时间 / 已发货
     */
    private String estimatedDeliveryText;

    /** 预计来源: none-不可推算, system-系统推算, manual-生产手工修正 */
    private String estimatedSource;

    // ==================== ADR-0020/D116：行终止行成品处置透出 ====================

    /** 成品处置状态: NULL-未触发, 1-待处置, 2-已保留成品, 3-已发起拆分, 4-拆分完成 */
    private Integer splitStatus;

    /** 已入库未出库量（实时计算） */
    private Integer unshippedInboundQty;

    /** 在途/最近一张关联拆分单ID（有则透出） */
    private Long splitOrderId;

    /** 在途/最近一张关联拆分单号 */
    private String splitOrderNo;

    /** 保留成品操作人姓名（splitStatus=2 时展示） */
    private String splitKeepName;

    /** 保留成品时间（splitStatus=2 时展示） */
    private LocalDateTime splitKeepTime;

    /** 仓储处置双按钮门控（保留成品/发起拆分）：行终止 + 仓储管理员/超管 + 待处置 + 余量>0 */
    private Boolean canHandleSplit;
}
