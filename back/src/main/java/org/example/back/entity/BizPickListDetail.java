package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_pick_list_detail")
public class BizPickListDetail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long pickListId;

    private Long goodsId;

    private String goodsName;

    /** 规格快照（建单时自物料主数据带入，D63） */
    private String spec;

    /** 材质快照（建单时自物料主数据带入，D63） */
    private String material;

    /** 备注快照（建单时自物料主数据描述带入，D63） */
    private String remark;

    private Integer quantity;

    private Integer sortNo;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}
