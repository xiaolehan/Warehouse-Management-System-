package org.example.back.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PurchaseRequestDetailVO {

    private Long id;

    private Long requestId;

    private Long goodsId;

    /** 对应BOM明细id */
    private Long bomDetailId;

    private String goodsName;

    /** 规格/材质/备注快照（D60：生产补料提交时自 BOM 行带入） */
    private String spec;

    private String material;

    private String remark;

    /** 新物料标记（D60）：0-已有物料缺口，1-未知物料自动建档 */
    private Integer isNewMaterial;

    private Integer quantity;

    /** 预计到货时间（采购认领时按行填写，采购中可改；D61） */
    private LocalDateTime expectedArrivalTime;

    /** 到货备注（供应商/发货方式等采购口径，与 remark 物料描述快照独立；D61） */
    private String arrivalRemark;

    private Integer arriveQuantity;

    private BigDecimal unitPrice;

    /** D131 行级供应商ID（到货提交时选定；前端按供应商 options 映射名称展示） */
    private Long supplierId;

    /** D120 行级接收状态：1-待到货, 2-本批待入库确认, 3-已入库 */
    private Integer receiveStatus;

    /** D120 到货批次号（同批同号，如 B1/B2） */
    private String arriveBatchNo;

    /** D120 本批到货提交时间 */
    private LocalDateTime arriveBatchTime;

    /** D120 本批入库确认时间 */
    private LocalDateTime receiveBatchTime;

    private Integer sortNo;
}
