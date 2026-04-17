package com.xinjian.capsulecode.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClaudeInvocation {
    private Long id;
    private Long triggerMsgId;
    private String triggerDeviceId;
    private Long replyMsgId;
    private Integer totalRounds;
    private String status; // success / error / timeout
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
