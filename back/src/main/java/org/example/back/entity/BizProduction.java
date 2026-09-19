package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产入库单：仓储管理员将自己生产的零件存入仓库，库存增加。
 * D107 两段式：生产端「提交入库申请」生成 confirm_status=1 待确认记录（不加库存），
 * 仓储管理员「确认入库」才增加库存；仓储手动新增直接 confirm_status=2（录入即验收）。
 */
@Data
@TableName("biz_production")
public class BizProduction {

    /** D107 确认状态: 1-待仓库确认, 2-已确认入库, 3-已驳回 */
    public static final int CONFIRM_PENDING = 1;
    public static final int CONFIRM_CONFIRMED = 2;
    public static final int CONFIRM_REJECTED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String productionNo;

    /** D107：来源生产任务单 id（生产端提交入库申请时写入；仓储手动新增为空） */
    private Long productionOrderId;

    private Long goodsId;

    private String goodsName;

    private Integer quantity;

    /**
     * 生产单价（可选，自产零件成本可能未知）
     */
    private BigDecimal unitPrice;

    /**
     * 总金额（unitPrice 为空时为空）
     */
    private BigDecimal totalPrice;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private String remark;

    /**
     * 1-正常, 2-已作废, 3-红冲单
     */
    private Integer bizStatus;

    /** D107 确认状态: 1-待仓库确认, 2-已确认入库, 3-已驳回（存量行迁移时回填 2） */
    private Integer confirmStatus;

    /** D107：确认人（确认入库/驳回时写；手动新增=录入人） */
    private Long confirmerId;

    private String confirmerName;

    /** D107：确认/驳回时间 */
    private LocalDateTime confirmTime;

    /** D107：驳回原因（confirm_status=3 时有值） */
    private String rejectReason;

    /**
     * 红冲来源单ID
     */
    private Long sourceId;

    private LocalDateTime voidTime;

    private String voidReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
