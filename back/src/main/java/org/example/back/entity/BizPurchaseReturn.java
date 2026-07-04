package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_purchase_return")
public class BizPurchaseReturn {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String returnNo;

    private Long sourcePurchaseId;

    private String sourcePurchaseNo;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalPrice;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private String remark;

    /**
     * 1-正常, 2-已作废, 3-红冲单
     */
    private Integer bizStatus;

    /**
     * 退货确认: 1-待出库确认, 2-待退货确认, 3-已退货
     */
    private Integer confirmStatus;

    /**
     * 出库确认人ID(仓储)
     */
    private Long confirmerId;

    /**
     * 出库确认人姓名
     */
    private String confirmerName;

    /**
     * 仓储确认出库时间
     */
    private LocalDateTime confirmTime;

    /**
     * 退货完成确认人ID(采购)
     */
    private Long completerId;

    /**
     * 退货完成确认人姓名
     */
    private String completerName;

    /**
     * 采购确认退货成功时间
     */
    private LocalDateTime completeTime;

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
