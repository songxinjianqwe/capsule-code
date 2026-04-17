package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.controller.DeviceVersionController;
import com.xinjian.capsulecode.mapper.DeviceInfoMapper;
import com.xinjian.capsulecode.model.DeviceAliases;
import com.xinjian.capsulecode.model.DeviceInfo;
import com.xinjian.capsulecode.websocket.PushWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/push")
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    @Autowired
    private PushWebSocketHandler pushWebSocketHandler;

    @Autowired
    private DeviceInfoMapper deviceInfoMapper;

    /** 获取所有已知设备及在线状态、版本信息、心跳时间 */
    @GetMapping("/devices")
    public List<Map<String, Object>> getDevices() {
        Set<String> onlineIds = pushWebSocketHandler.getOnlineDeviceIds();

        // 从数据库取设备详情，以 deviceId（不含 MOBILE_ 前缀）为 key
        Map<String, DeviceInfo> dbMap = new HashMap<>();
        for (DeviceInfo d : deviceInfoMapper.findAll()) {
            dbMap.put(d.getDeviceId(), d);
        }

        // 获取服务端最新版本信息
        Map<String, Object> serverVersions = DeviceVersionController.getServerVersionInfo();
        int serverVersionCode = (int) serverVersions.getOrDefault("serverVersionCode", 0);
        String serverVersionName = (String) serverVersions.getOrDefault("serverVersionName", "");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : DeviceAliases.getMobileDevices().entrySet()) {
            String fullDeviceId = entry.getKey(); // MOBILE_xxx
            String rawDeviceId = fullDeviceId.startsWith("MOBILE_") ? fullDeviceId.substring(7) : fullDeviceId;
            DeviceInfo db = dbMap.get(rawDeviceId);

            Map<String, Object> item = new HashMap<>();
            item.put("deviceId", rawDeviceId);
            item.put("name", entry.getValue());
            item.put("online", onlineIds.contains(fullDeviceId));
            item.put("versionName", db != null ? db.getVersionName() : null);
            item.put("versionCode", db != null ? db.getVersionCode() : null);
            item.put("isLatest", db != null && serverVersionCode > 0 && db.getVersionCode() >= serverVersionCode);
            item.put("serverVersionName", serverVersionName);
            item.put("manufacturer", db != null ? db.getManufacturer() : null);
            item.put("lastHeartbeatAt", db != null ? db.getLastHeartbeatAt() : null);
            item.put("lastReportedAt", db != null ? db.getLastReportedAt() : null);
            result.add(item);
        }
        return result;
    }

    /** 向指定设备发送推送，deviceId="all" 时广播给所有在线设备 */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, String> body) {
        String deviceId = body.get("deviceId");
        String title = body.getOrDefault("title", "Capsule");
        String content = body.getOrDefault("body", "");

        Map<String, Object> result = new HashMap<>();
        try {
            if ("all".equals(deviceId)) {
                pushWebSocketHandler.pushToAll(title, content);
                result.put("success", true);
                result.put("message", "已广播给所有在线设备");
            } else {
                boolean ok = pushWebSocketHandler.pushToDevice(deviceId, title, content);
                result.put("success", ok);
                result.put("message", ok ? "推送成功" : "设备不在线");
            }
        } catch (Throwable t) {
            // 捕获所有 Throwable（含 Error），防止 Spring processDispatchResult 触发 classloading bug
            log.error("[Push] send failed: deviceId={}", deviceId, t);
            result.put("success", false);
            result.put("message", "推送失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return result;
    }
}
