package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.mapper.DeviceHeartbeatLogMapper;
import com.xinjian.capsulecode.mapper.DeviceInfoMapper;
import com.xinjian.capsulecode.model.DeviceAliases;
import com.xinjian.capsulecode.model.DeviceHeartbeatLog;
import com.xinjian.capsulecode.model.DeviceInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/app/device")
public class DeviceVersionController {

    @Value("${app.apk-path}")
    private String apkPath;

    @Value("${app.aapt-path}")
    private String aaptPath;

    @Autowired
    private DeviceInfoMapper deviceInfoMapper;

    @Autowired
    private DeviceHeartbeatLogMapper deviceHeartbeatLogMapper;

    private static final Pattern AAPT_VERSION_CODE = Pattern.compile("versionCode='(\\d+)'");
    private static final Pattern AAPT_VERSION_NAME = Pattern.compile("versionName='([^']+)'");

    // deviceId -> 上报信息
    private static final Map<String, Map<String, Object>> deviceVersions = new ConcurrentHashMap<String, Map<String, Object>>();

    /** 返回所有已上报过版本的 deviceId 集合。 */
    public static java.util.Set<String> getAllDeviceIds() {
        return deviceVersions.keySet();
    }

    /** 返回服务端最新版本信息（供其他 Controller 使用）。 */
    public static Map<String, Object> getServerVersionInfo() {
        return lastServerVersionInfo;
    }

    private static volatile Map<String, Object> lastServerVersionInfo = new HashMap<>();

    /** 获取某设备上报的 manufacturer 字段，未上报返回 null。 */
    public static String getManufacturer(String deviceId) {
        Map<String, Object> info = deviceVersions.get(deviceId);
        if (info == null) return null;
        Object mfr = info.get("manufacturer");
        return mfr != null ? mfr.toString() : null;
    }

    /**
     * 按厂商名（大小写不敏感前缀匹配）查找最近上报过的 deviceId。
     * 例如：findDeviceIdByManufacturer("OnePlus") 可匹配 manufacturer="OnePlus" 的设备。
     */
    public static String findDeviceIdByManufacturer(String manufacturerKeyword) {
        if (manufacturerKeyword == null || manufacturerKeyword.isEmpty()) return null;
        String keyword = manufacturerKeyword.toLowerCase();
        String found = null;
        long latestTime = 0;
        for (Map.Entry<String, Map<String, Object>> entry : deviceVersions.entrySet()) {
            String mfr = String.valueOf(entry.getValue().getOrDefault("manufacturer", "")).toLowerCase();
            if (mfr.contains(keyword)) {
                long t = (long) entry.getValue().getOrDefault("reportedAt", 0L);
                if (t > latestTime) {
                    latestTime = t;
                    found = entry.getKey();
                }
            }
        }
        return found;
    }

    /** 启动时从数据库预加载所有设备信息到内存 */
    @PostConstruct
    public void loadFromDb() {
        try {
            List<DeviceInfo> list = deviceInfoMapper.findAll();
            for (DeviceInfo d : list) {
                Map<String, Object> info = new HashMap<String, Object>();
                info.put("deviceId", d.getDeviceId());
                info.put("versionCode", d.getVersionCode());
                info.put("versionName", d.getVersionName());
                info.put("manufacturer", d.getManufacturer());
                info.put("reportedAt", d.getLastReportedAt());
                deviceVersions.put(d.getDeviceId(), info);
            }
            System.out.println("[DeviceVersionController] 预加载设备数: " + list.size());
        } catch (Exception e) {
            System.err.println("[DeviceVersionController] 预加载设备信息失败: " + e.getMessage());
        }
        // 初始化服务端版本缓存
        initServerVersionInfo();
    }

    private void initServerVersionInfo() {
        int serverVersionCode = 0;
        String serverVersionName = "";
        try {
            Process p = new ProcessBuilder(aaptPath, "dump", "badging", apkPath)
                .redirectErrorStream(true)
                .start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            Matcher m2 = AAPT_VERSION_CODE.matcher(output);
            if (m2.find()) serverVersionCode = Integer.parseInt(m2.group(1));
            Matcher m3 = AAPT_VERSION_NAME.matcher(output);
            if (m3.find()) serverVersionName = m3.group(1);
        } catch (Exception e) {
            // Docker 分发场景下 APK 和 aapt 都不存在，属于正常情况，用 debug 级别避免刷屏
            log.debug("[DeviceVersionController] 读取服务端版本失败（Docker 分发正常现象）: {}", e.getMessage());
        }
        Map<String, Object> info = new HashMap<>();
        info.put("serverVersionCode", serverVersionCode);
        info.put("serverVersionName", serverVersionName);
        lastServerVersionInfo = info;
    }

    // POST /app/device/heartbeat?deviceId=xxx
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestParam String deviceId) {
        long now = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<String, Object>();
        try {
            deviceInfoMapper.updateHeartbeat(deviceId, now);
            // 写心跳历史日志
            DeviceHeartbeatLog logEntry = new DeviceHeartbeatLog();
            logEntry.setDeviceId(deviceId);
            logEntry.setHeartbeatAt(now);
            deviceHeartbeatLogMapper.insert(logEntry);
            result.put("ok", true);
        } catch (Exception e) {
            System.err.println("[DeviceVersionController] 心跳更新失败 deviceId=" + deviceId + ": " + e.getMessage());
            result.put("ok", false);
        }
        return result;
    }

    // POST /app/device/version?deviceId=xxx&versionCode=xxx&versionName=xxx&manufacturer=xxx
    @PostMapping("/version")
    public Map<String, Object> reportVersion(
            @RequestParam String deviceId,
            @RequestParam(required = false, defaultValue = "0") int versionCode,
            @RequestParam(required = false, defaultValue = "") String versionName,
            @RequestParam(required = false, defaultValue = "") String manufacturer) {
        long now = System.currentTimeMillis();
        Map<String, Object> info = new HashMap<String, Object>();
        info.put("deviceId", deviceId);
        info.put("versionCode", versionCode);
        info.put("versionName", versionName);
        info.put("manufacturer", manufacturer);
        info.put("reportedAt", now);
        deviceVersions.put(deviceId, info);

        // 持久化到数据库
        try {
            DeviceInfo d = new DeviceInfo();
            d.setDeviceId(deviceId);
            d.setAlias(DeviceAliases.resolve("MOBILE_" + deviceId));
            d.setManufacturer(manufacturer);
            d.setVersionCode(versionCode);
            d.setVersionName(versionName);
            d.setLastReportedAt(now);
            deviceInfoMapper.upsert(d);
        } catch (Exception e) {
            System.err.println("[DeviceVersionController] 持久化设备信息失败 deviceId=" + deviceId + ": " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("ok", true);
        return result;
    }

    // GET /app/device/versions → 所有已上报设备的版本 + 与最新 APK 对比结果
    @GetMapping(value = "/versions", produces = "application/json;charset=UTF-8")
    public Map<String, Object> getVersions() {
        // 刷新并读取服务端最新 APK 版本
        initServerVersionInfo();
        int serverVersionCode = (int) lastServerVersionInfo.getOrDefault("serverVersionCode", 0);
        String serverVersionName = (String) lastServerVersionInfo.getOrDefault("serverVersionName", "");

        // 构建每台设备的对比结果
        List<Map<String, Object>> devices = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : deviceVersions.entrySet()) {
            String deviceId = entry.getKey();
            Map<String, Object> info = entry.getValue();
            int deviceVersionCode = (int) info.getOrDefault("versionCode", 0);
            boolean isLatest = serverVersionCode > 0 && deviceVersionCode >= serverVersionCode;

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("deviceId", deviceId);
            item.put("alias", DeviceAliases.resolve("MOBILE_" + deviceId));
            item.put("manufacturer", info.get("manufacturer"));
            item.put("versionCode", deviceVersionCode);
            item.put("versionName", info.get("versionName"));
            item.put("isLatest", isLatest);
            item.put("reportedAt", info.get("reportedAt"));
            devices.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serverVersionCode", serverVersionCode);
        result.put("serverVersionName", serverVersionName);
        result.put("devices", devices);
        return result;
    }

    // GET /app/device/heartbeat-history?since={epochMs}&until={epochMs}
    // since 缺失时 Spring 自动返回 400 MissingServletRequestParameterException
    @GetMapping("/heartbeat-history")
    public ResponseEntity<Map<String, List<Long>>> getHeartbeatHistory(
            @RequestParam long since,
            @RequestParam(required = false) Long until) {
        long untilMs = (until != null) ? until : System.currentTimeMillis();

        List<DeviceHeartbeatLog> logs = deviceHeartbeatLogMapper.selectByTimestampRange(since, untilMs);

        // 按 deviceId 分组，只保留时间戳列表
        Map<String, List<Long>> result = new LinkedHashMap<>();
        for (DeviceHeartbeatLog log : logs) {
            result.computeIfAbsent(log.getDeviceId(), k -> new ArrayList<>())
                  .add(log.getHeartbeatAt());
        }
        return ResponseEntity.ok(result);
    }
}
