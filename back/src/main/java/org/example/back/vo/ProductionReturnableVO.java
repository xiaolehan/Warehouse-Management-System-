package org.example.back.vo;

import lombok.Data;

import java.util.List;

/**
 * D73：终止弹窗的「已领未退」预览。items=净额明细（PICK/SUPPLY 已发料起 − RETURN 已发料起，按物料分组取 >0）；
 * hasOpenReturn=true 时终止将跳过自动生成退料单（前端提示人工在既有退料单中核对覆盖）。
 * 需求二（Q10a/Q13）：仅关联销售单且行未终止时回填现货信息——goodsStock=成品现货、salesLineQuantity=订单行数量、
 * stockSufficient=现货≥订单量（派生不落库）；不足时前端展示缺口警告。
 * 需求二（Q17）：inFlightPurchaseNos=该任务单在途补料采购申请单号（未入库/未终态），终止弹窗黄条提示生产自行决定是否撤销。
 */
@Data
public class ProductionReturnableVO {
    private List<ProductionPickItemVO> items;
    private Boolean hasOpenReturn;
    private String openReturnPickNo;

    /** 需求二 Q10a/Q13：成品现货库存（仅关联销售单且行未终止时回填） */
    private Integer goodsStock;
    /** 需求二 Q13：关联销售明细行数量（订单量，用于缺口警告） */
    private Integer salesLineQuantity;
    /** 需求二 Q13：现货是否满足订单量（派生值，goodsStock 未回填时为 null） */
    private Boolean stockSufficient;
    /** 需求二 Q13：关联销售单号（现货提示文案用） */
    private String salesOrderNo;
    /** 需求二 Q17：在途补料采购申请单号列表（生产自行决定是否撤销，系统不代撤） */
    private List<String> inFlightPurchaseNos;
}
