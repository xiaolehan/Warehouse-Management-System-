package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseRequestQueryDTO extends PageQuery {

    private String requestNo;

    /**
     * 状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回
     */
    private Integer status;

    /**
     * 明细商品名模糊匹配
     */
    private String goodsName;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
