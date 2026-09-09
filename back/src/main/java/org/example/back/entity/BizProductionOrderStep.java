package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产工序实例（D64 打卡追踪）：仅 7 道人工装配工序落库；
 * 第 6 首次测试/8 成品测试/10 成品入库由质检记录与订单状态实时推导展示，不在此表。
 */
@Data
@TableName("biz_production_order_step")
public class BizProductionOrderStep {

    public static final int STATUS_UNDONE = 0;
    public static final int STATUS_DONE = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 生产任务单 id */
    private Long orderId;

    /** 工序序号(人工工序: 1/2/3/4/5/7/9) */
    private Integer stepNo;

    /** 工序名称(建单时快照) */
    private String stepName;

    /** 完成状态: 0-未完成, 1-已完成 */
    private Integer status;

    /** 打卡人 id(完成时) */
    private Long operatorId;

    /** 打卡人姓名 */
    private String operatorName;

    /** 打卡时间 */
    private LocalDateTime operateTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
