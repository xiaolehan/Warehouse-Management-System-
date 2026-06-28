package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class PickListQueryDTO extends PageQuery {

    private String pickNo;

    /**
     * 领料类型: PICK/SUPPLY/RETURN
     */
    private String pickType;

    /**
     * 状态: 1-待发料, 2-已发料, 3-已完成, 4-已驳回
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
