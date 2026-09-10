package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单履约时间线（D71）：回答客户"我订的那批货什么时候好"。
 */
@Data
public class SalesTimelineVO {

    private Long salesOrderId;

    private String salesNo;

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
}
