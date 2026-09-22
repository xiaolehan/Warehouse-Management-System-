package org.example.back.vo;

import lombok.Data;

/**
 * D129：物料「最新供应商」批量查询结果行（GET /base/goods/latest-suppliers）。
 * 口径同商品资料页「最新供应商」列（行级→头级→绑定+「默认」，D131/ADR-0018）。
 */
@Data
public class GoodsLatestSupplierVO {

    private Long goodsId;

    /** 解析出的最新供应商（无有效进货记录时=绑定供应商，可能为「系统默认供应商」锚点 id=1） */
    private Long supplierId;

    private String supplierName;

    /** true=无有效进货记录，回退绑定供应商（前端标灰色「默认」tag） */
    private Boolean isDefault;

    /** 物料绑定供应商 id（D131 到货提交预填用；=1 锚点时前端留空必选手选） */
    private Long bindingSupplierId;
}
