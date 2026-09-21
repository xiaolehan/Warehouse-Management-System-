package org.example.back.vo;

import lombok.Data;

/**
 * D113：按销售单批量下达——单行结果。成功行带任务单单号/齐套状态；
 * 跳过行 success=false 且 skipReason 说明（无 BOM/已有在途单/已满足等）。
 */
@Data
public class ProductionReleaseResultVO {

    private Long salesDetailId;
    private String goodsName;
    private Integer quantity;
    private Boolean success;
    /** 成功：新任务单 */
    private Long orderId;
    private String orderNo;
    /** 成功：齐套快照状态（ok/partial/block，同建单口径） */
    private String kitStatus;
    /** 跳过原因（success=false 时有值） */
    private String skipReason;
}
