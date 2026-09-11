package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_message")
public class SysMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long recipientUserId;

    private Long recipientDeptId;

    private String title;

    private String content;

    private Integer isRead;

    private LocalDateTime readTime;

    /**
     * 关联业务类型: sales/sales_return 等(用于按单据撤销待办)
     */
    private String bizType;

    /**
     * 关联业务单据ID
     */
    private Long bizId;

    /** D75 消息跳转目标：前端路由字面值，接收方点击跳该列表页；空则前端按 bizType 映射兜底 */
    private String targetRoute;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
}