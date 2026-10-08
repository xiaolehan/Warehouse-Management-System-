package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_approval_order")
public class BizApprovalOrder {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String approvalNo;

    private String bizType;

    private Long bizId;

    private String bizNo;

    private String requestAction;

    private String requestReason;

    /**
     * 价格偏离行快照(JSON,会话68/D137)：建单时点的本次售价/标准售价/偏离%对照，
     * 详情弹窗按行展示；与 before/after_biz_snapshot（作废类业务单据快照）语义不同
     */
    private String requestDetail;

    /**
     * 提交申请时的业务单据状态快照
     */
    private Integer beforeBizStatus;

    /**
     * 提交申请时的业务单据详情快照(JSON)
     */
    private String beforeBizSnapshot;

    /**
     * 审批结束时的业务单据状态快照
     */
    private Integer afterBizStatus;

    /**
     * 审批结束时的业务单据详情快照(JSON)
     */
    private String afterBizSnapshot;

    /**
     * 1-待审批, 2-已通过, 3-已驳回, 4-处理中
     */
    private Integer status;

    private Long requesterId;

    private String requesterName;

    private String requesterRole;

    private Long approverId;

    private String approverName;

    private String approveRemark;

    private LocalDateTime approvedAt;

    private LocalDateTime rejectedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
