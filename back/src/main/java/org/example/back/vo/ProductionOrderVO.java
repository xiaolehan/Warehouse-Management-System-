package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductionOrderVO {

    private Long id;

    private String orderNo;

    private Long goodsId;

    private String goodsName;

    private String unit;

    private Integer quantity;

    /** 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止 */
    private Integer status;

    private String statusText;

    /** 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料 */
    private String kitStatus;

    private String kitStatusText;

    private String source;

    /** D70：关联销售单 id（可空） */
    private Long salesOrderId;

    /** D70：关联销售单号（展示用，列表/详情批量填充） */
    private String salesOrderNo;

    /** D71：生产手工修正的预计完工时间（可空，优先于系统推算） */
    private LocalDateTime expectedCompletionTime;

    /** 工序清单快照(SOP 文字；详情有工序实例时由 stepList 取代，历史单回落) */
    private List<String> processList;

    /** 10 道生产工序行（D64：人工行+质检/入库推导行；历史单无实例时为 null） */
    private List<ProductionStepVO> stepList;

    private String remark;

    private LocalDateTime createTime;

    /** 齐套明细（含缺料标红/黄） */
    private List<KitShortageVO> kitLines;

    /** 质检状态（首测/成品测） */
    private QcStateVO qcState;

    private Integer isDeleted;
}