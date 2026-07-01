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
     * 采购单价(入库时填写)
     */
    private BigDecimal unitPrice;

    private Integer sortNo;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
