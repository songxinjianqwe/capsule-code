package com.xinjian.capsulecode.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * capsule-code Android app 的 OTA 入口。
 *
 * /app/version → aapt 读 APK 的 versionCode / versionName / size
 * /app/apk     → 下发 APK 二进制（同步写出）
 */
@Slf4j
@RestController
@RequestMapping("/app")
public class AppController {

    @Value("${capsule-code.app.apk-path:}")
    private String apkPath;

    @Value("${capsule-code.app.aapt-path:aapt}")
    private String aaptPath;

    private static final Pattern AAPT_VERSION_CODE = Pattern.compile("versionCode='(\\d+)'");
    private static final Pattern AAPT_VERSION_NAME = Pattern.compile("versionName='([^']+)'");

    @GetMapping("/version")
    public Map<String, Object> getVersion() {
        File apk = new File(apkPath);
        int versionCode = 0;
        String versionName = "";
        if (apk.exists()) {
            try {
                Process p = new ProcessBuilder(aaptPath, "dump", "badging", apk.getAbsolutePath())
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                Matcher mc = AAPT_VERSION_CODE.matcher(out);
                if (mc.find()) versionCode = Integer.parseInt(mc.group(1));
                Matcher mn = AAPT_VERSION_NAME.matcher(out);
                if (mn.find()) versionName = mn.group(1);
            } catch (Exception e) {
                log.warn("[APK] aapt failed: {}", e.getMessage());
            }
        } else {
            log.warn("[APK] not found: {}", apkPath);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("versionCode", versionCode);
        result.put("versionName", versionName);
        result.put("size", apk.exists() ? apk.length() : 0L);
        return result;
    }

    @GetMapping("/apk")
    public void getApk(HttpServletResponse response) throws IOException {
        File apk = new File(apkPath);
        if (!apk.exists() || apk.length() == 0) {
            log.error("[APK] file not found or empty: {}", apk.getAbsolutePath());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long size = apk.length();
        log.info("[APK] start sending size={} path={}", size, apk.getAbsolutePath());
        response.setContentType("application/vnd.android.package-archive");
        response.setHeader("Content-Disposition", "attachment; filename=\"capsule-code-debug.apk\"");
        response.setContentLengthLong(size);
        try (FileInputStream fis = new FileInputStream(apk)) {
            byte[] buf = new byte[65536];
            int len;
            long written = 0;
            OutputStream out = response.getOutputStream();
            while ((len = fis.read(buf)) != -1) {
                out.write(buf, 0, len);
                written += len;
            }
            out.flush();
            log.info("[APK] done, sent {} bytes", written);
        }
    }
}
