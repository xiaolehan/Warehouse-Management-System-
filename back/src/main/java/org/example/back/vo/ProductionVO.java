package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductionVO {

    private Long id;

    private String productionNo;

    private String orderNo;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal price;

    private BigDecimal totalPrice;

    private BigDecimal totalAmount;

    private LocalDateTime operationTime;

    private LocalDateTime productionDate;

    private String operatorName;

    private String operator;

    private String remark;

    private Integer bizStatus;

    /** D107：来源生产任务单 id（生产端提交的入库申请有值；仓储手动新增为空） */
    private Long productionOrderId;

    /** D107：来源生产任务单号（列表/详情批量填充） */
    private String productionOrderNo;

    /** D107 确认状态: 1-待仓库确认, 2-已确认入库, 3-已驳回 */
    private Integer confirmStatus;

    private String confirmStatusText;

    /** D107：确认人（手动新增=录入人） */
    private String confirmerName;

    /** D107：确认/驳回时间 */
    private LocalDateTime confirmTime;

    /** D107：驳回原因（confirm_status=3 时有值） */
    private String rejectReason;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
