package org.example.back.vo;

import lombok.Data;

import java.util.List;

/**
 * D73：终止弹窗的「已领未退」预览。items=净额明细（PICK/SUPPLY 已发料起 − RETURN 已发料起，按物料分组取 >0）；
 * hasOpenReturn=true 时终止将跳过自动生成退料单（前端提示人工在既有退料单中核对覆盖）。
 */
@Data
public class ProductionReturnableVO {
    private List<ProductionPickItemVO> items;
    private Boolean hasOpenReturn;
    private String openReturnPickNo;
}
