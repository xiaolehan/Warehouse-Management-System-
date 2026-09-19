package org.example.back.vo;

import lombok.Data;

import java.util.List;

/**
 * 单据流程时间线（D104）：按单据字段合成标准流程节点（与销售履约时间线同模式），
 * 回答「谁在哪一步做了什么」；回退后仅显示当前所处步骤，弯路细节由超管操作日志承担。
 */
@Data
public class DocumentTimelineVO {

    /** 单据编号（进货单号/申请单号/退货单号） */
    private String docNo;

    /** 流程节点（按业务顺序排列） */
    private List<DocumentTimelineNodeVO> nodes;
}
