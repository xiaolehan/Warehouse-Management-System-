package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售单 VO（D110 头行结构）：头汇总 + 明细行列表。
 */
@Data
public class SalesVO {

    private Long id;

    private String salesNo;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    /** 均价 = totalAmount / totalQuantity（单行时即行单价，列表展示用） */
    private BigDecimal avgPrice;

    /** 商品汇总描述：单行 "PTO153"，多行 "PTO153 等 3 种" */
    private String goodsSummary;

    private List<SalesDetailVO> details;

    private LocalDateTime operationTime;

    private LocalDateTime salesDate;

    private String operatorName;

    private String operator;

    private String remark;

    private Integer bizStatus;

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    /**
     * 仓库确认状态: 1-待仓库确认, 2-已确认出库
     */
    private Integer confirmStatus;

    private String confirmStatusText;

    private LocalDateTime confirmTime;

    private String confirmerName;

    private String customerName;

    private String contractNo;

    /** D128 客户联系人姓名（选填自由文本） */
    private String customerContactName;

    /** D128 客户手机号（选填，不做格式校验） */
    private String customerPhone;

    /**
     * 是否含税: 0-不含税, 1-含税
     */
    private Integer taxIncluded;

    private LocalDateTime createTime;

    private Integer isDeleted;

    private Integer approvalStatus;

    private String approvalRequestAction;

    /** 最近审批单的备注（超管通过/驳回时填写，用于销售人员查看驳回原因）。 */
    private String approvalRemark;
}
