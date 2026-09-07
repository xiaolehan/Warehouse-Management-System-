package org.example.back.vo;

import lombok.Data;

/**
 * 生产申请领料：单行物料（goodsId + 需求数量，数量由后端按 BOM×生产数量锁定，前端只读）。
 */
@Data
public class ProductionPickItemVO {
    private Long goodsId;
    private String goodsName;
    private Integer quantity;
}
