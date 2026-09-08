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

    /** 对应BOM明细id(生产补料来源行定位；D60建档即回绑) */
    private Long bomDetailId;

    private String goodsName;

    /** 规格/材质/备注快照（D60：生产补料提交时自 BOM 行带入，供采购采购与详情分组） */
    private String spec;

    private String material;

    private String remark;

    /** 新物料标记（D60）：0-已有物料缺口，1-未知物料自动建档 */
    private Integer isNewMaterial;

    private Integer quantity;

    /**
     * 预计到货时间(采购认领时按行填写,采购中可改;D61)
     */
    private LocalDateTime expectedArrivalTime;

    /**
     * 到货备注(供应商/发货方式等采购口径,与remark物料描述快照独立;D61)
     */
    private String arrivalRemark;

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
