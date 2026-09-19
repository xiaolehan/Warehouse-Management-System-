package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductionQueryDTO extends PageQuery {

    private String productionNo;

    private String goodsName;

    private Long goodsId;

    /** D107 确认状态筛选: 1-待仓库确认, 2-已确认入库, 3-已驳回 */
    private Integer confirmStatus;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
