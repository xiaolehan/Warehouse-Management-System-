package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class StocktakeVO {

    private Long id;

    private String stocktakeNo;

    private Integer status;

    private String statusText;

    private String remark;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime submitTime;

    /**
     * 提交人（D85：与审核人分离留痕）
     */
    private String submitterName;

    private String reviewerName;

    private LocalDateTime reviewTime;

    private String rejectReason;

    private String cancelReason;

    /**
     * 取消人（D85）
     */
    private String cancelerName;

    private LocalDateTime createTime;

    /**
     * 汇总（服务端按明细计算）：总行数/已盘/未盘
     */
    private Integer totalRows;

    private Integer countedRows;

    private Integer unscannedRows;

    /**
     * 审核后差异汇总：盘盈/盘亏/账实一致行数
     */
    private Integer overRows;

    private Integer shortRows;

    private Integer matchRows;

    private List<StocktakeDetailVO> detailList;
}
