package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseQueryDTO extends PageQuery {

    private String purchaseNo;

    private String goodsName;

    private Long goodsId;

    /** 按供应商名称模糊筛选（供应商在 base_goods.supplier_id -> base_supplier，非本表列） */
    private String supplierName;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
