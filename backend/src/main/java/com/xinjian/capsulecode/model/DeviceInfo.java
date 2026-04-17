package com.xinjian.capsulecode.model;

public class DeviceInfo {
    private String deviceId;
    private String alias;
    private String manufacturer;
    private int versionCode;
    private String versionName;
    private long lastReportedAt;
    private Long lastHeartbeatAt;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }

    public int getVersionCode() { return versionCode; }
    public void setVersionCode(int versionCode) { this.versionCode = versionCode; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public long getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(long lastReportedAt) { this.lastReportedAt = lastReportedAt; }

    public Long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Long lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
}
