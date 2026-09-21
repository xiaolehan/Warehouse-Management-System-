package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductionOrderQueryDTO extends PageQuery {

    /** 任务单号模糊匹配 */
    private String orderNo;

    /** 成品名称模糊匹配 */
    private String goodsName;

    /** 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废 */
    private Integer status;

    /** D113：关联销售单号模糊匹配——看全这张销售单的所有任务单 */
    private String salesNo;
}