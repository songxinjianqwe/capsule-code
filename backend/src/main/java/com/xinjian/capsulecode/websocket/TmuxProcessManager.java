package com.xinjian.capsulecode.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 管理通过 tmux 托管的 claude 进程。
 *
 * 生命周期：
 * - start() / startResume()：启动 tmux session，claude stdout 重定向到日志文件，stdin 通过 FIFO 传入
 * - startTailing(offset)：在独立 tail 线程里 RandomAccessFile 逐行读日志文件，实时推送给客户端
 * - stopTailing()：同步阻塞停止 tail 线程，会话切换时调用，不影响 tmux 进程
 * - killTmuxSession()：彻底 kill tmux session，仅在 delete_conversation 时调用
 *
 * 并发安全：
 * - tailThread 用 volatile Thread + join(3s) 管控，确保切换会话时旧 tail 彻底退出
 * - FIFO 写入通过 writeExecutor（单线程）+ fifoWriteLock 串行化
 * - flushScheduler 只在 killTmuxSession() 时关闭（stopTailing 不关，warmup 60s 超时需要它）
 *
 * IPC 方式：
 * - 实时输出：RandomAccessFile poll 日志文件（50ms 间隔），输出同步写 DB
 * - stdin 输入：FIFO（不变）
 */
public class TmuxProcessManager {

    private static final Logger log = LoggerFactory.getLogger(TmuxProcessManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    // 优先环境变量 CLAUDE_BIN，其次 macOS 默认路径，最后走 PATH（Docker 里 npm -g 装到 /usr/bin/claude）
    public static final String CLAUDE_BIN = resolveClaudeBin();
    private static String resolveClaudeBin() {
        String env = System.getenv("CLAUDE_BIN");
        if (env != null && !env.isBlank()) return env;
        String macDefault = System.getProperty("user.home") + "/.local/bin/claude";
        if (new java.io.File(macDefault).canExecute()) return macDefault;
        return "claude"; // 从 PATH 查（Linux 容器用）
    }

    /** 输出片段，携带内容类型 */
    public record TextChunk(String text, String subtype) {}

    /** context_window 用量及本轮 token 明细 */
    public record ContextUsage(int tokensUsed, int contextWindow,
                               int inputTokens, int outputTokens,
                               int cacheReadTokens, int cacheCreateTokens) {}

    // ── 回调接口（volatile，支持 attach 时更新绑定的 WS session） ──────
    protected volatile Consumer<TextChunk> onOutput;
    protected volatile Runnable onTurnEnd;
    protected volatile Consumer<String> onTurnText;
    protected volatile Consumer<ContextUsage> onContextUsage;
    protected volatile Runnable onExit;
    protected volatile Consumer<String> onSessionId;
    protected volatile Consumer<String> onResumeFailed;
    protected volatile Runnable onWarmupComplete;
    // message_stop 事件回调：Claude CLI 流式文字结束信号（比 result 事件早约 3 秒）
    // 仅用于提前通知 Android 文字已结束，不改变后端 processing 状态
    protected volatile Runnable onAssistantDone;
    // DB 持久化回调（不受 silenceOutput 影响，断线期间仍持续写 DB）
    protected volatile Consumer<TextChunk> onPersist;
    protected volatile Consumer<ContextUsage> onPersistTurnEnd;

    // ── 回调修改/触发锁（防止 silenceOutput 与 onTurnEnd 触发之间的竞态） ──
    protected final Object callbackLock = new Object();

    // ── 进程标识 ───────────────────────────────────────────────────
    private final String convId;
    private final String workDir;
    private final String proxy;
    private final String envFile;       // enterprise 模式下 source 的环境变量文件路径，null = 不需要
    private final String claudeConfigDir; // pro 模式下的 CLAUDE_CONFIG_DIR，null = 不设置
    private final String tmuxSession;   // "claude_" + convId 去横线取前 8 位
    private final String logFilePath;   // <workDir>/logs/claude_logs/<convId>.log
    private final String fifoPath;      // <workDir>/logs/claude_stdin/<convId>.fifo

    // ── tail 线程管控 ──────────────────────────────────────────────
    // tailLock 保护 startTailing/stopTailing/isTailing 的原子性。
    // 注意：不能复用 this（bufferDelta 是 synchronized(this)，tail 线程持有 this 时若外部 join 会死锁）。
    private final Object tailLock = new Object();
    private volatile Thread tailThread = null;
    protected volatile boolean tailing = false;
    protected volatile long tailOffset = 0;

    // ── FIFO 写入串行化 ────────────────────────────────────────────
    // 单线程 executor + ReentrantLock，确保切换会话时不会并发写同一 fifo
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final ReentrantLock fifoWriteLock = new ReentrantLock();

    // ── 状态标志 ───────────────────────────────────────────────────
    private volatile boolean silenced = false;
    protected volatile boolean warmupRoundActive = false;
    private volatile boolean isResumeMode = false;
    private volatile boolean hasTurnCompleted = false;
    private volatile boolean resumeReadyFired = false;
    protected volatile boolean currentlyProcessing = false;  // 有一轮 turn 正在进行中（已收到 user 消息，尚未收到 turn_end）
    private volatile boolean interruptFired = false;          // sendInterrupt() 已手动触发 onTurnEnd，tail 线程后续收到的 result 事件需忽略
    protected volatile long processingStartMs = 0L;          // 本轮开始时间戳（ms），用于切换会话后恢复计时
    private volatile int contextWindowSize = -1;
    protected volatile Runnable onResumeReady = null;

    // ── delta 缓冲（同 ClaudeProcessManager） ──────────────────────
    private final StringBuilder deltaBuffer = new StringBuilder();
    private String deltaSubtype = "text";
    private ScheduledFuture<?> flushFuture;
    private final StringBuilder turnTextBuffer = new StringBuilder();
    // flushScheduler 只在 killTmuxSession() 时关闭（stopTailing 不关，warmup 超时需要它）
    protected final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor();

    // ── 无输出超时检测 ─────────────────────────────────────────────
    private static final long PROCESSING_TIMEOUT_MS = 3 * 60 * 1000L; // 3 分钟
    private volatile long lastOutputMs = 0L;   // 最后一次收到输出的时间戳
    private volatile ScheduledFuture<?> timeoutCheckFuture = null;

    public TmuxProcessManager(String convId, String workDir, String proxy, String envFile, String claudeConfigDir,
                               Consumer<TextChunk> onOutput, Runnable onTurnEnd,
                               Consumer<String> onTurnText, Consumer<ContextUsage> onContextUsage,
                               Runnable onExit, Consumer<String> onSessionId,
                               Consumer<String> onResumeFailed) {
        this.convId = convId;
        this.workDir = workDir;
        this.proxy = proxy;
        this.envFile = envFile;
        this.claudeConfigDir = claudeConfigDir;
        // 回调可为 null（通过 applyCallbacks/updateCallbacks 覆盖），用空实现兜底防 NPE
        this.onOutput       = onOutput       != null ? onOutput       : chunk -> {};
        this.onTurnEnd      = onTurnEnd      != null ? onTurnEnd      : () -> {};
        this.onTurnText     = onTurnText     != null ? onTurnText     : t -> {};
        this.onContextUsage = onContextUsage != null ? onContextUsage : u -> {};
        this.onExit         = onExit         != null ? onExit         : () -> {};
        this.onSessionId    = onSessionId    != null ? onSessionId    : s -> {};
        this.onResumeFailed = onResumeFailed != null ? onResumeFailed : r -> {};
        this.onPersist        = chunk -> {};
        this.onPersistTurnEnd = u -> {};

        // session 名：去掉 UUID 横线取前 8 位
        this.tmuxSession = "claude_" + convId.replace("-", "").substring(0, 8);
        this.logFilePath = workDir + "/logs/claude_logs/" + convId + ".log";
        this.fifoPath = workDir + "/logs/claude_stdin/" + convId + ".fifo";
    }

    public String getLogFilePath() { return logFilePath; }
    public String getTmuxSession() { return tmuxSession; }
    public long getTailOffset() { synchronized (tailLock) { return tailOffset; } }
    public boolean isTailing() { synchronized (tailLock) { return tailing && tailThread != null && tailThread.isAlive(); } }
    public String getWorkDir() { return workDir; }
    public String getProxy() { return proxy; }
    public String getEnvFile() { return envFile; }
    public String getClaudeConfigDir() { return claudeConfigDir; }
    public void setOnResumeReady(Runnable callback) { this.onResumeReady = callback; }
    public void setOnWarmupComplete(Runnable callback) { this.onWarmupComplete = callback; }
    public void setOnAssistantDone(Runnable callback) { this.onAssistantDone = callback; }
    public void setWarmupRoundActive(boolean active) { this.warmupRoundActive = active; }
    public boolean isWarmupRoundActive() { return warmupRoundActive; }
    public boolean isCurrentlyProcessing() { return currentlyProcessing; }
    public long getProcessingStartMs() { return processingStartMs; }

    /**
     * backend-restart-attach 场景：重建 TmuxProcessManager 挂接到现存 tmux session 时，
     * 新实例的 currentlyProcessing 默认 false，会导致 sendInterrupt() 误判 skip。
     * DB 里存的 processing=true 表示 claude 进程实际在处理中，此时必须同步恢复。
     */
    public void restoreProcessingState(long startMs) {
        this.currentlyProcessing = true;
        this.processingStartMs = startMs > 0 ? startMs : System.currentTimeMillis();
        log.info("[restoreProcessingState] currentlyProcessing=true processingStartMs={} convId={}",
                this.processingStartMs, convId);
    }
    public void setOnPersist(Consumer<TextChunk> onPersist) { this.onPersist = onPersist; }
    public void setOnPersistTurnEnd(Consumer<ContextUsage> onPersistTurnEnd) { this.onPersistTurnEnd = onPersistTurnEnd; }

    /**
     * 重连时更新绑定的 WS session 回调。
     * TmuxProcessManager 的生命周期与 WS 连接解耦，但 onOutput/onTurnEnd 等回调
     * capture 了具体的 WS session 对象。断线重连后新 session 来了，必须更新这些回调，
     * 否则 tail 线程会把消息推给已关闭的旧 session，客户端收不到任何输出。
     */
    /** 切换会话时调用：把 output/turnEnd 回调置为 noop，防止旧进程的输出串到新会话 */
    public void silenceOutput() {
        synchronized (callbackLock) {
            this.onOutput  = chunk -> {};
            this.onTurnEnd = () -> {};
        }
        log.info("Output silenced for convId={}", convId);
    }

    public void updateCallbacks(Consumer<TextChunk> onOutput, Runnable onTurnEnd,
                                Consumer<String> onTurnText, Consumer<ContextUsage> onContextUsage,
                                Runnable onExit, Consumer<String> onSessionId,
                                Consumer<String> onResumeFailed) {
        synchronized (callbackLock) {
            this.onOutput       = onOutput;
            this.onTurnEnd      = onTurnEnd;
            this.onTurnText     = onTurnText;
            this.onContextUsage = onContextUsage;
            this.onExit         = onExit;
            this.onSessionId    = onSessionId;
            this.onResumeFailed = onResumeFailed;
        }
        log.info("Callbacks updated for convId={}", convId);
    }

    // ── tmux 检查 ──────────────────────────────────────────────────

    /** 检查 tmux session 是否存活 */
    public boolean isSessionAlive() {
        try {
            Process p = new ProcessBuilder("tmux", "has-session", "-t", tmuxSession)
                    .redirectErrorStream(true).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 stdin FIFO 向 claude 发送 control_request/interrupt，中断当前 turn 但保持进程存活。
     * 原 tmux send-keys C-c 方案在 stream-json 模式下会杀死进程，已废弃。
     * claude 收到 interrupt 后只输出 control_response（不输出 result），
     * 因此此处在 FIFO 写入后立即手动触发 onTurnEnd，重置 processing 状态。
     */
    public void sendInterrupt() {
        // 兜底：只要 tmux session 存活就允许写 FIFO interrupt。
        // 不再以 currentlyProcessing 作为前置条件 —— 重建 mgr 后该字段可能为 false 但进程仍在跑
        // （backend-restart-attach 场景），此时仍应允许用户中断。
        if (!isSessionAlive()) {
            log.info("[sendInterrupt] tmux session dead, skip convId={}", convId);
            return;
        }
        if (!currentlyProcessing) {
            log.warn("[sendInterrupt] currentlyProcessing=false but session alive, sending interrupt anyway convId={}", convId);
        }
        String requestId = java.util.UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "control_request",
                "request_id", requestId,
                "request", java.util.Map.of("subtype", "interrupt")
            ));
        } catch (Exception e) {
            log.error("[sendInterrupt] serialize failed convId={}", convId, e);
            return;
        }
        final String line = json + "\n";
        log.info("[sendInterrupt] writing interrupt to FIFO convId={} requestId={}", convId, requestId);
        Future<?> future = writeExecutor.submit(() -> {
            fifoWriteLock.lock();
            try (FileOutputStream fos = new FileOutputStream(fifoPath, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                log.info("[sendInterrupt] FIFO write OK convId={}", convId);
            } catch (IOException e) {
                log.error("[sendInterrupt] FIFO write FAILED convId={}", convId, e);
            } finally {
                fifoWriteLock.unlock();
            }
        });
        try {
            future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[sendInterrupt] FIFO write timeout convId={}", convId);
            future.cancel(true);
        } catch (Exception e) {
            log.error("[sendInterrupt] FIFO write error convId={}", convId, e);
        }
        // claude 实际上仍会输出 result 事件（有时 is_error=true），
        // 用 interruptFired 标志让 processLine 跳过它，避免双重 turn_end + [错误] 消息
        flushDelta();
        turnTextBuffer.setLength(0);
        synchronized (callbackLock) {
            if (currentlyProcessing) {
                currentlyProcessing = false;
                processingStartMs = 0L;
                interruptFired = true;
                log.info("[sendInterrupt] manually firing onTurnEnd convId={}", convId);
                onTurnEnd.run();
            }
        }
    }

    // ── 启动 ──────────────────────────────────────────────────────

    /** 确保日志目录、fifo 目录、pipe 目录存在，创建 stdin fifo（已存在则跳过） */
    private void ensureDirsAndFifo() throws IOException {
        Files.createDirectories(Paths.get(workDir + "/logs/claude_logs"));
        Files.createDirectories(Paths.get(workDir + "/logs/claude_stdin"));
        Files.createDirectories(Paths.get(workDir + "/logs/claude_pipes"));
        File fifo = new File(fifoPath);
        if (!fifo.exists()) {
            Process mkfifo = new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start();
            try { mkfifo.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!fifo.exists()) throw new IOException("Failed to create fifo: " + fifoPath);
            log.info("Created stdin fifo: {}", fifoPath);
        }
    }

    /** 全新启动（无 --resume） */
    public void start() throws IOException {
        ensureDirsAndFifo();
        startTmuxSession(List.of());
    }

    /** 带 --resume 启动 */
    public void startResume(String sessionId) throws IOException {
        log.info("Resuming claude session={}", sessionId);
        isResumeMode = true;
        ensureDirsAndFifo();
        startTmuxSession(List.of("--resume", sessionId));
        if (onResumeReady != null && !resumeReadyFired) {
            resumeReadyFired = true;
            onResumeReady.run();
        }
    }

    private void startTmuxSession(List<String> extraArgs) throws IOException {
        List<String> claudeArgs = new java.util.ArrayList<>(List.of(
                CLAUDE_BIN,
                "--print", "--input-format=stream-json", "--output-format=stream-json",
                "--verbose", "--include-partial-messages"
        ));
        claudeArgs.addAll(extraArgs);

        // ⚠️ stdin 用 <> 读写模式：bash 同时持有读写端，claude 永不收到 EOF
        StringBuilder envPrefix = new StringBuilder("NO_COLOR=1 CLAUDE_CONV_ID=" + convId + " ");
        if (proxy != null && !proxy.isBlank()) {
            envPrefix.append("HTTP_PROXY=").append(proxy).append(" ");
            envPrefix.append("HTTPS_PROXY=").append(proxy).append(" ");
        }
        // pro 模式：注入 CLAUDE_CONFIG_DIR
        if (claudeConfigDir != null && !claudeConfigDir.isBlank()) {
            envPrefix.append("CLAUDE_CONFIG_DIR=").append(claudeConfigDir).append(" ");
            log.info("Pro mode: CLAUDE_CONFIG_DIR={} for convId={}", claudeConfigDir, convId);
        }
        // enterprise 模式：source 环境变量文件
        String envSourcePrefix = "";
        if (envFile != null && !envFile.isBlank()) {
            envSourcePrefix = "source " + envFile + " && ";
            log.info("Enterprise mode: sourcing envFile={} for convId={}", envFile, convId);
        }
        String claudeCmd = "cd " + workDir + " && "
                + envSourcePrefix
                + envPrefix
                + String.join(" ", claudeArgs)
                + " <> " + fifoPath        // 读写模式打开 fifo
                + " >> " + logFilePath + " 2>&1";

        // 清理可能残留的同名 session（后端重启后内存丢失但 tmux session 仍存活）
        Process killOld = new ProcessBuilder("tmux", "kill-session", "-t", tmuxSession)
                .redirectErrorStream(true).start();
        try { killOld.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int killExit = killOld.exitValue();
        if (killExit == 0) {
            log.warn("Killed stale tmux session={} before starting new one (convId={})", tmuxSession, convId);
        }

        log.info("Starting tmux session={} cmd={}", tmuxSession, claudeCmd);
        Process tmux = new ProcessBuilder(
                "tmux", "new-session", "-d", "-s", tmuxSession,
                "/bin/bash", "-c", claudeCmd
        ).directory(new File(workDir)).redirectErrorStream(true).start();

        String output = new String(tmux.getInputStream().readAllBytes());
        try { tmux.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (tmux.exitValue() != 0) {
            throw new IOException("tmux new-session failed (exit=" + tmux.exitValue() + "): " + output);
        }
        log.info("tmux session {} started for convId={}", tmuxSession, convId);
    }

    // ── Tail ──────────────────────────────────────────────────────

    /**
     * 开始实时 tail 日志文件，从 startOffset 字节处继续。
     * 如已有 tail 在运行，先同步等它停止。
     * 线程安全：在 tailLock 内执行，与 stopTailing/isTailing 互斥。
     */
    public void startTailing(long startOffset) {
        synchronized (tailLock) {
            startTailingLocked(startOffset);
        }
    }

    /**
     * 原子性地"仅在 tail 未运行时才启动"，避免调用方 check-then-act 竞态。
     * init attach 分支和 send 路径均应调用此方法，而非裸 if(!isTailing()) startTailing()。
     */
    public void startTailingIfNotRunning(long offset) {
        synchronized (tailLock) {
            if (tailing && tailThread != null && tailThread.isAlive()) {
                log.debug("[tail] already tailing, skip startTailingIfNotRunning convId={}", convId);
                return;
            }
            log.info("[tail] not running, starting tail offset={} convId={}", offset, convId);
            startTailingLocked(offset);
        }
    }

    // tailLock 持有者调用，不重复加锁
    private void startTailingLocked(long startOffset) {
        stopTailingLocked();  // 先确保旧 tail 彻底停止（同步阻塞，最多等 3s）
        tailing = true;
        tailOffset = startOffset;
        lastOutputMs = System.currentTimeMillis();
        if (timeoutCheckFuture != null) timeoutCheckFuture.cancel(false);
        timeoutCheckFuture = flushScheduler.scheduleAtFixedRate(this::checkProcessingTimeout,
                30, 30, TimeUnit.SECONDS);
        tailThread = new Thread(this::tailLoop, "tail-" + convId.substring(0, 8));
        tailThread.setDaemon(true);
        tailThread.start();
        log.info("[tail] started convId={} offset={}", convId, startOffset);
    }

    /**
     * 停止 tail 线程，同步等待线程退出（最多 3s）。
     * 线程安全：在 tailLock 内执行，与 startTailing/isTailing 互斥。
     * ⚠️ 不关 flushScheduler（warmup 超时需要它），不关 writeExecutor（复用）。
     */
    public void stopTailing() {
        synchronized (tailLock) {
            stopTailingLocked();
        }
    }

    /**
     * 仅设标志位 + interrupt，不 join tail 线程（供 tail 线程内部的回调调用，避免自己 join 自己死锁）。
     * tail 线程在当前行处理完后会检测到 tailing=false，自然退出。
     * 场景：onResumeFailed 回调在 tail 线程里执行，必须用此方法而非 stopTailing()。
     */
    public void softStopTailing() {
        synchronized (tailLock) {
            tailing = false;
            Thread t = tailThread;
            tailThread = null;
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
            log.info("[tail] soft stop requested convId={} offset={}", convId, tailOffset);
        }
    }

    // tailLock 持有者调用，不重复加锁
    // ⚠️ tailLoop 不持有 tailLock（只用 volatile），join 不会死锁
    private void stopTailingLocked() {
        tailing = false;
        if (timeoutCheckFuture != null) {
            timeoutCheckFuture.cancel(false);
            timeoutCheckFuture = null;
        }
        Thread t = tailThread;
        tailThread = null;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(3000);
                if (t.isAlive()) log.warn("[tail] thread did not stop in 3s for convId={}", convId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("[tail] stopped convId={} offset={}", convId, tailOffset);
    }

    private void tailLoop() {
        log.info("Tail loop running, convId={} offset={}", convId, tailOffset);
        File logFile = new File(logFilePath);
        // 等日志文件出现（tmux 进程刚启动可能有短暂延迟，最多等 6s）
        for (int i = 0; i < 30 && !logFile.exists() && !Thread.currentThread().isInterrupted(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!logFile.exists()) {
            log.warn("Log file never appeared: {}", logFilePath);
            return;
        }
        // 每 3s 检查一次 tmux session 是否存活（检测进程意外退出）
        int deadCheckCounter = 0;
        final int DEAD_CHECK_INTERVAL = 60; // 60 * 50ms = 3s
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            raf.seek(tailOffset);
            while (tailing && !Thread.currentThread().isInterrupted()) {
                String rawLine = raf.readLine();
                if (rawLine != null) {
                    // RandomAccessFile.readLine() 用 ISO-8859-1，需转码为 UTF-8
                    byte[] bytes = rawLine.getBytes(StandardCharsets.ISO_8859_1);
                    String line = new String(bytes, StandardCharsets.UTF_8);
                    tailOffset = raf.getFilePointer();
                    if (!line.isBlank()) {
                        lastOutputMs = System.currentTimeMillis();
                        try {
                            processLine(line);
                        } catch (Exception e) {
                            log.error("[tailLoop] processLine threw unexpected exception convId={} offset={} line={}",
                                convId, tailOffset, line.length() > 80 ? line.substring(0, 80) + "…" : line, e);
                        }
                        deadCheckCounter = 0; // 有新内容说明进程还活着，重置计数
                    }
                } else {
                    // 无新内容时每 3s 检查一次 tmux session 存活状态
                    deadCheckCounter++;
                    if (deadCheckCounter >= DEAD_CHECK_INTERVAL) {
                        deadCheckCounter = 0;
                        if (!isSessionAlive()) {
                            boolean wasProcessing = currentlyProcessing;
                            log.info("[tailLoop] tmux session dead detected convId={} wasProcessing={} silenced={} tailOffset={}",
                                convId, wasProcessing, silenced, tailOffset);
                            if (!silenced) {
                                currentlyProcessing = false;
                                processingStartMs = 0L;
                                log.info("[tailLoop] firing onExit convId={} wasProcessing={}", convId, wasProcessing);
                                onExit.run();
                            } else {
                                log.info("[tailLoop] onExit suppressed (silenced) convId={}", convId);
                            }
                            break;
                        }
                    }
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } catch (IOException e) {
            if (tailing) log.error("Tail loop IO error for convId={} offset={}", convId, tailOffset, e);
        }
        log.info("Tail loop exited, convId={} offset={}", convId, tailOffset);
    }

    // ── JSON 解析 + 输出处理 ──────────────────────────────────────

    private void processLine(String line) {
        // 完整原始行打 debug（太多时可关掉），关键结构行打 info
        log.debug("[claude→tail] raw convId={}: {}", convId, line);

        // message_stop：Claude CLI 流式文字结束信号，比 result 早约 3 秒（详见 devdocs）
        // 这里只通知 Android 让 loading 动画提前消失，不改动任何后端 processing 状态
        if (!warmupRoundActive && line.contains("\"type\":\"message_stop\"")) {
            log.info("[processLine] message_stop detected convId={}", convId);
            flushDelta();
            Runnable cb = onAssistantDone;
            if (cb != null) {
                try { cb.run(); } catch (Exception e) {
                    log.error("[processLine] onAssistantDone callback threw convId={}", convId, e);
                }
            }
        }

        // interrupt 后 claude 仍会输出 result 事件，必须在 extractChunk 之前跳过，
        // 否则 [错误] chunk 会先被塞进 buffer 再被拦截，已经来不及了
        if (interruptFired && isTurnEnd(line)) {
            interruptFired = false;
            log.info("[processLine] suppressing result after interrupt convId={}", convId);
            return;
        }

        // resume 首轮 error result：提前拦截，防止 extractChunk 先输出 [错误] chunk
        // 导致错误消息显示两遍（一遍来自 chunk，一遍来自 onResumeFailed 写入的 segment）
        if (isResumeMode && !hasTurnCompleted && isTurnEnd(line) && isErrorResult(line)) {
            String errMsg = extractErrorMessage(line);
            log.warn("[claude→pipe] resume error result (early intercept) convId={} silenced={} err={}", convId, silenced, errMsg);
            if (!silenced) onResumeFailed.accept(errMsg);
            return;
        }

        TextChunk chunk = extractChunk(line);
        if (chunk != null) {
            // 无条件过滤所有 subtype：把 <br> 标签替换成换行，纯空白 chunk 直接丢弃
            {
                String subtype = chunk.subtype();
                String cleaned = chunk.text().replaceAll("(?i)<br\\s*/?>", "\n");
                if (cleaned.isBlank()) chunk = null;
                else if (!cleaned.equals(chunk.text())) chunk = new TextChunk(cleaned, subtype);
            }
        }
        if (chunk != null) {
            String preview = chunk.text().length() > 60 ? chunk.text().substring(0, 60).replace("\n", "↵") + "…" : chunk.text().replace("\n", "↵");
            log.info("[claude→tail] chunk subtype={} warmupActive={} text=\"{}\"", chunk.subtype(), warmupRoundActive, preview);
            if (!warmupRoundActive) {
                bufferDelta(chunk);
                if ("text".equals(chunk.subtype())) turnTextBuffer.append(chunk.text());
            }
        }

        String sessionId = extractSessionId(line);
        if (sessionId != null) {
            log.info("[claude→pipe] session_id={} convId={}", sessionId, convId);
            onSessionId.accept(sessionId);
            // system/init 是进程就绪的最早信号（源码：utils/messages/systemInit.ts）
            // resume 后收到 system/init 即可判定进程已启动完毕，无需继续等待 turn_end 或轮询 isSessionAlive()
            if (warmupRoundActive) {
                log.info("[Warmup] system/init received, warmup complete immediately convId={}", convId);
                warmupRoundActive = false;
                currentlyProcessing = false;
                processingStartMs = 0L;
                Runnable cb = onWarmupComplete;
                if (cb != null) cb.run();
            }
        }

        int cw = extractContextWindow(line);
        if (cw > 0) contextWindowSize = cw;

        if (!isTurnEnd(line)) return;

        flushDelta();

        // resume 失败（首轮就是 error result）
        if (isResumeMode && !hasTurnCompleted && isErrorResult(line)) {
            String errMsg = extractErrorMessage(line);
            log.warn("[claude→pipe] resume error result convId={} silenced={} err={}", convId, silenced, errMsg);
            if (!silenced) onResumeFailed.accept(errMsg);
            return;
        }
        hasTurnCompleted = true;

        // warmup 轮结束：关闭屏蔽，不触发 onTurnEnd/onTurnText，但触发 onWarmupComplete 清除 loading
        if (warmupRoundActive) {
            warmupRoundActive = false;
            currentlyProcessing = false;
            processingStartMs = 0L;
            turnTextBuffer.setLength(0);
            log.info("[STATE] warmup completed convId={}", convId);
            Runnable cb = onWarmupComplete;
            if (cb != null) cb.run();
            return;
        }

        String turnText = turnTextBuffer.toString();
        onTurnText.accept(turnText);
        turnTextBuffer.setLength(0);

        if (contextWindowSize <= 0) {
            int cwFromResult = extractContextWindowFromResult(line);
            if (cwFromResult > 0) contextWindowSize = cwFromResult;
        }
        int[] usage = extractUsage(line);
        int tokensUsed = usage[0] + usage[2] + usage[3];
        log.info("[processLine] turn_end convId={} inputTokens={} outputTokens={} cacheRead={} cacheCreate={} contextWindow={}",
            convId, usage[0], usage[1], usage[2], usage[3], contextWindowSize);
        ContextUsage contextUsage = new ContextUsage(tokensUsed, contextWindowSize,
                usage[0], usage[1], usage[2], usage[3]);
        if (tokensUsed >= 0 && contextWindowSize > 0) {
            onContextUsage.accept(contextUsage);
        }
        onPersistTurnEnd.accept(contextUsage);
        // 普通消息收到 error result（如 limit 超限）：通知 Android 显示切换提示
        if (isErrorResult(line)) {
            String errMsg = extractErrorMessage(line);
            log.warn("[processLine] error result (normal turn) convId={} silenced={} err={}", convId, silenced, errMsg);
            if (!silenced) onResumeFailed.accept(errMsg);
            currentlyProcessing = false;
            processingStartMs = 0L;
            return;
        }
        synchronized (callbackLock) {
            currentlyProcessing = false;
            processingStartMs = 0L;
            log.info("[processLine] firing onTurnEnd convId={} turnTextLen={}", convId, turnText.length());
            onTurnEnd.run();
        }
    }

    // ── FIFO 写入 ─────────────────────────────────────────────────

    public void sendMessage(String userText) {
        sendMessageWithContent(List.of(Map.of("type", "text", "text", userText)));
    }

    public boolean sendMessageWithContent(List<Map<String, Object>> contentBlocks) {
        currentlyProcessing = true;
        processingStartMs = System.currentTimeMillis();
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "type", "user",
                    "message", Map.of("role", "user", "content", contentBlocks)
            ));
        } catch (Exception e) {
            log.error("Failed to serialize message for convId={}", convId, e);
            currentlyProcessing = false;
            processingStartMs = 0L;
            return false;
        }
        final String line = json + "\n";
        String preview = json.length() > 80 ? json.substring(0, 80) + "…" : json;
        log.info("[FIFO→claude] writing {} blocks convId={} json={}", contentBlocks.size(), convId, preview);

        // writeExecutor 串行化写入，future.get(3s) 超时防止 claude 进程已退出时无限阻塞
        Future<?> future = writeExecutor.submit(() -> {
            fifoWriteLock.lock();
            try (FileOutputStream fos = new FileOutputStream(fifoPath, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                log.info("[FIFO→claude] write OK convId={}", convId);
            } catch (IOException e) {
                log.error("[FIFO→claude] write FAILED convId={}", convId, e);
            } finally {
                fifoWriteLock.unlock();
            }
        });
        try {
            future.get(3, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("[FIFO→claude] write TIMEOUT (3s) convId={} — claude process may have exited", convId);
            future.cancel(true);
            currentlyProcessing = false;
            processingStartMs = 0L;
            return false;
        } catch (Exception e) {
            log.error("[FIFO→claude] write ERROR convId={}", convId, e);
            currentlyProcessing = false;
            processingStartMs = 0L;
            return false;
        }
    }

    // ── 无输出超时检测 ─────────────────────────────────────────────

    private void checkProcessingTimeout() {
        if (!currentlyProcessing || warmupRoundActive) return;
        long idleMs = System.currentTimeMillis() - lastOutputMs;
        if (idleMs >= PROCESSING_TIMEOUT_MS) {
            log.warn("Processing timeout ({}ms idle) for convId={}, forcing turn end", idleMs, convId);
            currentlyProcessing = false;
            processingStartMs = 0L;
            onOutput.accept(new TextChunk("\n⚠️ 响应超时（超过 3 分钟无输出），已自动取消。\n", "system"));
            onTurnEnd.run();
        }
    }

    // ── Warmup（通过 tmux capture-pane 检测 ❯ 提示符判断 Claude 是否 ready）──

    private static final int PROCESS_CHECK_INTERVAL_MS = 1000;  // 每 1 秒检查一次
    private static final int PROCESS_CHECK_TIMEOUT_MS = 30_000; // 30 秒超时（进程启动失败才会走到这里）

    /**
     * 阶段五（当前方案）：不等任何信号，只确认 claude 进程存活即视为就绪。
     *
     * 背景：--print 模式下 claude 启动后不显示 ❯ 提示符（那是交互模式专有的），
     * 历史上下文加载完成后直接阻塞等待 FIFO 输入，期间日志文件和 tmux pane 均无输出。
     * 因此无法通过任何输出信号判断"就绪"，进程存活即是当前最可靠的就绪标志。
     *
     * 超时设置为 30s，仅用于兜底进程启动失败的情况。
     */
    public void waitForPrompt() {
        log.info("[Warmup] checking process alive, convId={} tmux={}", convId, tmuxSession);
        warmupRoundActive = true;

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            long startMs = System.currentTimeMillis();
            while (warmupRoundActive) {
                long elapsed = System.currentTimeMillis() - startMs;

                if (elapsed > PROCESS_CHECK_TIMEOUT_MS) {
                    log.warn("[Warmup] process start timed out ({}ms) for convId={}, sessionAlive={}",
                            elapsed, convId, isSessionAlive());
                    warmupRoundActive = false;
                    currentlyProcessing = false;
                    processingStartMs = 0L;
                    killTmuxSession();
                    onResumeFailed.accept("warmup 超时（>" + (PROCESS_CHECK_TIMEOUT_MS / 1000) + "s，claude 进程未能启动），会话恢复失败");
                    return;
                }

                try {
                    if (isSessionAlive()) {
                        log.info("[Warmup] process alive after {}ms, convId={}", elapsed, convId);
                        warmupRoundActive = false;
                        currentlyProcessing = false;
                        processingStartMs = 0L;
                        if (onWarmupComplete != null) onWarmupComplete.run();
                        return;
                    }
                    log.debug("[Warmup] process not alive yet ({}ms), convId={}", elapsed, convId);
                    Thread.sleep(PROCESS_CHECK_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    log.info("[Warmup] process check interrupted, convId={}", convId);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("[Warmup] process check failed: {}", e.getMessage());
                    try { Thread.sleep(PROCESS_CHECK_INTERVAL_MS); } catch (InterruptedException ignored) { return; }
                }
            }
        });
    }

    /** 保留旧方法签名，内部转发到 waitForPrompt()，兼容调用方 */
    public void sendWarmup() {
        waitForPrompt();
    }

    // ── Kill ──────────────────────────────────────────────────────

    /**
     * 彻底 kill tmux session（仅在 delete_conversation 时调用）。
     * 关闭所有 executor，删除 fifo 文件。
     */
    public void killTmuxSession() {
        log.info("Killing tmux session {} for convId={}", tmuxSession, convId);
        silenced = true;
        stopTailing();
        writeExecutor.shutdownNow();
        flushScheduler.shutdownNow();
        try {
            new ProcessBuilder("tmux", "kill-session", "-t", tmuxSession)
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            log.info("Tmux session {} killed", tmuxSession);
        } catch (Exception e) {
            log.warn("Failed to kill tmux session {}: {}", tmuxSession, e.getMessage(), e);
        }
        try { Files.deleteIfExists(Paths.get(fifoPath)); } catch (IOException ignored) {}
    }

    // ── Delta 缓冲 ────────────────────────────────────────────────

    /** 将 delta 加入缓冲，100ms 内统一刷出；subtype 变化时立即 flush 当前缓冲再开新段。
     *  同一 subtype 内，新工具调用开始（text 以 "\n[工具:" 开头）时也强制分段，避免多个工具调用合并到同一 segment。 */
    private synchronized void bufferDelta(TextChunk chunk) {
        boolean subtypeChanged = !chunk.subtype().equals(deltaSubtype);
        boolean newToolCall = "tool".equals(chunk.subtype()) && chunk.text().startsWith("\n[工具:");
        if ((subtypeChanged || newToolCall) && deltaBuffer.length() > 0) {
            if (flushFuture != null) flushFuture.cancel(false);
            String text = deltaBuffer.toString();
            deltaBuffer.setLength(0);
            onOutput.accept(new TextChunk(text, deltaSubtype));
        }
        deltaSubtype = chunk.subtype();
        deltaBuffer.append(chunk.text());
        if (flushFuture != null) flushFuture.cancel(false);
        flushFuture = flushScheduler.schedule(this::flushDelta, 100, TimeUnit.MILLISECONDS);
    }

    private synchronized void flushDelta() {
        if (deltaBuffer.length() == 0) return;
        String text = deltaBuffer.toString();
        String subtype = deltaSubtype;
        deltaBuffer.setLength(0);
        TextChunk chunk = new TextChunk(text, subtype);
        onOutput.accept(chunk);
        if (!warmupRoundActive) onPersist.accept(chunk);
    }

    // ── JSON 解析静态方法（供 Handler 的 sendCatchupContent 复用） ──

    public static TextChunk extractChunkStatic(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            String type = node.path("type").asText();

            if ("stream_event".equals(type)) {
                JsonNode event = node.path("event");
                String eventType = event.path("type").asText();
                if ("content_block_delta".equals(eventType)) {
                    JsonNode delta = event.path("delta");
                    String deltaType = delta.path("type").asText();
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText(null);
                        if (text != null && !text.isEmpty()) {
                            // 把 HTML <br> 标签替换成换行符，避免在 Android 等纯文本界面原样显示
                            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
                            return new TextChunk(text, "text");
                        }
                    }
                    if ("thinking_delta".equals(deltaType)) {
                        String thinking = delta.path("thinking").asText(null);
                        if (thinking != null && !thinking.isEmpty()) return new TextChunk(thinking, "thinking");
                    }
                    if ("input_json_delta".equals(deltaType)) {
                        String partial = delta.path("partial_json").asText(null);
                        if (partial != null && !partial.isEmpty()) {
                            partial = partial.replace("\n", "").replace("\r", "");
                            if (!partial.isEmpty()) return new TextChunk(partial, "tool");
                        }
                    }
                }
                if ("content_block_start".equals(eventType)) {
                    JsonNode block = event.path("content_block");
                    String blockType = block.path("type").asText();
                    if ("tool_use".equals(blockType)) {
                        String toolName = block.path("name").asText("");
                        // 前置换行确保每个工具调用单独一行，不会和上一个 tool segment 连在一起
                        return new TextChunk("\n[工具: " + toolName + "] ", "tool");
                    }
                    if ("thinking".equals(blockType)) {
                        return new TextChunk("\n[思考]\n", "thinking");
                    }
                }
                if ("tool_result".equals(eventType)) {
                    return new TextChunk("[工具结果]\n", "tool");
                }
                return null;
            }

            if ("assistant".equals(type)) {
                // Claude CLI stream-json 格式会在流式分片（stream_event/content_block_delta）结束后
                // 再输出一条 assistant 汇总消息，内容与分片拼接结果完全一致。
                // 如果解析此消息会导致 Android 端正文重复显示，直接忽略。
                log.debug("[extractChunk] ignoring assistant summary message convId={}", "n/a");
                return null;
            }

            if ("result".equals(type) && node.path("is_error").asBoolean()) {
                return new TextChunk("[错误] " + node.path("result").asText() + "\n", "system");
            }
        } catch (Exception e) {
            log.debug("extractChunkStatic parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
        }
        return null;
    }

    public static boolean isTurnEndStatic(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            return "result".equals(node.path("type").asText());
        } catch (Exception e) {
            log.debug("isTurnEnd parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
            return false;
        }
    }

    public static int[] extractUsageStatic(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            if ("result".equals(node.path("type").asText())) {
                JsonNode modelUsage = node.path("modelUsage");
                if (!modelUsage.isMissingNode()) {
                    var it = modelUsage.fields();
                    while (it.hasNext()) {
                        JsonNode model = it.next().getValue();
                        int input       = model.path("inputTokens").asInt(0);
                        int output      = model.path("outputTokens").asInt(0);
                        int cacheRead   = model.path("cacheReadInputTokens").asInt(0);
                        int cacheCreate = model.path("cacheCreationInputTokens").asInt(0);
                        if (input + output + cacheRead + cacheCreate > 0) {
                            return new int[]{input, output, cacheRead, cacheCreate};
                        }
                    }
                }
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode()) {
                    int input       = usage.path("input_tokens").asInt(0);
                    int output      = usage.path("output_tokens").asInt(0);
                    int cacheRead   = usage.path("cache_read_input_tokens").asInt(0);
                    int cacheCreate = usage.path("cache_creation_input_tokens").asInt(0);
                    return new int[]{input, output, cacheRead, cacheCreate};
                }
            }
        } catch (Exception e) {
            log.debug("extractUsageStatic parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
        }
        return new int[]{0, 0, 0, 0};
    }

    public static int extractContextWindowFromResultStatic(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            if (!"result".equals(node.path("type").asText())) return -1;
            JsonNode modelUsage = node.path("modelUsage");
            if (modelUsage.isMissingNode()) return -1;
            var it = modelUsage.fields();
            while (it.hasNext()) {
                JsonNode model = it.next().getValue();
                int cw = model.path("contextWindow").asInt(-1);
                if (cw > 0) return cw;
            }
        } catch (Exception e) {
            log.debug("extractContextWindowStatic parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
        }
        return -1;
    }

    // ── 实例方法（processLine 调用，保持与旧 ClaudeProcessManager 一致） ──

    private TextChunk extractChunk(String jsonLine) {
        return extractChunkStatic(jsonLine);
    }

    private String extractSessionId(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            if ("system".equals(node.path("type").asText())
                    && "init".equals(node.path("subtype").asText())) {
                String sid = node.path("session_id").asText(null);
                if (sid != null && !sid.isBlank()) return sid;
            }
        } catch (Exception e) {
            log.debug("extractSessionId parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
        }
        return null;
    }

    private int extractContextWindow(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            if ("system".equals(node.path("type").asText())
                    && "init".equals(node.path("subtype").asText())) {
                log.info("claude system/init: {}", jsonLine);
                JsonNode cw = node.path("context_window");
                if (!cw.isMissingNode() && cw.asInt(-1) > 0) return cw.asInt(-1);
                JsonNode models = node.path("models");
                if (models.isArray() && models.size() > 0) {
                    JsonNode mcw = models.get(0).path("context_window");
                    if (!mcw.isMissingNode() && mcw.asInt(-1) > 0) return mcw.asInt(-1);
                }
            }
        } catch (Exception e) {
            log.debug("extractContextWindow parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
        }
        return -1;
    }

    private int extractContextWindowFromResult(String jsonLine) {
        return extractContextWindowFromResultStatic(jsonLine);
    }

    private int[] extractUsage(String jsonLine) {
        return extractUsageStatic(jsonLine);
    }

    private boolean isErrorResult(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            return "result".equals(node.path("type").asText()) && node.path("is_error").asBoolean();
        } catch (Exception e) {
            log.debug("isErrorResult parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
            return false;
        }
    }

    private String extractErrorMessage(String jsonLine) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            String result = node.path("result").asText("");
            return result.isEmpty() ? "未知错误" : result;
        } catch (Exception e) {
            log.debug("extractErrorMessage parse failed: {}", jsonLine.length() > 80 ? jsonLine.substring(0, 80) : jsonLine, e);
            return "未知错误";
        }
    }

    private boolean isTurnEnd(String jsonLine) {
        return isTurnEndStatic(jsonLine);
    }
}
