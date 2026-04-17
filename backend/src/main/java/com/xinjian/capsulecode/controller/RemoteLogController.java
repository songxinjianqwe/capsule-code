package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.model.DeviceAliases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/callback/log")
public class RemoteLogController {

    private static final Logger log = LoggerFactory.getLogger(RemoteLogController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Value("${app.log-dir:logs}")
    private String logDir;

    /** 根据 deviceId 构建日志目录名，格式固定为 deviceId_alias */
    private String dirName(String deviceId) {
        String alias = DeviceAliases.resolve("MOBILE_" + deviceId);
        return deviceId + "_" + alias;
    }

    // POST /callback/log?deviceId=xxx&level=I&tag=Foo&msg=hello
    @PostMapping
    public Map<String, Object> appendLog(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "I") String level,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam String msg) {

        String today = LocalDate.now().format(DATE_FMT);
        File dir = new File(logDir + "/" + dirName(deviceId));
        dir.mkdirs();
        File logFile = new File(dir, today + ".log");

        String line = LocalDateTime.now().format(TS_FMT) + " " + level + "/" + tag + ": " + msg + "\n";
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to write remote log for device {}: {}", deviceId, e.getMessage());
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("ok", true);
        return result;
    }

    // GET /callback/log?deviceId=xxx&date=2025-03-17&pageSize=500&page=0
    // page=0 → 最后 pageSize 行；page=1 → 倒数 pageSize~2*pageSize 行，以此类推
    // deviceId 传目录名（deviceId_alias）
    @GetMapping
    public Map<String, Object> readLog(
            @RequestParam String deviceId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "500") int pageSize,
            @RequestParam(required = false, defaultValue = "0") int page) throws IOException {

        String day = (date != null && !date.isEmpty()) ? date : LocalDate.now().format(DATE_FMT);
        File logFile = new File(logDir + "/" + deviceId + "/" + day + ".log");
        if (!logFile.exists()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("lines", new ArrayList<>());
            empty.put("hasMoreInDate", false);
            return empty;
        }

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        String[] allLines = content.split("\n", -1);
        // 文件末尾换行导致最后一个元素为空串，去掉
        int totalLines = (allLines.length > 0 && allLines[allLines.length - 1].isEmpty())
                ? allLines.length - 1 : allLines.length;

        int end = totalLines - page * pageSize;
        List<String> resultLines = new ArrayList<>();
        boolean hasMoreInDate = false;
        if (end > 0) {
            int start = Math.max(0, end - pageSize);
            hasMoreInDate = start > 0;
            for (int i = start; i < end; i++) resultLines.add(allLines[i]);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("lines", resultLines);
        result.put("hasMoreInDate", hasMoreInDate);
        log.debug("readLog deviceId={} date={} page={} pageSize={} returned={} lines hasMore={}",
                deviceId, day, page, pageSize, resultLines.size(), hasMoreInDate);
        return result;
    }

    // GET /callback/log/devices → 列出所有有日志的设备（目录名列表，格式 deviceId_alias）
    @GetMapping("/devices")
    public Map<String, Object> listDevices() {
        File logsDir = new File(logDir);
        Map<String, Object> result = new HashMap<String, Object>();
        if (!logsDir.exists()) {
            result.put("devices", new String[0]);
            return result;
        }
        String[] dirs = logsDir.list();
        List<String> deviceDirs = new ArrayList<>();
        if (dirs != null) {
            for (String dir : dirs) {
                String id = dir.contains("_") ? dir.substring(0, dir.indexOf("_")) : dir;
                if (id.matches("[0-9a-f]{16}")) deviceDirs.add(dir);
            }
        }
        result.put("devices", deviceDirs);
        return result;
    }

    // GET /callback/log/dates?deviceId=xxx → 列出该设备有日志的日期（降序），deviceId 传目录名
    @GetMapping("/dates")
    public List<String> listDates(@RequestParam String deviceId) {
        File dir = new File(logDir + "/" + deviceId);
        List<String> dates = new ArrayList<>();
        if (dir.exists()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File f : files) dates.add(f.getName().replace(".log", ""));
                dates.sort(Collections.reverseOrder());
            }
        }
        return dates;
    }

    // GET /callback/log/server-log/dates?source=backend|frontend|python|watchdog
    // 返回可用日期列表（降序）。单文件服务（frontend/python/watchdog）只返回今天；
    // 后端按日期切割，返回 capsule-yyyy-MM-dd.log 中的日期 + 今天（capsule.log）
    @GetMapping("/server-log/dates")
    public List<String> listServerLogDates(@RequestParam String source) {
        List<String> dates = new ArrayList<>();
        String today = LocalDate.now().format(DATE_FMT);
        if ("backend".equals(source)) {
            // 扫描 logs/ 目录下的 capsule-yyyy-MM-dd.log
            File logsDir = new File(logDir);
            if (logsDir.exists()) {
                File[] files = logsDir.listFiles((d, name) -> name.startsWith("capsule-") && name.endsWith(".log"));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName(); // capsule-2026-03-21.log
                        dates.add(name.substring("capsule-".length(), name.length() - ".log".length()));
                    }
                    dates.sort(Collections.reverseOrder());
                }
            }
            // capsule.log 就是今天，如果还没切割则补今天
            if (!dates.contains(today)) dates.add(0, today);
        } else {
            // frontend / python / watchdog / whisper：单文件，只有今天
            dates.add(today);
        }
        return dates;
    }

    // GET /callback/log/server-log?source=backend|frontend|python&date=yyyy-MM-dd&pageSize=500&page=0
    // 返回对应日志文件分页内容
    @GetMapping("/server-log")
    public Map<String, Object> readServerLog(
            @RequestParam String source,
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "500") int pageSize,
            @RequestParam(required = false, defaultValue = "0") int page) throws IOException {
        String today = LocalDate.now().format(DATE_FMT);
        String day = (date != null && !date.isEmpty()) ? date : today;
        File logFile;
        switch (source) {
            case "backend":
                if (day.equals(today)) {
                    logFile = new File(logDir + "/capsule.log");
                } else {
                    logFile = new File(logDir + "/capsule-" + day + ".log");
                }
                break;
            case "frontend":
                logFile = new File(logDir + "/frontend.log");
                break;
            case "python":
                logFile = new File(logDir + "/electricity_web.log");
                break;
            case "watchdog":
                logFile = new File(logDir + "/watchdog.log");
                break;
            case "whisper":
                logFile = new File(logDir + "/whisper.log");
                break;
            default:
                Map<String, Object> err = new HashMap<>();
                err.put("lines", List.of("Unknown source: " + source));
                err.put("hasMoreInDate", false);
                return err;
        }
        if (!logFile.exists()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("lines", new ArrayList<>());
            empty.put("hasMoreInDate", false);
            return empty;
        }

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        String[] allLines = content.split("\n", -1);
        int totalLines = (allLines.length > 0 && allLines[allLines.length - 1].isEmpty())
                ? allLines.length - 1 : allLines.length;

        int end = totalLines - page * pageSize;
        List<String> resultLines = new ArrayList<>();
        boolean hasMoreInDate = false;
        if (end > 0) {
            int start = Math.max(0, end - pageSize);
            hasMoreInDate = start > 0;
            for (int i = start; i < end; i++) resultLines.add(allLines[i]);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("lines", resultLines);
        result.put("hasMoreInDate", hasMoreInDate);
        log.debug("readServerLog source={} date={} page={} pageSize={} returned={} lines hasMore={}",
                source, day, page, pageSize, resultLines.size(), hasMoreInDate);
        return result;
    }
}
