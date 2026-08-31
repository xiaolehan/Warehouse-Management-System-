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

    /** 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废 */
    private Integer status;

    private String statusText;

    /** 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料 */
    private String kitStatus;

    private String kitStatusText;

    private String source;

    /** 工序清单快照(8道装配工序静态 SOP) */
    private List<String> processList;

    private String remark;

    private LocalDateTime createTime;

    /** 齐套明细（含缺料标红/黄） */
    private List<KitShortageVO> kitLines;

    /** 质检状态（首测/成品测） */
    private QcStateVO qcState;

    private Integer isDeleted;
}