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

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    /**
     * 预计到货时间（采购认领时填写）
     */
    private LocalDateTime expectedArrivalTime;

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

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
