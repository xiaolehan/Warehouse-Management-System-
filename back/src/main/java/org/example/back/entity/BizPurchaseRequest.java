package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_purchase_request")
public class BizPurchaseRequest {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String requestNo;

    /**
     * 状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回, 5-待入库确认
     */
    private Integer status;

    /**
     * 销售冻结豁免(D114): 0-不豁免, 1-生产终止未勾选撤销→豁免销售冻结(采购可继续认领/到货/入库)
     */
    private Integer freezeExempt;

    /** 来源: production-生产缺料补料, warehouse-仓储手动 */
    private String sourceType;

    /** 来源生产任务单id(仅production来源有值) */
    private Long productionOrderId;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    /**
     * 采购到货提交时间（采购管理员提交入库申请时）
     */
    private LocalDateTime arriveTime;

    private LocalDateTime receiveTime;

    /**
     * 入库确认人ID（仓储管理员）
     */
    private Long confirmerId;

    /**
     * 入库确认人姓名
     */
    private String confirmerName;

    /**
     * 仓储确认入库时间
     */
    private LocalDateTime confirmTime;

    private String rejectReason;

    /**
     * 会话 67（决策 1a）：撤销原因——一键撤销自动生成（关联销售已取消）/申请人自行撤销
     */
    private String revokeReason;

    /**
     * 撤销人ID（申请人本人或生产管理员）
     */
    private Long revokerId;

    /**
     * 撤销人姓名
     */
    private String revokerName;

    /**
     * 撤销时间
     */
    private LocalDateTime revokeTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
