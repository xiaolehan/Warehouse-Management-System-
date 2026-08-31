package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产任务单（D42 齐套预警）：一张单 = 一个成品 × 生产数量。
 * 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废。
 * 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料（阻断开工）。
 */
@Data
@TableName("biz_production_order")
public class BizProductionOrder {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_IN_PROGRESS = 2;
    public static final int STATUS_AWAIT_QC = 3;
    public static final int STATUS_DONE = 4;
    public static final int STATUS_VOIDED = 5;
    public static final int STATUS_SCRAPPED = 6;

    public static final String KIT_OK = "ok";
    public static final String KIT_PARTIAL = "partial";
    public static final String KIT_BLOCK = "block";

    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** 成品 goods_id(type=product) */
    private Long goodsId;

    private String goodsName;

    private String unit;

    /** 生产数量 */
    private Integer quantity;

    /** 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废 */
    private Integer status;

    /** 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料 */
    private String kitStatus;

    /** 来源: MANUAL-手动创建 */
    private String source;

    /** 工序清单快照(8道装配工序静态 SOP，打印用) */
    private String processSnapshot;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}