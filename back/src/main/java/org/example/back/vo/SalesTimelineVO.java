package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单履约时间线（D71/D110）：逐明细行回答客户"我订的那批货什么时候好"。
 */
@Data
public class SalesTimelineVO {

    private Long salesOrderId;

    private String salesNo;

    /** D110：逐明细行时间线（每行自己的 8 节点+预计可交付时间） */
    private List<SalesTimelineLineVO> lines;
}
