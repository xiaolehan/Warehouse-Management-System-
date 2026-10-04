package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * D73：生产任务单手动终止入参。reason 必填（审计留痕）；
 * items 为终止退料明细（已领未退净额预填后生产管理员可改），空=无待退物料仅终止。
 * D114：revokePurchases 三值口径——true=终止并同事务撤销在途补料采购申请；
 * false=终止不撤销，在途补料申请豁免销售冻结（采购可继续）；null=旧客户端未传（不处理采购申请）。
 */
@Data
public class ProductionTerminateDTO {

    @NotBlank(message = "终止原因不能为空")
    private String reason;

    @Valid
    private List<ProductionReturnItemDTO> items;

    /**
     * D114：是否一并撤销在途补料采购申请（待采购/采购中）。
     * 勾选撤销=true；不勾选（仍需采购）=false（豁免冻结）；旧客户端不传=null（不处理）。
     */
    private Boolean revokePurchases;
}
