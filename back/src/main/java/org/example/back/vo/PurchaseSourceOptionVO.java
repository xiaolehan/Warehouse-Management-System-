package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退货来源进货单选项（D111）：按来源进货单分组，明细行带行级可退量。
 */
@Data
public class PurchaseSourceOptionVO {

    /** 来源进货单 id（biz_purchase.id） */
    private Long id;

    private String purchaseNo;

    private LocalDateTime operationTime;

    /** D111：该单的明细行（行级可退量 = 行入库量 - 该行被有效退货累计） */
    private List<PurchaseSourceOptionLineVO> lines;
}
