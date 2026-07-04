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
