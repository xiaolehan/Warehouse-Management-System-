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

    /** 应退量快照（会话68/D138，ADR-0022）：RETURN 行建单时点的「应该退多少」参照量；该功能前历史行为 NULL（前端显示「—」） */
    private Integer expectedQuantity;

    private Integer quantity;

    private Integer sortNo;
}
