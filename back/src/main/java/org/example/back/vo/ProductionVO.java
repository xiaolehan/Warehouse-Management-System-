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

    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
