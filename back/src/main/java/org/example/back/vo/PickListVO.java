package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PickListVO {

    private Long id;

    private String pickNo;

    /**
     * 领料类型: PICK-领料, SUPPLY-补料, RETURN-退料
     */
    private String pickType;

    /**
     * 类型文本
     */
    private String pickTypeText;

    private Long sourceSalesId;

    /**
     * 状态: 1-待发料, 2-已发料, 3-已完成, 4-已驳回
     */
    private Integer status;

    /**
     * 状态文本
     */
    private String statusText;

    private Long applicantId;

    private String applicantName;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime confirmTime;

    private String rejectReason;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;

    /**
     * 明细列表
     */
    private List<PickListDetailVO> details;
}
