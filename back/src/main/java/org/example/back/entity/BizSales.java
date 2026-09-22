package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售单头表（D110 头行结构）：只留单据与汇总字段，成品行见 {@link BizSalesDetail}。
 */
@Data
@TableName("biz_sales")
public class BizSales {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String salesNo;

    /**
     * 销售总数量(建单按明细行合计，单据不可编辑)
     */
    private Integer totalQuantity;

    /**
     * 销售总金额(建单按明细行合计，单据不可编辑)
     */
    private BigDecimal totalAmount;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private String remark;

    /**
     * 1-正常, 2-已作废, 3-红冲单
     */
    private Integer bizStatus;

    /**
     * 红冲来源单ID
     */
    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    /**
     * 仓库确认状态: 1-待仓库确认, 2-已确认出库
     */
    private Integer confirmStatus;

    private LocalDateTime confirmTime;

    private Long confirmerId;

    private String confirmerName;

    /**
     * 客户公司名(对齐下单文档)
     */
    private String customerName;

    /**
     * 是否含税: 0-不含税, 1-含税（仅记录标志，不影响金额计算）
     */
    private Integer taxIncluded;

    /**
     * 合同编号(对齐下单文档)
     */
    private String contractNo;

    /**
     * D128 客户联系人姓名（选填自由文本）
     */
    private String customerContactName;

    /**
     * D128 客户手机号（选填，不做格式校验）
     */
    private String customerPhone;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
