package org.example.back.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * D109：未知物料「匹配供应商」结果（仓储人工触发，回写 base_goods.supplier_id 后返回）。
 * 兼作审计快照：定格物料、供应商与来源采购申请单/备注原文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierMatchVO {

    /** 物料 id */
    private Long goodsId;

    /** 物料名称 */
    private String goodsName;

    /** 规格 */
    private String spec;

    /** 匹配到的供应商 id */
    private Long supplierId;

    /** 匹配到的供应商名称 */
    private String supplierName;

    /** 数据来源：命中的采购申请单号（审计可溯） */
    private String sourceRequestNo;

    /** 数据来源：命中明细的到货备注原文 */
    private String sourceRemark;
}
