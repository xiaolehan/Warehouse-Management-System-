package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 履约时间线节点（D71）：已完成节点带实际时间，未来节点 time 为空、description 说明待办。
 */
@Data
public class SalesTimelineNodeVO {

    /** 节点 key：order_placed/scheduled/materials/started/assembly/qc/inbound/shipped */
    private String key;

    /** 节点标题：下单/生产排产/物料准备/开工/装配进度/质检/成品入库/发货 */
    private String title;

    /** 状态: done-已完成, current-进行中, pending-未开始 */
    private String status;

    /** 实际发生时间（done 节点；开工等推导节点为近似值） */
    private LocalDateTime time;

    /** 补充说明：单号、进度 x/7、缺口描述等 */
    private String description;
}
