package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.websocket.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Claude HTTP 轮询接口（v2）。
 *
 * 替代 WebSocket 连接，客户端通过 POST /claude/v2/init 初始化，
 * 再每秒 GET /claude/v2/stream 拉取新增输出，彻底消除连接状态管理。
 *
 * 接口一览：
 *   POST /claude/v2/init                         - 初始化，返回 cursor、history、conversations 等
 *   GET  /claude/v2/stream                       - 轮询新增输出 entries
 *   POST /claude/v2/messages                     - 发消息
 *   POST /claude/v2/conversations                - 新建会话
 *   POST /claude/v2/conversations/{convId}/switch - 切换会话
 *   DELETE /claude/v2/conversations/{convId}     - 删除会话
 *   POST /claude/v2/conversations/{convId}/kill  - kill 当前 session
 *   POST /claude/v2/conversations/kill-all       - kill 所有 session
 *   POST /claude/v2/conversations/{convId}/interrupt - 中断
 *   GET  /claude/v2/conversations                  - 查询会话列表（含 tmuxAlive 状态）
 */
@RestController
@RequestMapping("/claude/v2")
public class ClaudeHttpController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeHttpController.class);

    @Autowired
    private ClaudeProcessManager handler;

    /**
     * 初始化：解析会话、启动进程（若需要），返回初始 cursor、history、conversations 等。
     *
     * 请求体：{"deviceId":"xxx","convId":"yyy"（可选，不传则用最近会话）}
     * 响应：{convId, cursor, processing, processingStartMs, totalInputTokens, totalOutputTokens,
     *        conversations, activeTmuxSessions, history:[{role,text}]}
     */
    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestBody Map<String, Object> body) {
        String deviceId = (String) body.get("deviceId");
        String convId = (String) body.get("convId");
        String accountMode = body.get("accountMode") instanceof String s ? s : "max";
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        log.info("[HTTP] init deviceId={} convId={} accountMode={}", deviceId, convId != null ? convId.substring(0, Math.min(8, convId.length())) : "null", accountMode);
        Map<String, Object> result = handler.httpInit(deviceId, convId, accountMode);
        return ResponseEntity.ok(result);
    }

    /**
     * 轮询新增输出。
     *
     * 参数：convId（必填）、cursor（上次返回的 nextCursor，首次传 init 返回的 cursor）、deviceId
     * 响应：{entries:[{seqId,type,subtype,text,outputTokens,contextWindow,timestampMs}], nextCursor}
     *
     * entries 为空表示暂无新内容，客户端等 1s 再次请求。
     * cursorExpired=true 时客户端应重新调 init。
     */
    @GetMapping("/stream")
    public ResponseEntity<Map<String, Object>> stream(
            @RequestParam String convId,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) String deviceId) {
        if (convId == null || convId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "convId is required"));
        }
        Map<String, Object> result = handler.httpStream(convId, cursor);
        return ResponseEntity.ok(result);
    }

    /**
     * 发送消息到 claude 进程。
     *
     * 请求体：{"deviceId":"xxx","convId":"yyy","text":"...",
     *          "fileIds":["id1","id2"]（可选）}
     */
    @PostMapping("/messages")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> body) {
        String deviceId = (String) body.get("deviceId");
        String convId = (String) body.get("convId");
        String text = (String) body.get("text");
        @SuppressWarnings("unchecked")
        List<String> fileIds = (List<String>) body.get("fileIds");

        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        if (convId == null || convId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "convId is required"));
        }
        String preview = text != null && text.length() > 60 ? text.substring(0, 60).replace("\n", "↵") + "…" : (text != null ? text.replace("\n", "↵") : "");
        log.info("[HTTP] sendMessage deviceId={} convId={} text=\"{}\"", deviceId, convId.substring(0, Math.min(8, convId.length())), preview);

        Map<String, Object> result = handler.httpSendMessage(deviceId, convId, text, fileIds);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 新建会话。
     *
     * 请求体：{"deviceId":"xxx"}
     * 响应：与 init 相同，convId 是新会话的 ID
     */
    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> newConversation(@RequestBody Map<String, Object> body) {
        String deviceId = (String) body.get("deviceId");
        String accountMode = body.get("accountMode") instanceof String s ? s : "max";
        String workDir = body.get("workDir") instanceof String w && !w.isBlank() ? w : null;
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        log.info("[HTTP] newConversation deviceId={} accountMode={} workDir={}", deviceId, accountMode, workDir);
        Map<String, Object> result = handler.httpNewConversation(deviceId, accountMode, workDir);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 切换到指定会话。
     *
     * 请求体：{"deviceId":"xxx","enterpriseMode":true/false}
     * 响应：与 init 相同，convId 是目标会话的 ID
     */
    @PostMapping("/conversations/{convId}/switch")
    public ResponseEntity<Map<String, Object>> switchConversation(
            @PathVariable String convId,
            @RequestBody Map<String, Object> body) {
        String deviceId = (String) body.get("deviceId");
        String accountMode = body.get("accountMode") instanceof String s ? s : "max";
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        log.info("[HTTP] switchConversation deviceId={} convId={} accountMode={}", deviceId, convId.substring(0, Math.min(8, convId.length())), accountMode);
        Map<String, Object> result = handler.httpSwitchConversation(deviceId, convId, accountMode);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除会话（DB + tmux kill）。
     *
     * 响应：{conversations:[...], activeTmuxSessions:N}
     */
    @DeleteMapping("/conversations/{convId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable String convId,
            @RequestParam String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        log.info("[HTTP] deleteConversation deviceId={} convId={}", deviceId, convId.substring(0, Math.min(8, convId.length())));
        Map<String, Object> result = handler.httpDeleteConversation(deviceId, convId);
        return ResponseEntity.ok(result);
    }

    /**
     * Kill 当前会话的 tmux 进程（不删 DB，下次 init 会走 resume）。
     *
     * 请求体：{"deviceId":"xxx"}
     */
    @PostMapping("/conversations/{convId}/kill")
    public ResponseEntity<Map<String, Object>> killCurrentSession(
            @PathVariable String convId,
            @RequestBody Map<String, String> body) {
        String deviceId = body.get("deviceId");
        log.info("[HTTP] killCurrentSession convId={}", convId.substring(0, Math.min(8, convId.length())));
        Map<String, Object> result = handler.httpKillCurrentSession(deviceId, convId);
        return ResponseEntity.ok(result);
    }

    /**
     * Kill 所有活跃 tmux session（不删 DB）。
     *
     * 请求体：{"deviceId":"xxx"}
     */
    @PostMapping("/conversations/kill-all")
    public ResponseEntity<Map<String, Object>> killAllSessions(@RequestBody Map<String, String> body) {
        String deviceId = body.get("deviceId");
        log.info("[HTTP] killAllSessions deviceId={}", deviceId);
        Map<String, Object> result = handler.httpKillAllSessions(deviceId);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询会话列表（含 tmuxAlive 实时状态）。
     *
     * 响应：{conversations:[...]}
     */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(@RequestParam String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        log.info("[HTTP] listConversations deviceId={}", deviceId);
        return ResponseEntity.ok(Map.of("conversations", handler.buildConversationList(deviceId)));
    }

    /**
     * 中断当前 claude 进程（发送 Ctrl+C）。
     *
     * 请求体：{"convId":"xxx"}
     */
    @PostMapping("/conversations/{convId}/interrupt")
    public ResponseEntity<Map<String, Object>> interrupt(@PathVariable String convId) {
        log.info("[HTTP] interrupt convId={}", convId.substring(0, Math.min(8, convId.length())));
        Map<String, Object> result = handler.httpInterrupt(convId);
        return ResponseEntity.ok(result);
    }

    /**
     * Claude Code Hook 事件回调。
     * 由 ~/.claude/hooks/notify-backend.sh 调用，将 Stop/Notification 事件推送给 Android。
     *
     * 请求体：{"convId":"xxx","event":"stop|notification","data":{...}}
     */
    @PostMapping("/hook-event")
    public ResponseEntity<Void> hookEvent(@RequestBody Map<String, Object> body) {
        String convId = (String) body.get("convId");
        String event = (String) body.get("event");
        if (convId == null || event == null) {
            return ResponseEntity.badRequest().build();
        }
        String data = null;
        Object dataObj = body.get("data");
        if (dataObj != null) {
            try { data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dataObj); }
            catch (Exception ignored) {}
        }
        handler.handleHookEvent(convId, event, data);
        return ResponseEntity.ok().build();
    }
}
