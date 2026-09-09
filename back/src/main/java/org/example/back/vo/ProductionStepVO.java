package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产工序行（D64）：type=manual 人工打卡行；type=qc 由质检记录推导；type=receipt 由生产入库推导。
 */
@Data
public class ProductionStepVO {

    private Integer stepNo;

    private String stepName;

    /** 行类型: manual-人工打卡, qc-质检推导, receipt-入库推导 */
    private String type;

    /** 是否完成 */
    private Boolean done;

    /** 状态文案: 未完成/已完成/未测/NG-待处置/返工-待重测/已报废/待入库(质检合格)/已完成(已入库) */
    private String statusText;

    /** tag 颜色: info/warning/danger/success */
    private String tagType;

    /** 打卡人（仅 manual 完成行） */
    private String operatorName;

    /** 打卡时间（仅 manual 完成行） */
    private LocalDateTime operateTime;

    /** 是否可打卡（manual 未完成 且 订单生产中/待入库） */
    private Boolean operable;

    /** 是否可撤销（manual 已完成 且 订单生产中/待入库；本人/管理员校验在接口层） */
    private Boolean revocable;
}
