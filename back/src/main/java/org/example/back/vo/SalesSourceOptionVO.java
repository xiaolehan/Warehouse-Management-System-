package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退货来源销售单选项（D110）：按来源销售单分组，明细行带行级可退量。
 */
@Data
public class SalesSourceOptionVO {

    /** 来源销售单 id（biz_sales.id） */
    private Long id;

    private String salesNo;

    private String customerName;

    private LocalDateTime operationTime;

    /**
     * linkableOptions（生产关联）场景=该成品在单内的明细行数量（(头单,成品)唯一解析）；
     * returnableOptions 场景为 null（行级数量看 lines），商品维度取行字段。
     */
    private Integer quantity;

    /** D110：该单的明细行（行级可退量 = 行量 - 该行被有效退货累计） */
    private List<SalesSourceOptionLineVO> lines;
}
