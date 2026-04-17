package com.xinjian.capsulecode.model;

import com.xinjian.capsulecode.controller.DeviceVersionController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DeviceAliases {

    // 厂商关键字 -> 别名（大小写不敏感）
    private static final Map<String, String> MANUFACTURER_ALIAS = new HashMap<>();

    static {
        MANUFACTURER_ALIAS.put("oneplus", "一加");
        MANUFACTURER_ALIAS.put("xiaomi", "小米");
        MANUFACTURER_ALIAS.put("oppo", "OPPO");
        MANUFACTURER_ALIAS.put("vivo", "vivo");
        MANUFACTURER_ALIAS.put("huawei", "华为");
        MANUFACTURER_ALIAS.put("honor", "荣耀");
        MANUFACTURER_ALIAS.put("samsung", "三星");
    }

    /**
     * 根据 deviceId（含 MOBILE_ 前缀）解析别名。
     * 优先从运行时上报的 manufacturer 动态解析；若未上报则返回 deviceId 本身。
     */
    public static String resolve(String deviceId) {
        if ("PC".equals(deviceId)) return "PC";
        String rawId = deviceId.startsWith("MOBILE_") ? deviceId.substring(7) : deviceId;
        // 从运行时上报数据中查 manufacturer
        String mfr = DeviceVersionController.getManufacturer(rawId);
        if (mfr != null && !mfr.isEmpty()) {
            String mfrLower = mfr.toLowerCase();
            for (Map.Entry<String, String> entry : MANUFACTURER_ALIAS.entrySet()) {
                if (mfrLower.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return mfr; // 未知厂商直接返回原始值
        }
        return deviceId;
    }

    /**
     * 返回所有已上报设备的 Map（含 MOBILE_ 前缀的 deviceId -> 别名）。
     * 供 PushController 等枚举设备列表使用。
     */
    public static Map<String, String> getMobileDevices() {
        Map<String, String> result = new HashMap<>();
        for (String deviceId : DeviceVersionController.getAllDeviceIds()) {
            String fullId = "MOBILE_" + deviceId;
            result.put(fullId, resolve(fullId));
        }
        return result;
    }

    /**
     * 按别名（如"一加"、"小米"）找到对应 deviceId（不含 MOBILE_ 前缀）。
     * 用于 ElectricityService 等需要按品牌定位设备的场景。
     */
    public static String findDeviceIdByAlias(String alias) {
        // 找到对应的厂商关键字
        String targetKeyword = null;
        for (Map.Entry<String, String> entry : MANUFACTURER_ALIAS.entrySet()) {
            if (entry.getValue().equals(alias)) {
                targetKeyword = entry.getKey();
                break;
            }
        }
        if (targetKeyword == null) return null;
        return DeviceVersionController.findDeviceIdByManufacturer(targetKeyword);
    }
}
