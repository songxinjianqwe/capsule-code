package com.xinjian.capsulecode.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备别名 → ADB serial 映射，对应 application.yml 的 devices.*。
 * 统一在此处维护，各功能模块通过别名查 serial，不在业务代码里硬编码设备 ID。
 */
@Component
@ConfigurationProperties(prefix = "devices")
public class DeviceProperties {

    private static final Logger log = LoggerFactory.getLogger(DeviceProperties.class);

    /** adb 可执行文件路径 */
    private String adbPath = "adb";

    /** 别名 → ADB serial 映射，对应 devices.adb-serials */
    private Map<String, String> adbSerials = new HashMap<>();

    public String getAdbPath() { return adbPath; }
    public void setAdbPath(String adbPath) { this.adbPath = adbPath; }

    public Map<String, String> getAdbSerials() { return adbSerials; }
    public void setAdbSerials(Map<String, String> adbSerials) { this.adbSerials = adbSerials; }

    /**
     * 根据别名查 ADB serial，并用 `adb devices` 校验该 serial 当前是否已连接。
     *
     * @param alias 设备别名，如"小米"，必须在 devices.adb-serials 中配置
     * @return ADB serial（已确认在线），或 null（未配置 / serial 为空 / 设备未连接 / adb 不可用）
     */
    public String resolveAndValidateSerial(String alias) {
        String serial = adbSerials.get(alias);
        if (serial == null || serial.isBlank()) {
            log.warn("resolveAndValidateSerial: alias '{}' 未配置 ADB serial", alias);
            return null;
        }
        try {
            Process p = new ProcessBuilder(adbPath, "devices")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            // adb devices 输出每行格式：<serial>\t<state>，state=device 表示已连接
            boolean online = output.lines()
                    .anyMatch(line -> line.startsWith(serial + "\t") && line.contains("device"));
            if (!online) {
                log.warn("resolveAndValidateSerial: serial '{}' (alias='{}') 未在 adb devices 中找到已连接设备", serial, alias);
                return null;
            }
            log.debug("resolveAndValidateSerial: alias='{}' serial='{}' 已连接", alias, serial);
            return serial;
        } catch (Exception e) {
            log.warn("resolveAndValidateSerial: adb 调用失败，无法校验 alias='{}': {}", alias, e.getMessage());
            return null;
        }
    }
}
