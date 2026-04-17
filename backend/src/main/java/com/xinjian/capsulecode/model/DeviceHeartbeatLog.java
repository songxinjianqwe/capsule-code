package com.xinjian.capsulecode.model;

public class DeviceHeartbeatLog {
    private Long id;
    private String deviceId;
    private long heartbeatAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public long getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(long heartbeatAt) { this.heartbeatAt = heartbeatAt; }
}
