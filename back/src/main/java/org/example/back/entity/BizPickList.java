package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_pick_list")
public class BizPickList {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String pickNo;

    /**
     * 领料类型: PICK-领料, SUPPLY-补料, RETURN-退料
     */
    private String pickType;

    /**
     * 关联销售单ID(可选)
     */
    private Long sourceSalesId;

    /**
     * 状态: 1-待发料, 2-已发料, 3-已完成, 4-已驳回
     */
    private Integer status;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime confirmTime;

    private String rejectReason;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
