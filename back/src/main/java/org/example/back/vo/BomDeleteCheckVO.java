package org.example.back.vo;

import lombok.Data;

/**
 * D66：BOM 删除前检查——前端据 unfinishedOrderCount 决定是否弹「未完结任务单」软保护确认。
 */
@Data
public class BomDeleteCheckVO {

    /** 该成品名下未完结（待生产/生产中/待入库）生产任务单数 */
    private Long unfinishedOrderCount;

    /** 成品名称（确认弹窗展示用） */
    private String goodsName;
}
