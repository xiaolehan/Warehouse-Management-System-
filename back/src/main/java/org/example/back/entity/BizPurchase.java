package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_purchase")
public class BizPurchase {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String purchaseNo;

    /**
     * 进货总数量(按明细行合计)
     */
    private Integer totalQuantity;

    /**
     * 进货总金额(按明细行合计)
     */
    private BigDecimal totalAmount;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private String remark;

    /**
     * D123 头级供应商：手动进货必填；存量单与采购申请渠道（createInternal）单据可空
     */
    private Long supplierId;

    /**
     * 1-正常, 2-已作废, 3-红冲单
     */
    private Integer bizStatus;

    /**
     * 入库确认: 1-待到货, 2-待入库确认, 3-已入库
     */
    private Integer confirmStatus;

    /**
     * 采购到货确认时间
     */
    private LocalDateTime arriveTime;

    /**
     * 入库确认人ID(仓储)
     */
    private Long confirmerId;

    /**
     * 入库确认人姓名
     */
    private String confirmerName;

    /**
     * 仓储确认入库时间
     */
    private LocalDateTime confirmTime;

    /**
     * 红冲来源单ID
     */
    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
