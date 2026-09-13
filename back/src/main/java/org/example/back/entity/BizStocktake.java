package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_stocktake")
public class BizStocktake {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String stocktakeNo;

    /**
     * 状态: 1-盘点中, 2-待审核, 3-已完成, 4-已取消
     */
    private Integer status;

    private String remark;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private LocalDateTime submitTime;

    /**
     * 提交人（D85：admin+员工均可提交，与审核人分离留痕）
     */
    private Long submitterId;

    private String submitterName;

    private Long reviewerId;

    private String reviewerName;

    private LocalDateTime reviewTime;

    private String rejectReason;

    private String cancelReason;

    /**
     * 取消人（D85）
     */
    private Long cancelerId;

    private String cancelerName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
