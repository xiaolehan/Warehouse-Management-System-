package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单据流程时间线节点（D104）：跨部门单据详情内的「谁在哪一步做了什么」视图，
 * 与销售履约时间线（SalesTimelineNodeVO，回答「货什么时候好」）语义不同、结构同构。
 */
@Data
public class DocumentTimelineNodeVO {

    /** 节点 key：created/arrived/received/claimed/submitted/confirmedOut/confirmedIn/completed/rejected/void_pending/voided */
    private String key;

    /** 节点标题：业务步骤名（创建进货单/采购到货/仓储入库确认…） */
    private String title;

    /** 状态: done-已完成, current-进行中, pending-未开始（作废单据的未到步骤也归 pending） */
    private String status;

    /** 实际发生时间（done 节点；驳回节点取 updateTime 近似） */
    private LocalDateTime time;

    /** 补充说明：操作人（人名或角色名）、库存影响、原因等 */
    private String description;
}
