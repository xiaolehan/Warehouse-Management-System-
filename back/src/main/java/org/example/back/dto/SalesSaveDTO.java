package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售单保存 DTO（D110 头行结构）：头字段 + 明细行列表，同一成品一单只允许一行。
 * D106：销售日期 = 开单时间自动生成，不再接受客户端传入（「出库日期」标签废除）。
 */
@Data
public class SalesSaveDTO {

    @NotEmpty(message = "销售明细不能为空")
    @Valid
    private List<Item> items;

    /**
     * 客户公司名（对齐 wms_v1 下单文档，可选）
     */
    private String customerName;

    /**
     * 合同编号（对齐 wms_v1 下单文档，可选）
     */
    private String contractNo;

    /**
     * D128 客户联系人姓名（选填自由文本）
     */
    private String customerContactName;

    /**
     * D128 客户手机号（选填，不做格式校验）
     */
    private String customerPhone;

    /**
     * 是否含税: 0-不含税, 1-含税（仅记录标志，不影响金额计算）
     */
    private Integer taxIncluded;

    private String remark;

    @Data
    public static class Item {

        @NotNull(message = "商品不能为空")
        private Long goodsId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于0")
        private Integer quantity;

        @DecimalMin(value = "0.01", message = "单价必须大于0")
        private BigDecimal unitPrice;
    }
}
