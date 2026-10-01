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

    /** 差异备注（需求二 Q16：RETURN 行退料量<已领未退时的损耗/丢失等原因，仓储确认收料时展示；其余行为空） */
    private String diffReason;

    private Integer quantity;

    private Integer sortNo;
}
