package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_purchase_request_detail")
public class BizPurchaseRequestDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long requestId;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    /**
     * 到货数量(采购到货提交时填写，确认入库按此数量加库存)
     */
    private Integer arriveQuantity;

    /**
     * 采购单价(入库时填写)
     */
    private BigDecimal unitPrice;

    private Integer sortNo;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
