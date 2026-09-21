package org.example.back.vo;

import lombok.Data;

import java.util.List;

/**
 * D113：批量下达预览——销售单头信息 + 全部明细行的可下达性标注。
 * 每行给成品名、订单行数量、当前库存、BOM 状态、在途任务单单号与已生产数量，
 * 前端据此禁选/预填生产数量。
 */
@Data
public class ProductionReleasePreviewVO {

    private Long salesId;
    private String salesNo;
    private String customerName;
    private List<PreviewLineVO> lines;

    @Data
    public static class PreviewLineVO {

        private Long salesDetailId;
        private Long goodsId;
        private String goodsName;
        /** 订单行数量 */
        private Integer quantity;
        /** 成品当前库存 */
        private Integer stock;
        /** 是否已建 BOM（无则建议先建档，行禁选） */
        private Boolean hasBom;
        /** 该行已有在途任务单（任一）单号——非空则禁选 */
        private String inFlightOrderNo;
        /** 该行已完成生产数量（非作废/终止的 DONE 单合计） */
        private Integer doneQuantity;
    }
}
