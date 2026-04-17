package com.xinjian.capsulecode.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TmuxProcessManager 的 Mock 子类，用于 Android UI 测试。
 *
 * 继承 TmuxProcessManager，override 所有与 tmux/进程相关的方法，
 * 不启动任何真实进程，行为完全由外部注入（通过 setBehavior）。
 *
 * Behavior：
 *   NORMAL_REPLY   - 收到消息后 300ms 吐回复 + turn_end（默认）
 *   SLOW_REPLY     - 5s 后回复（测试长 loading）
 *   WARMUP_TIMEOUT - sendWarmup 永不回复，等 60s 超时触发 onResumeFailed
 *   PROCESS_CRASH  - 收到消息后立即触发 onExit
 *   NO_OUTPUT      - 收到消息后永不回复（测试 3 分钟超时）
 *   RESUME_FAIL    - startResume 后直接触发 onResumeFailed
 */
public class MockTmuxProcessManager extends TmuxProcessManager {

    private static final Logger log = LoggerFactory.getLogger(MockTmuxProcessManager.class);

    public enum Behavior {
        NORMAL_REPLY,
        SLOW_REPLY,
        WARMUP_TIMEOUT,
        PROCESS_CRASH,
        NO_OUTPUT,
        RESUME_FAIL
    }

    private volatile Behavior behavior = Behavior.NORMAL_REPLY;
    private volatile boolean mockSessionAlive = false;
    private volatile boolean mockTailing = false;
    private volatile long mockTailOffset = 0;

    private final ScheduledExecutorService mockScheduler = Executors.newSingleThreadScheduledExecutor();
    private static final String MOCK_SESSION_ID_PREFIX = "mock-session-";

    public MockTmuxProcessManager(String convId, String workDir) {
        // 父类构造传 null 回调，随后通过 applyCallbacks/updateCallbacks 覆盖
        super(convId, workDir, null, null, null,
              null, null, null, null, null, null, null);
    }

    // ── 行为注入 ──────────────────────────────────────────────────
    public void setBehavior(Behavior b) {
        log.info("[Mock] setBehavior convId={} behavior={}", getConvId(), b);
        this.behavior = b;
    }
    public Behavior getBehavior() { return behavior; }

    // convId 在父类是 private，通过 tmuxSession 推算（去掉 claude_ 前缀）
    private String getConvId() {
        String s = getTmuxSession();
        return s.startsWith("claude_") ? s.substring(7) : s;
    }

    // ── override：生命周期方法 ─────────────────────────────────────

    @Override
    public void start() {
        log.info("[Mock] start");
        mockSessionAlive = true;
        mockScheduler.schedule(() -> {
            synchronized (callbackLock) {
                onSessionId.accept(MOCK_SESSION_ID_PREFIX + getConvId());
            }
        }, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public void startResume(String claudeSessionId) {
        log.info("[Mock] startResume behavior={}", behavior);
        mockSessionAlive = true;
        switch (behavior) {
            case RESUME_FAIL -> mockScheduler.schedule(() -> {
                mockSessionAlive = false;
                synchronized (callbackLock) { onResumeFailed.accept("mock resume fail"); }
            }, 300, TimeUnit.MILLISECONDS);
            case WARMUP_TIMEOUT -> mockScheduler.schedule(() -> {
                // 触发 onResumeReady，让 Handler 调 sendWarmup()，但 sendWarmup 永不回复
                if (onResumeReady != null) onResumeReady.run();
            }, 200, TimeUnit.MILLISECONDS);
            default -> mockScheduler.schedule(() -> {
                if (onResumeReady != null) onResumeReady.run();
            }, 200, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void startTailing(long startOffset) {
        log.info("[Mock] startTailing offset={}", startOffset);
        mockTailing = true;
        mockTailOffset = startOffset;
        tailing = true;       // 父类字段，供 isTailing() 使用
        tailOffset = startOffset;
    }

    @Override
    public void stopTailing() {
        log.info("[Mock] stopTailing");
        mockTailing = false;
        tailing = false;
    }

    @Override
    public boolean isTailing() { return mockTailing; }

    @Override
    public long getTailOffset() { return mockTailOffset; }

    @Override
    public boolean isSessionAlive() { return mockSessionAlive; }

    @Override
    public boolean isCurrentlyProcessing() { return currentlyProcessing; }

    @Override
    public long getProcessingStartMs() { return processingStartMs; }

    @Override
    public void killTmuxSession() {
        log.info("[Mock] killTmuxSession");
        mockSessionAlive = false;
        mockTailing = false;
        tailing = false;
        currentlyProcessing = false;
        processingStartMs = 0L;
        mockScheduler.shutdownNow();
        flushScheduler.shutdownNow();
    }

    @Override
    public boolean sendMessageWithContent(List<Map<String, Object>> contentBlocks) {
        if (!mockSessionAlive) {
            log.warn("[Mock] sendMessageWithContent: session not alive");
            return false;
        }
        currentlyProcessing = true;
        processingStartMs = System.currentTimeMillis();
        log.info("[Mock] sendMessageWithContent behavior={}", behavior);

        switch (behavior) {
            case NORMAL_REPLY -> mockScheduler.schedule(this::doNormalReply, 1500, TimeUnit.MILLISECONDS);
            case SLOW_REPLY   -> mockScheduler.schedule(this::doNormalReply, 6000, TimeUnit.MILLISECONDS);
            case PROCESS_CRASH -> mockScheduler.schedule(() -> {
                mockSessionAlive = false;
                currentlyProcessing = false;
                processingStartMs = 0L;
                synchronized (callbackLock) { onExit.run(); }
            }, 1500, TimeUnit.MILLISECONDS);
            case NO_OUTPUT -> { /* 什么都不做，模拟永久 loading */ }
            default -> mockScheduler.schedule(this::doNormalReply, 300, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    @Override
    public void sendWarmup() {
        log.info("[Mock] sendWarmup behavior={}", behavior);
        if (behavior == Behavior.WARMUP_TIMEOUT) {
            // 模拟 60s 超时：用缩短的 3s（测试不需要真等 60s），触发 onResumeFailed
            log.info("[Mock] warmup will timeout in 3s (mock accelerated)");
            mockScheduler.schedule(() -> {
                if (!warmupRoundActive) return;
                log.warn("[Mock] Warmup timed out for mock session");
                warmupRoundActive = false;
                currentlyProcessing = false;
                processingStartMs = 0L;
                mockSessionAlive = false;
                synchronized (callbackLock) {
                    onResumeFailed.accept("warmup 超时（mock 加速）");
                }
            }, 3, TimeUnit.SECONDS);
            return;
        }
        // 正常：200ms 后触发 turn_end 完成 warmup
        mockScheduler.schedule(() -> {
            if (!warmupRoundActive) return;
            warmupRoundActive = false;
            synchronized (callbackLock) {
                currentlyProcessing = false;
                processingStartMs = 0L;
                onTurnEnd.run();
            }
        }, 200, TimeUnit.MILLISECONDS);
    }

    /** 外部调用：模拟进程崩溃（由 MockClaudeController.simulateCrash 调用）*/
    public void simulateCrash() {
        log.info("[Mock] simulateCrash");
        mockSessionAlive = false;
        currentlyProcessing = false;
        processingStartMs = 0L;
        synchronized (callbackLock) { onExit.run(); }
    }

    // ── 内部辅助 ──────────────────────────────────────────────────

    private void doNormalReply() {
        String replyText = "这是来自 mock claude 的测试回复。";
        synchronized (callbackLock) {
            onOutput.accept(new TmuxProcessManager.TextChunk(replyText, "text"));
            onTurnText.accept(replyText);
            onContextUsage.accept(new TmuxProcessManager.ContextUsage(100, 200000, 50, 50, 0, 0));
            currentlyProcessing = false;
            processingStartMs = 0L;
            onTurnEnd.run();
        }
    }
}
