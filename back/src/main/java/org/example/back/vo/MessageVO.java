package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;

    private String title;

    private String content;

    private Boolean read;

    /** D21 业务绑定：前端按 bizType 映射跳转待处理页，可为空（公告类消息） */
    private String bizType;

    private Long bizId;

    /** D75 消息跳转目标（发送方显式指定；为空前端按 bizType 映射兜底） */
    private String targetRoute;

    private LocalDateTime readTime;

    private LocalDateTime createTime;
}