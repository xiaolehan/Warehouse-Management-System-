package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成品拆分单明细表（ADR-0020）：发起时 BOM×拆分数量快照，物料回流实际量走 RETURN 单。
 */
@Data
@TableName("biz_split_order_detail")
public class BizSplitOrderDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long splitOrderId;

    private Long goodsId;

    private String goodsName;

    private String spec;

    private String material;

    /**
     * 需求量(BOM×拆分数量)
     */
    private Integer requiredQuantity;

    private Integer sortNo;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
