package org.example.back.vo;

import lombok.Data;

@Data
public class PickListDetailVO {

    private Long id;

    private Long pickListId;

    private Long goodsId;

    private String goodsName;

    /** 规格快照；历史行无快照时由服务层兜底实时读主数据补显（D63） */
    private String spec;

    private String material;

    private String remark;

    private Integer quantity;

    private Integer sortNo;
}
