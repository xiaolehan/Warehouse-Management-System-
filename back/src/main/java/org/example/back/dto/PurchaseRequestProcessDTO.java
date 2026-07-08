package org.example.back.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购认领 DTO：采购管理员认领采购申请单时填写预计到货时间（可选）。
 */
@Data
public class PurchaseRequestProcessDTO {

    /**
     * 预计到货时间（可选，采购认领时填写）
     */
    private LocalDateTime expectedArrivalTime;
}
