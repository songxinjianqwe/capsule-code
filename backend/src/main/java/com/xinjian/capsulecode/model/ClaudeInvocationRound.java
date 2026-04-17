package com.xinjian.capsulecode.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClaudeInvocationRound {
    private Long id;
    private Long invocationId;
    private Integer roundIndex;
    private String requestMessages;          // JSON
    private String responseContent;          // JSON
    private String stopReason;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cacheReadInputTokens;
    private Integer cacheCreationInputTokens;
    private String toolName;
    private String toolInput;                // JSON
    private String toolResult;               // JSON
    private Integer durationMs;
    private LocalDateTime createdAt;
}
