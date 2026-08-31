package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产质检记录（D40）：首测/成品测两个测点，追加式记录。
 * 最新一条 result=OK 则测点通过；NG 需处置：返工(REWORK 后重测) 或 报废(SCRAP)。
 * 两个测点全部最新 OK 才允许生产入库。
 */
@Data
@TableName("biz_production_qc")
public class BizProductionQc {

    public static final String POINT_FIRST = "first";
    public static final String POINT_FINAL = "final";

    public static final String RESULT_OK = "OK";
    public static final String RESULT_NG = "NG";

    public static final String DISP_REWORK = "REWORK";
    public static final String DISP_SCRAP = "SCRAP";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long goodsId;

    private String goodsName;

    /** 测点: first-首测, final-成品测 */
    private String testPoint;

    private Long testerId;

    private String testerName;

    /** 结果: OK-合格, NG-不合格 */
    private String result;

    /** NG 原因/备注 */
    private String reason;

    /** 处置: REWORK-返工, SCRAP-报废 */
    private String disposition;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;
}