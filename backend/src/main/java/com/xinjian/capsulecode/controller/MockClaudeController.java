package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.websocket.ClaudeProcessManager;
import com.xinjian.capsulecode.websocket.MockTmuxProcessManager;
import com.xinjian.capsulecode.websocket.TmuxProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Mock Claude 控制接口，仅在 claude.mock=true 时有效。
 *
 * Android UI 测试通过这些接口精确控制 mock claude 的行为：
 *
 *   POST /mock/claude/set-behavior?convId=xxx&behavior=SLOW_REPLY
 *   POST /mock/claude/simulate-crash?convId=xxx
 *   GET  /mock/claude/status          — 返回 mock 模式是否启用
 */
@RestController
@RequestMapping("/mock/claude")
public class MockClaudeController {

    private static final Logger log = LoggerFactory.getLogger(MockClaudeController.class);

    @Autowired
    private ClaudeProcessManager claudeProcessManager;

    /** 检查 mock 模式是否启用 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean mock = claudeProcessManager.isMockMode();
        return ResponseEntity.ok(Map.of("mockMode", mock));
    }

    /**
     * 设置指定会话的 mock 行为。
     * @param convId  会话 ID
     * @param behavior  NORMAL_REPLY / SLOW_REPLY / WARMUP_TIMEOUT / PROCESS_CRASH / NO_OUTPUT / RESUME_FAIL
     */
    @PostMapping("/set-behavior")
    public ResponseEntity<Map<String, Object>> setBehavior(
            @RequestParam String convId,
            @RequestParam String behavior) {

        if (!claudeProcessManager.isMockMode()) {
            return ResponseEntity.badRequest().body(Map.of("error", "not in mock mode"));
        }

        TmuxProcessManager mgr = claudeProcessManager.getMockManager(convId);
        if (mgr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "no manager for convId=" + convId));
        }
        if (!(mgr instanceof MockTmuxProcessManager mock)) {
            return ResponseEntity.badRequest().body(Map.of("error", "manager is not MockTmuxProcessManager"));
        }

        MockTmuxProcessManager.Behavior b;
        try {
            b = MockTmuxProcessManager.Behavior.valueOf(behavior.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown behavior: " + behavior));
        }

        mock.setBehavior(b);
        // 同时存入 pending，确保下次 buildTmuxManager 时（manager 被重建）也能应用
        claudeProcessManager.setPendingMockBehavior(convId, behavior);
        log.info("[Mock] set behavior convId={} behavior={}", convId, b);
        return ResponseEntity.ok(Map.of("convId", convId, "behavior", b.name()));
    }

    /**
     * 模拟进程崩溃（触发 onExit 回调）。
     * @param convId 会话 ID
     */
    @PostMapping("/simulate-crash")
    public ResponseEntity<Map<String, Object>> simulateCrash(@RequestParam String convId) {
        if (!claudeProcessManager.isMockMode()) {
            return ResponseEntity.badRequest().body(Map.of("error", "not in mock mode"));
        }

        TmuxProcessManager mgr = claudeProcessManager.getMockManager(convId);
        if (mgr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "no manager for convId=" + convId));
        }
        if (!(mgr instanceof MockTmuxProcessManager mock)) {
            return ResponseEntity.badRequest().body(Map.of("error", "manager is not MockTmuxProcessManager"));
        }

        mock.simulateCrash();
        log.info("[Mock] simulate crash convId={}", convId);
        return ResponseEntity.ok(Map.of("convId", convId, "crashed", true));
    }
}
