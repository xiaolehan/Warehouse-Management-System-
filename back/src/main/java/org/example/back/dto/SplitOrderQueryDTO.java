package org.example.back.dto;

import lombok.Data;

/**
 * D116（ADR-0020）：成品拆分单列表查询。
 */
@Data
public class SplitOrderQueryDTO {

    private Long pageNum = 1L;

    private Long pageSize = 10L;

    /** 状态（1-7） */
    private Integer status;

    /** 拆分单号（精确/模糊） */
    private String splitNo;

    /** 关联销售单号（模糊） */
    private String salesOrderNo;

    /** 成品名称（模糊） */
    private String goodsName;
}
