package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客退单头表（D110 头行结构）：只留单据与汇总字段，退货行见 {@link BizSalesReturnDetail}。
 */
@Data
@TableName("biz_sales_return")
public class BizSalesReturn {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String returnNo;

    private Long sourceSalesId;

    private String sourceSalesNo;

    /**
     * 客户公司名快照（从来源销售单带入）
     */
    private String customerName;

    /**
     * D128 客户联系人姓名快照（可从来源销售单带出可改）
     */
    private String customerContactName;

    /**
     * D128 客户手机号快照（可从来源销售单带出可改）
     */
    private String customerPhone;

    /**
     * 退货总数量(建单按明细行合计，单据不可编辑)
     */
    private Integer totalQuantity;

    /**
     * 退货总金额(建单按明细行合计，单据不可编辑)
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
     * 仓库确认状态: 1-待仓库确认, 2-已确认入库
     */
    private Integer confirmStatus;

    private LocalDateTime confirmTime;

    private Long confirmerId;

    private String confirmerName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
