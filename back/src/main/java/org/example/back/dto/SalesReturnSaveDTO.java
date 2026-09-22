package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售退货保存 DTO（D110 头行结构）：按来源销售明细行退，行级可退量=原行量-该行已退累计。
 */
@Data
public class SalesReturnSaveDTO {

    @NotNull(message = "来源销售单不能为空")
    private Long sourceSalesId;

    @NotEmpty(message = "退货明细不能为空")
    @Valid
    private List<Item> items;

    private LocalDateTime operationTime;

    /**
     * 退货公司名（可选，默认从来源销售单带出）
     */
    private String customerName;

    /**
     * D128 客户联系人姓名（可选，默认从来源销售单带出）
     */
    private String customerContactName;

    /**
     * D128 客户手机号（可选，默认从来源销售单带出）
     */
    private String customerPhone;

    private String remark;

    @Data
    public static class Item {

        @NotNull(message = "来源销售明细行不能为空")
        private Long sourceSalesDetailId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity;

        /**
         * 退货单价（可选，默认取来源销售行单价）
         */
        @DecimalMin(value = "0.01", message = "单价必须大于0")
        private BigDecimal unitPrice;
    }
}
