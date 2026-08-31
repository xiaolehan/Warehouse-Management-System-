package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BOM 主表（D41/D45）：成品 -> 一组物料明细，生产研发部维护。
 */
@Data
@TableName("biz_bom")
public class BizBom {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** BOM 编码(如 PTO153-BOM) */
    private String bomCode;

    /** 成品 goods_id（type=product） */
    private Long goodsId;

    /** 成品名称(冗余) */
    private String goodsName;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}