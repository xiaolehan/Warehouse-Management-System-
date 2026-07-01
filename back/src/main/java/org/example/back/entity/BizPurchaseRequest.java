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
     * 状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回
     */
    private Integer status;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime receiveTime;

    private String rejectReason;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
