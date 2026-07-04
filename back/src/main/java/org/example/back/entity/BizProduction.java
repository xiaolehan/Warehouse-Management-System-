package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产入库单：仓储管理员将自己生产的零件存入仓库，库存增加。
 */
@Data
@TableName("biz_production")
public class BizProduction {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String productionNo;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    /**
     * 生产单价（可选，自产零件成本可能未知）
     */
    private BigDecimal unitPrice;

    /**
     * 总金额（unitPrice 为空时为空）
     */
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
