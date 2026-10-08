package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * D116（ADR-0020）：成品拆分单明细行 VO（BOM×拆分数量快照）。
 */
@Data
public class SplitOrderDetailVO {

    private Long id;

    private Long splitOrderId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    /** 需求量(BOM×拆分数量) */
    private Integer requiredQuantity;

    /** 已退量（D140）：关联退料单中该物料实际提交退回的数量；未提交/已撤销退料或该物料不在退料明细中时为 null（前端显示「—」） */
    private Integer returnedQuantity;

    /** 退料差异备注（D140）：关联退料单中该物料的损耗/丢失等原因说明；无差异行为空 */
    private String returnDiffReason;

    private Integer sortNo;

    private LocalDateTime createTime;
}
