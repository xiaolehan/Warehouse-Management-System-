package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单质检状态快照（D40 首测/成品测）。
 */
@Data
public class QcStateVO {

    /** 订单 id */
    private Long orderId;

    /** 是否整体质检通过（首测+成品测最新均为 OK 且非报废） */
    private Boolean passed;

    /** 是否已报废 */
    private Boolean scrapped;

    /** 首测状态 */
    private String firstStatus;

    private String firstStatusText;

    private Boolean firstPassed;

    /** 成品测状态 */
    private String finalStatus;

    private String finalStatusText;

    private Boolean finalPassed;

    /** D125：首测解锁——工序 1-5 全部打卡（false 时质检页禁选首测） */
    private Boolean firstUnlocked;

    /** D125：成品测解锁——工序 7 已打卡（false 时质检页禁选成品测） */
    private Boolean finalUnlocked;

    /** 质检记录历史 */
    private List<QcRecordVO> records;

    @Data
    public static class QcRecordVO {
        private Long id;
        private String testPoint;
        private String testPointText;
        private String testerName;
        private String result;
        private String reason;
        private String disposition;
        private LocalDateTime createTime;
    }
}