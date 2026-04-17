package com.xinjian.capsulecode.model;

public class ClaudeConversation {
    private Long id;
    private String deviceId;
    private String convId;
    private String name;
    private String claudeSessionId;
    private long createdAt;
    private long lastActiveAt;
    private boolean processing;
    private boolean enterpriseMode;
    private String accountMode; // max / pro / enterprise
    private String workDir;     // Claude CLI cwd；null 时 fallback claude.work-dir 全局配置

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getConvId() { return convId; }
    public void setConvId(String convId) { this.convId = convId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(long lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public boolean isProcessing() { return processing; }
    public void setProcessing(boolean processing) { this.processing = processing; }
    public boolean isEnterpriseMode() { return enterpriseMode; }
    public void setEnterpriseMode(boolean enterpriseMode) { this.enterpriseMode = enterpriseMode; }
    public String getAccountMode() { return accountMode != null ? accountMode : "max"; }
    public void setAccountMode(String accountMode) { this.accountMode = accountMode; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
}
