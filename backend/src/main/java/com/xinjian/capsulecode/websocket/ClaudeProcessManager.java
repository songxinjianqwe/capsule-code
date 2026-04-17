package com.xinjian.capsulecode.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinjian.capsulecode.shared.FileStorage;
import com.xinjian.capsulecode.mapper.ClaudeConversationMapper;
import com.xinjian.capsulecode.mapper.ClaudeMessageMapper;
import com.xinjian.capsulecode.mapper.ClaudeSessionMapper;
import com.xinjian.capsulecode.model.ChatFile;
import com.xinjian.capsulecode.model.ClaudeConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ClaudeProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProcessManager.class);

    @Value("${claude.work-dir}")
    private String workDir;

    @Value("${claude.proxy:}")
    private String claudeProxy;

    @Value("${claude.mock:false}")
    private boolean mockMode;

    @Value("${claude.env-file:}")
    private String claudeEnvFile;

    @Autowired
    private ClaudeSessionMapper claudeSessionMapper;

    @Autowired
    private ClaudeConversationMapper claudeConversationMapper;

    @Autowired
    private ClaudeMessageMapper claudeMessageMapper;

    @Autowired
    private PushWebSocketHandler pushWebSocketHandler;

    @Autowired
    private FileStorage fileStorage;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // convId -> TmuxProcessManager（进程与生命周期解耦）
    private final ConcurrentHashMap<String, TmuxProcessManager> processMap = new ConcurrentHashMap<>();

    // convId -> 账号模式（max/pro/enterprise），跟随最近一次 init 时的客户端设置
    private final ConcurrentHashMap<String, String> accountMap = new ConcurrentHashMap<>();

    // convId -> 互斥锁对象，防止并发 init 重复 startTmuxSession（duplicate session）
    private final ConcurrentHashMap<String, Object> initLocks = new ConcurrentHashMap<>();

    // ── HTTP 轮询支持 ─────────────────────────────────────────────────
    // convId -> 输出缓冲区（HTTP 轮询模式）
    private final ConcurrentHashMap<String, ClaudeOutputBuffer> outputBuffers = new ConcurrentHashMap<>();
    // convId -> AtomicBoolean（HTTP 模式下 per-convId 的 processing 状态）
    private final ConcurrentHashMap<String, AtomicBoolean> convProcessingMap = new ConcurrentHashMap<>();

    // mock 模式下：convId → 预设 behavior（在 manager 尚未建立时设置，建立时自动应用）
    private final java.util.concurrent.ConcurrentHashMap<String, String> pendingMockBehaviors
            = new java.util.concurrent.ConcurrentHashMap<>();

    public void setPendingMockBehavior(String convId, String behavior) {
        pendingMockBehaviors.put(convId, behavior);
    }

    /**
     * HTTP 模式回调绑定：把 onOutput/onTurnEnd/onExit/onResumeFailed 写入 ClaudeOutputBuffer。
     * 当 WS 路由被彻底移除后，此方法会成为唯一的回调绑定入口。
     */
    public void applyCallbacksBuffer(TmuxProcessManager mgr, String deviceId, String convId) {
        ClaudeOutputBuffer buf = outputBuffers.computeIfAbsent(convId, k -> new ClaudeOutputBuffer());
        AtomicBoolean convProcessing = convProcessingMap.computeIfAbsent(convId, k -> new AtomicBoolean(false));
        AtomicReference<TmuxProcessManager.ContextUsage> lastUsageRef = new AtomicReference<>();

        mgr.updateCallbacks(
            chunk -> buf.append("output", chunk.subtype(), chunk.text(), 0, 0),
            () -> {
                boolean wasProcessing = convProcessing.getAndSet(false);
                log.info("[STATE][BUF] turn_end convId={} wasProcessing={}", convId, wasProcessing);
                TmuxProcessManager.ContextUsage usage = lastUsageRef.get();
                int outTokens = usage != null ? usage.outputTokens() : 0;
                int cw = usage != null ? usage.contextWindow() : 0;
                buf.append("turn_end", "", "", outTokens, cw);
                CompletableFuture.runAsync(() -> {
                    try { claudeConversationMapper.updateProcessing(convId, false); }
                    catch (Exception e) { log.error("[persist] updateProcessing false failed convId={}", convId, e); }
                });
            },
            turnText -> {
                // turnText 回调保留但不再用于写 DB（已通过 onPersist 逐片写入）
                log.debug("[BUF] turnText len={} convId={}", turnText.length(), convId);
            },
            usage -> {
                lastUsageRef.set(usage);
                log.debug("[BUF] Context usage: tokensUsed={} contextWindow={}", usage.tokensUsed(), usage.contextWindow());
            },
            () -> {
                boolean wasProcessing = convProcessing.getAndSet(false);
                log.info("[STATE][BUF] onExit convId={} wasProcessing={} bufSize={}",
                    convId, wasProcessing, buf.size());
                if (wasProcessing) {
                    long seq = buf.append("turn_end", "", "", 0, 0);
                    log.info("[BUF] appended turn_end seqId={} convId={}", seq, convId);
                    CompletableFuture.runAsync(() -> {
                        try { claudeConversationMapper.updateProcessing(convId, false); }
                        catch (Exception e) { log.error("[persist] updateProcessing false (onExit) failed convId={}", convId, e); }
                    });
                }
                long seq2 = buf.append("disconnected", "system", "\n[" + ts() + "] claude 进程已退出\n", 0, 0);
                log.info("[BUF] appended disconnected seqId={} convId={}", seq2, convId);
            },
            sessionId -> {
                if (deviceId != null && convId != null) {
                    log.info("[BUF] Saving claude session_id={} for convId={}", sessionId, convId);
                    claudeConversationMapper.updateSessionId(convId, sessionId);
                    claudeSessionMapper.upsert(deviceId, sessionId, System.currentTimeMillis());
                }
            },
            reason -> {
                log.info("[STATE][BUF] onResumeFailed convId={} reason={}", convId, reason);
                convProcessing.set(false);
                // ⚠️ 此回调在 tail 线程内执行，不能 join 自己（会死锁），必须用 softStopTailing。
                // 不清理：下次 backend-restart-attach 会新建 mgr 并启动新 tail；
                // 若不 stop 旧 tail，旧 tail 继续读同一日志文件，导致内容重复 N 次。
                mgr.softStopTailing();
                processMap.remove(convId);
                long seq = buf.append("resume_failed", "", reason != null ? reason : "", 0, 0);
                log.info("[BUF] appended resume_failed seqId={} convId={}", seq, convId);
                CompletableFuture.runAsync(() -> {
                    try { claudeConversationMapper.updateProcessing(convId, false); }
                    catch (Exception e) { log.error("[persist] updateProcessing false (onResumeFailed) failed convId={}", convId, e); }
                });
            }
        );

        // message_stop 回调：流式文字结束信号，比 result 早约 3 秒。
        // 只往 buffer append 一个 output_done entry 让 Android 提前灭 ThinkingIndicator，
        // 不改动任何后端状态（convProcessing 仍然为 true，直到真正的 result 事件来）。
        mgr.setOnAssistantDone(() -> {
            log.info("[STATE][BUF] assistant_done convId={}", convId);
            buf.append("output_done", "", "", 0, 0);
        });

        // warmup 完成回调：清 convProcessing，通知 Android 解除 loading
        mgr.setOnWarmupComplete(() -> {
            boolean wasProcessing = convProcessing.getAndSet(false);
            log.info("[STATE][BUF] warmupComplete convId={} wasProcessing={}", convId, wasProcessing);
            buf.append("warmup_complete", "", "", 0, 0);
            CompletableFuture.runAsync(() -> {
                try { claudeConversationMapper.updateProcessing(convId, false); }
                catch (Exception e) { log.error("[persist] updateProcessing false (warmupComplete) failed convId={}", convId, e); }
            });
        });

        // DB 持久化回调：每个 output 片段异步写 DB（不受 silenceOutput 影响）
        mgr.setOnPersist(chunk -> CompletableFuture.runAsync(() -> {
            try {
                if (chunk.text().isBlank()) return;
                claudeMessageMapper.insertWithSubtype(convId,
                        "assistant", chunk.subtype(), chunk.text(), System.currentTimeMillis());
            } catch (Exception e) {
                log.error("[persist] Failed to save output chunk convId={} subtype={}", convId, chunk.subtype(), e);
            }
        }));

        // turn_end 时写 token 信息（用 insertWithTokens 插一条空文本的 turn_end 标记行）
        mgr.setOnPersistTurnEnd(usage -> CompletableFuture.runAsync(() -> {
            try {
                claudeMessageMapper.insertWithTokens(convId, "assistant", "turn_end", "",
                        System.currentTimeMillis(),
                        usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadTokens(), usage.cacheCreateTokens());
                claudeConversationMapper.updateLastActive(convId, System.currentTimeMillis());
                log.info("[persist] turn_end saved convId={} outputTokens={}", convId, usage.outputTokens());
            } catch (Exception e) {
                log.error("[persist] Failed to save turn_end tokens convId={}", convId, e);
            }
        }));
    }

    /** 构建不依赖 WS session 的 TmuxProcessManager（HTTP 模式专用） */
    private TmuxProcessManager buildTmuxManagerHttp(String deviceId, String convId, String accountMode) {
        TmuxProcessManager mgr;
        String resolvedEnvFile = "enterprise".equals(accountMode) && !claudeEnvFile.isBlank()
            ? claudeEnvFile
            : "~/unset_claude_env.sh"; // max/pro 模式主动 unset Bedrock 变量，防止从父进程环境继承
        String resolvedConfigDir = "pro".equals(accountMode) ? "~/.claude-pro" : null;

        // 优先用会话 workDir，null/blank 时 fallback 到 claude.work-dir 全局默认
        ClaudeConversation conv = claudeConversationMapper.findByConvId(convId);
        String effectiveWorkDir = (conv != null && conv.getWorkDir() != null && !conv.getWorkDir().isBlank())
                ? conv.getWorkDir()
                : workDir;
        log.info("[HTTP] buildTmuxManager convId={} accountMode={} envFile={} configDir={} workDir={}",
                convId, accountMode, resolvedEnvFile, resolvedConfigDir, effectiveWorkDir);
        if (mockMode) {
            MockTmuxProcessManager mockMgr = new MockTmuxProcessManager(convId, effectiveWorkDir);
            String pending = pendingMockBehaviors.remove(convId);
            if (pending != null) {
                try {
                    mockMgr.setBehavior(MockTmuxProcessManager.Behavior.valueOf(pending.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("[HTTP][Mock] unknown pending behavior: {}", pending);
                }
            }
            mgr = mockMgr;
        } else {
            mgr = new TmuxProcessManager(
                convId, effectiveWorkDir, claudeProxy.isBlank() ? null : claudeProxy, resolvedEnvFile, resolvedConfigDir,
                null, null, null, null, null, null, null
            );
        }
        applyCallbacksBuffer(mgr, deviceId, convId);
        return mgr;
    }

    /** mock 模式下暴露 processMap，供 MockClaudeController 获取 manager */
    public TmuxProcessManager getMockManager(String convId) {
        return mockMode ? processMap.get(convId) : null;
    }

    public boolean isMockMode() { return mockMode; }

    private ClaudeConversation resolveConversation(String deviceId, String requestedConvId) {
        ClaudeConversation conv = null;
        if (requestedConvId != null && !requestedConvId.isBlank()) {
            List<ClaudeConversation> all = claudeConversationMapper.findAllByDevice(deviceId);
            conv = all.stream().filter(c -> c.getConvId().equals(requestedConvId)).findFirst().orElse(null);
        }
        if (conv == null && deviceId != null) {
            conv = claudeConversationMapper.findLatestActive(deviceId);
        }
        if (conv == null) {
            conv = createNewConversation(deviceId);
        }
        return conv;
    }

    private ClaudeConversation createNewConversation(String deviceId) {
        return createNewConversation(deviceId, null);
    }

    private ClaudeConversation createNewConversation(String deviceId, String workDir) {
        ClaudeConversation conv = new ClaudeConversation();
        conv.setConvId(UUID.randomUUID().toString());
        conv.setDeviceId(deviceId);
        String name = "会话 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        conv.setName(name);
        long now = System.currentTimeMillis();
        conv.setCreatedAt(now);
        conv.setLastActiveAt(now);
        if (workDir != null && !workDir.isBlank()) {
            conv.setWorkDir(workDir);
        }
        claudeConversationMapper.insert(conv);
        log.info("[HTTP][new] created convId={} deviceId={} workDir={}", conv.getConvId(), deviceId, conv.getWorkDir());
        return conv;
    }

    /** 统计当前活跃的 claude tmux session 数量 */
    private boolean isTmuxSessionAlive(String sessionName) {
        try {
            Process p = new ProcessBuilder("tmux", "has-session", "-t", sessionName)
                .redirectErrorStream(true).start();
            return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int countActiveTmuxSessions() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "tmux list-sessions 2>/dev/null | grep -c '^claude_'")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return Integer.parseInt(out.isEmpty() ? "0" : out);
        } catch (Exception e) {
            log.warn("Failed to count tmux sessions: {}", e.getMessage());
            return 0;
        }
    }

    private static String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0L; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP 轮询模式 public 方法（供 ClaudeHttpController 调用）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * HTTP init：解析会话、启动进程（若需要），返回初始化数据。
     * HTTP init：解析会话、启动进程（若需要），返回初始化数据。
     */
    public Map<String, Object> httpInit(String deviceId, String requestedConvId, String accountMode) {
        log.info("[HTTP][init] → deviceId={} requestedConvId={} accountMode={}",
            deviceId, requestedConvId != null ? requestedConvId.substring(0, Math.min(8, requestedConvId.length())) : "null", accountMode);

        // 空状态：没 requestedConvId 且该 deviceId 无任何历史会话 → 不自动建，让客户端显示欢迎页
        if ((requestedConvId == null || requestedConvId.isBlank())
                && claudeConversationMapper.findLatestActive(deviceId) == null) {
            log.info("[HTTP][init] empty state for deviceId={} (no history) → returning convId=null", deviceId);
            Map<String, Object> empty = new java.util.LinkedHashMap<>();
            empty.put("convId", null);
            empty.put("cursor", 0);
            empty.put("processing", false);
            empty.put("processingStartMs", 0);
            empty.put("warmingUp", false);
            empty.put("totalInputTokens", 0);
            empty.put("totalOutputTokens", 0);
            empty.put("conversations", java.util.List.of());
            empty.put("activeTmuxSessions", 0);
            empty.put("history", java.util.List.of());
            return empty;
        }

        ClaudeConversation conv = resolveConversation(deviceId, requestedConvId);
        String convId = conv.getConvId();
        // accountMap 从 DB 恢复（DB 是进程实际创建时写入的权威值）
        // attach 分支不会再调 accountMap.put，所以这里的值就是最终值
        accountMap.put(convId, conv.getAccountMode());
        log.info("[HTTP][init] resolved convId={} claudeSessionId={}",
            convId, conv.getClaudeSessionId() != null ? conv.getClaudeSessionId().substring(0, Math.min(8, conv.getClaudeSessionId().length())) : "null");

        ClaudeOutputBuffer buf = outputBuffers.computeIfAbsent(convId, k -> new ClaudeOutputBuffer());
        AtomicBoolean convProcessing = convProcessingMap.computeIfAbsent(convId, k -> new AtomicBoolean(false));

        // 先拿 cursor（tail 还未启动，此后新增内容 seqId > cursor）
        long cursor = buf.getMaxSeqId();
        log.info("[HTTP][init] cursor={} bufSize={} convProcessing={}", cursor, buf.size(), convProcessing.get());

        // per-convId 锁：防止并发 init 时两次都判断 processMap 为空，重复 startTmuxSession 报 duplicate session
        Object initLock = initLocks.computeIfAbsent(convId, k -> new Object());
        boolean sessionConflict = false;
        synchronized (initLock) {

        // 进锁后重新读 processMap，可能已被先一步拿到锁的并发 init 写入
        TmuxProcessManager mgr = processMap.get(convId);
        boolean mgrInMap = mgr != null;
        boolean tmuxAlive = mgr != null && mgr.isSessionAlive();
        log.info("[HTTP][init] LOCK acquired convId={} mgrInMap={} tmuxAlive={}", convId, mgrInMap, tmuxAlive);

        if (mgrInMap && tmuxAlive) {
            // ── 分支1a：processMap 有 mgr 且 tmux 存活 → 先检查 PC 端冲突，无冲突再 attach ──
            boolean hasConflict1a = conv.getClaudeSessionId() != null
                    && detectAndReportConflict(conv.getClaudeSessionId(), false, buf, true, "attach", convId);
            if (hasConflict1a) sessionConflict = true;
            if (!hasConflict1a) {
                // tail 线程设计为一直在跑（stopTailing 不在 WS 断开时调用），直接重新绑定回调即可。
                // 注意：不要无条件调用 startTailing！
                //   存在竞态：tail 读取 raf.readLine() 后、tailOffset 更新前，若 startTailing 读到旧 tailOffset，
                //   新 tail 会从旧偏移重新读取同一行，导致每个 chunk 被处理两次（重复显示）。
                // 只有 tail 异常退出时才重启（isTailing() 会同时检查 tailing 标志位、线程是否存活）。
                log.info("[HTTP][init] BRANCH=attach convId={}", convId);
                applyCallbacksBuffer(mgr, deviceId, convId);
                mgr.startTailingIfNotRunning(mgr.getTailOffset());
            }

        } else if (mgrInMap) {
            // ── 分支1b：processMap 有 mgr 但 tmux 已死 → 清掉旧 mgr，重新启动 ──
            log.info("[HTTP][init] BRANCH=tmux-dead-rebuild convId={} clearing stale mgr", convId);
            mgr.silenceOutput();
            processMap.remove(convId);
            accountMap.put(convId, accountMode); // 真正重建进程，固定当前模式
            claudeConversationMapper.updateAccountMode(convId, accountMode);
            if (conv.getClaudeSessionId() != null) {
                String sessionId = conv.getClaudeSessionId();
                log.info("[HTTP][init] BRANCH=resume-after-rebuild convId={} sessionId={}", convId, sessionId.substring(0, Math.min(8, sessionId.length())));
                if (detectAndReportConflict(sessionId, true, buf, true, "rebuild", convId)) {
                    sessionConflict = true;
                } else {
                    TmuxProcessManager rebuiltMgr = buildTmuxManagerHttp(deviceId, convId, accountMode);
                    processMap.put(convId, rebuiltMgr);
                    rebuiltMgr.setOnResumeReady(() -> {
                        // 从文件末尾开始 tail，跳过历史输出，避免历史 turn_end 污染 buffer
                        long tailFrom = new java.io.File(rebuiltMgr.getLogFilePath()).length();
                        log.info("[HTTP][init] onResumeReady fired (rebuild) convId={} tailFrom={}", convId, tailFrom);
                        buf.append("connected", "", "\n[" + ts() + "] 已恢复历史会话，输入指令继续对话\n", 0, 0);
                        rebuiltMgr.setWarmupRoundActive(true);
                        rebuiltMgr.startTailing(tailFrom);
                        CompletableFuture.runAsync(() -> {
                            if (processMap.get(convId) == rebuiltMgr) rebuiltMgr.sendWarmup();
                        });
                    });
                    try {
                        rebuiltMgr.startResume(sessionId);
                        log.info("[HTTP][init] startResume launched (rebuild) convId={}", convId);
                    } catch (IOException e) {
                        log.error("[HTTP][init] Failed to resume (rebuild) convId={}", convId, e);
                        buf.append("resume_failed", "", e.getMessage(), 0, 0);
                    }
                }
            } else {
                log.info("[HTTP][init] BRANCH=fresh-after-rebuild convId={}", convId);
                TmuxProcessManager newMgr = buildTmuxManagerHttp(deviceId, convId, accountMode);
                processMap.put(convId, newMgr);
                int activeSessions = countActiveTmuxSessions();
                if (activeSessions >= 10) {
                    log.warn("[HTTP][init] Too many active tmux sessions ({}) refusing convId={}", activeSessions, convId);
                    buf.append("error", "system", "活跃会话已达上限（10个），请先删除不需要的会话再新建。\n", 0, 0);
                } else {
                    try {
                        newMgr.start();
                        newMgr.startTailing(0);
                        buf.append("connected", "", "\n[" + ts() + "] 已连接，输入指令开始对话\n", 0, 0);
                        log.info("[HTTP][init] fresh start OK (rebuild) convId={}", convId);
                    } catch (IOException e) {
                        log.error("[HTTP][init] Failed to start (rebuild) convId={}", convId, e);
                        buf.append("error", "system", "启动 claude 失败: " + e.getMessage() + "\n", 0, 0);
                    }
                }
            }

        } else if (conv.getClaudeSessionId() != null) {
            // ── 分支2：processMap 为空但有 sessionId（后端重启 or App 重启）─────
            String sessionId = conv.getClaudeSessionId();
            log.info("[HTTP][init] BRANCH=rebuild-check convId={} sessionId={}",
                convId, sessionId.substring(0, Math.min(8, sessionId.length())));
            TmuxProcessManager rebuiltMgr = buildTmuxManagerHttp(deviceId, convId, accountMode);
            boolean rebuiltAlive = rebuiltMgr.isSessionAlive();
            log.info("[HTTP][init] rebuilt tmuxAlive={} convId={}", rebuiltAlive, convId);
            if (rebuiltAlive) {
                // 分支2a：tmux 还活着（后端重启场景），先检查 PC 端冲突，无冲突再 attach
                log.info("[HTTP][init] BRANCH=backend-restart-attach convId={}", convId);
                if (detectAndReportConflict(sessionId, false, buf, true, "backend-restart-attach", convId)) {
                    sessionConflict = true;
                } else {
                    processMap.put(convId, rebuiltMgr);
                    // 从 DB 恢复 processing 状态（后端重启后内存丢失，DB 是唯一可靠信源）
                    if (conv.isProcessing()) {
                        convProcessing.set(true);
                        // 同步恢复 TmuxProcessManager 层的 currentlyProcessing，
                        // 否则 sendInterrupt() 会误判 not processing 直接 skip
                        rebuiltMgr.restoreProcessingState(0L);
                        log.info("[HTTP][init] restored processing=true from DB convId={}", convId);
                    }
                    // 从文件末尾开始 tail，跳过历史避免历史 turn_end 污染 buffer
                    long tailFrom2a = new java.io.File(rebuiltMgr.getLogFilePath()).length();
                    log.info("[HTTP][init] backend-restart-attach tailFrom={} convId={}", tailFrom2a, convId);
                    rebuiltMgr.startTailing(tailFrom2a);
                }
            } else {
                // 分支2b：tmux 也死了，走 startResume
                log.info("[HTTP][init] BRANCH=resume convId={} sessionId={}", convId, sessionId.substring(0, Math.min(8, sessionId.length())));
                if (detectAndReportConflict(sessionId, true, buf, true, "resume", convId)) {
                    sessionConflict = true;
                } else {
                    accountMap.put(convId, accountMode); // 真正重建进程，固定当前模式
                    claudeConversationMapper.updateAccountMode(convId, accountMode);
                    processMap.put(convId, rebuiltMgr);
                    rebuiltMgr.setOnResumeReady(() -> {
                        long tailFrom = new java.io.File(rebuiltMgr.getLogFilePath()).length();
                        log.info("[HTTP][init] onResumeReady fired convId={} tailFrom={}", convId, tailFrom);
                        buf.append("connected", "", "\n[" + ts() + "] 已恢复历史会话，输入指令继续对话\n", 0, 0);
                        rebuiltMgr.setWarmupRoundActive(true);
                        rebuiltMgr.startTailing(tailFrom);
                        CompletableFuture.runAsync(() -> {
                            if (processMap.get(convId) == rebuiltMgr) rebuiltMgr.sendWarmup();
                        });
                    });
                    try {
                        rebuiltMgr.startResume(sessionId);
                        log.info("[HTTP][init] startResume launched convId={}", convId);
                    } catch (IOException e) {
                        log.error("[HTTP][init] Failed to resume convId={}", convId, e);
                        buf.append("resume_failed", "", e.getMessage(), 0, 0);
                    }
                }
            }

        } else {
            // ── 分支3：全新启动（processMap 为空且无 sessionId）─────────────
            log.info("[HTTP][init] BRANCH=fresh convId={}", convId);
            accountMap.put(convId, accountMode); // 全新进程，固定当前模式
            claudeConversationMapper.updateAccountMode(convId, accountMode);
            TmuxProcessManager newMgr = buildTmuxManagerHttp(deviceId, convId, accountMode);
            processMap.put(convId, newMgr);
            int activeSessions = countActiveTmuxSessions();
            if (activeSessions >= 10) {
                log.warn("[HTTP][init] Too many active tmux sessions ({}) refusing convId={}", activeSessions, convId);
                buf.append("error", "system", "活跃会话已达上限（10个），请先删除不需要的会话再新建。\n", 0, 0);
            } else {
                try {
                    log.info("[HTTP][init] starting fresh claude process convId={} activeSessions={}", convId, activeSessions);
                    newMgr.start();
                    newMgr.startTailing(0);
                    buf.append("connected", "", "\n[" + ts() + "] 已连接，输入指令开始对话\n", 0, 0);
                    log.info("[HTTP][init] fresh start OK convId={}", convId);
                } catch (IOException e) {
                    log.error("[HTTP][init] Failed to start convId={}", convId, e);
                    buf.append("error", "system", "启动 claude 失败: " + e.getMessage() + "\n", 0, 0);
                }
            }
        }
        } // end synchronized(initLock)

        // 组装响应
        boolean isProcessing = convProcessing.get() || (processMap.get(convId) != null && processMap.get(convId).isCurrentlyProcessing());
        long processingStartMs = 0L;
        TmuxProcessManager curMgr = processMap.get(convId);
        if (isProcessing && curMgr != null) processingStartMs = curMgr.getProcessingStartMs();

        Map<String, Object> sums = null;
        try { sums = claudeMessageMapper.sumTokensByConvId(convId); } catch (Exception e) { log.warn("[HTTP][init] sumTokens failed convId={}: {}", convId, e.getMessage()); }
        long totalInput  = sums != null ? toLong(sums.get("inputTokens"))  : 0L;
        long totalOutput = sums != null ? toLong(sums.get("outputTokens")) : 0L;

        List<Map<String, String>> history = new ArrayList<>();
        try {
            List<Map<String, String>> rows = claudeMessageMapper.findByConvId(convId);
            // 过滤 turn_end 标记行，合并相邻同 role+subtype 的片段
            List<Map<String, String>> merged = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String role = row.get("role");
                String subtype = row.getOrDefault("subtype", "");
                String text = row.getOrDefault("text", "");
                // 跳过 turn_end 标记行（text 为空的 assistant 行）
                if ("turn_end".equals(subtype)) continue;
                if (text.isBlank()) continue;
                if (!merged.isEmpty()) {
                    Map<String, String> last = merged.get(merged.size() - 1);
                    // user 消息不合并：每条用户消息独立显示（DB 中 user 消息 subtype 为 NULL）
                    if (last.get("role").equals(role) && last.getOrDefault("subtype", "").equals(subtype)
                            && !"user".equals(role)) {
                        // 合并到上一条
                        last.put("text", last.get("text") + text);
                        continue;
                    }
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("role", role);
                entry.put("subtype", subtype);
                entry.put("text", text);
                merged.add(entry);
            }
            history = merged;
        } catch (Exception e) {
            log.error("[HTTP][init] Failed to load history for convId={}", convId, e);
        }

        List<Map<String, Object>> convList = buildConversationList(deviceId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        boolean isWarmingUp = curMgr != null && curMgr.isWarmupRoundActive();

        result.put("convId", convId);
        result.put("cursor", cursor);
        result.put("processing", isProcessing);
        result.put("processingStartMs", processingStartMs);
        result.put("warmingUp", isWarmingUp);
        result.put("totalInputTokens", totalInput);
        result.put("totalOutputTokens", totalOutput);
        result.put("conversations", convList);
        result.put("activeTmuxSessions", countActiveTmuxSessions());
        result.put("history", history);

        log.info("[HTTP][init] ← convId={} cursor={} processing={} warmingUp={} processingStartMs={} historySize={} convs={}",
            convId, cursor, isProcessing, isWarmingUp, processingStartMs, history.size(), convList.size());
        return result;
    }

    /**
     * HTTP stream：返回 cursor 之后的新增 entries。
     */
    // 长轮询：httpStream 空结果时在 buffer 上阻塞等待的最大时长
    private static final long LONG_POLL_WAIT_MS = 10_000L;

    public Map<String, Object> httpStream(String convId, long cursor) {
        ClaudeOutputBuffer buf = outputBuffers.get(convId);
        if (buf == null) {
            // buffer 为空说明后端重启过（内存已清），触发客户端 re-init 重建会话
            AtomicBoolean cp = convProcessingMap.get(convId);
            log.info("[HTTP][stream] no buffer (backend restarted?) convId={} cursor={} convProcessing={}, returning cursorExpired", convId, cursor, cp != null ? cp.get() : "null");
            return Map.of("entries", List.of(), "nextCursor", cursor, "cursorExpired", true);
        }
        // cursor 过期（极少情况：buffer 被裁剪超过 MAX_SIZE）
        if (cursor > 0 && buf.getMinSeqId() > cursor + 1) {
            log.warn("[HTTP][stream] cursor expired convId={} cursor={} minSeqId={}", convId, cursor, buf.getMinSeqId());
            return Map.of("entries", List.of(), "nextCursor", cursor, "cursorExpired", true);
        }
        // 长轮询：若立即有结果直接返回；否则在 buffer 上阻塞最多 LONG_POLL_WAIT_MS 等待新 entry
        List<ClaudeOutputBuffer.OutputEntry> entries = buf.awaitSince(cursor, LONG_POLL_WAIT_MS);
        long nextCursor = entries.isEmpty() ? cursor : entries.get(entries.size() - 1).seqId();
        AtomicBoolean convProcessing = convProcessingMap.get(convId);
        boolean isProcessing = (convProcessing != null && convProcessing.get())
            || (processMap.get(convId) != null && processMap.get(convId).isCurrentlyProcessing());
        if (!entries.isEmpty()) {
            // 有新 entries 时打 INFO
            String types = entries.stream()
                .map(e -> e.type() + (e.subtype().isEmpty() ? "" : "/" + e.subtype()))
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
            log.info("[HTTP][stream] convId={} cursor={}→{} entries={} types=[{}] isProcessing={}",
                convId, cursor, nextCursor, entries.size(), types, isProcessing);
        } else {
            // 空 poll 降为 DEBUG，但每 60 次打一次 INFO 作为心跳（防止日志完全消失）
            long pollCount = buf.incrementEmptyPollCount();
            if (pollCount % 60 == 1) {
                log.info("[HTTP][stream] empty heartbeat convId={} cursor={} isProcessing={} emptyPolls={}",
                    convId, cursor, isProcessing, pollCount);
            } else {
                log.debug("[HTTP][stream] empty convId={} cursor={} isProcessing={}", convId, cursor, isProcessing);
            }
        }
        return Map.of("entries", entries, "nextCursor", nextCursor);
    }

    /**
     * HTTP sendMessage：向当前 convId 的 claude 进程发送消息。
     */
    public Map<String, Object> httpSendMessage(String deviceId, String convId, String text, List<String> fileIds) {
        if (convId == null || convId.isBlank()) return Map.of("error", "convId is required");
        String preview = text != null && text.length() > 60
            ? text.substring(0, 60).replace("\n", "↵") + "…" : (text != null ? text.replace("\n", "↵") : "");
        log.info("[HTTP][send] convId={} text=\"{}\" fileIds={}", convId, preview, fileIds != null ? fileIds.size() : 0);

        ClaudeOutputBuffer buf = outputBuffers.computeIfAbsent(convId, k -> new ClaudeOutputBuffer());
        AtomicBoolean convProcessing = convProcessingMap.computeIfAbsent(convId, k -> new AtomicBoolean(false));

        if (!convProcessing.compareAndSet(false, true)) {
            log.warn("[HTTP][send] rejected: already processing convId={}", convId);
            return Map.of("error", "请等待上一条指令完成");
        }

        TmuxProcessManager mgr = processMap.get(convId);
        if (mgr == null) {
            // idle-kill 或后端重启可能导致 processMap 为空。若 DB 里有 claudeSessionId，
            // 触发一次 auto-resume（复用 httpInit 的 resume 分支），等 warmup 完成后继续发。
            ClaudeConversation convForResume = claudeConversationMapper.findByConvId(convId);
            if (convForResume != null && convForResume.getClaudeSessionId() != null) {
                String sid = convForResume.getClaudeSessionId();
                log.info("[HTTP][send] mgr missing, triggering auto-resume convId={} sessionId={}",
                        convId, sid.substring(0, Math.min(8, sid.length())));
                try {
                    httpInit(deviceId, convId, convForResume.getAccountMode());
                } catch (Exception e) {
                    log.error("[HTTP][send] auto-resume httpInit failed convId={}", convId, e);
                    convProcessing.set(false);
                    buf.append("resume_failed", "", "auto-resume: " + e.getMessage(), 0, 0);
                    return Map.of("error", "会话恢复失败: " + e.getMessage());
                }
                // 等待 resume 真正就绪：processMap 有 mgr 且 warmup 完成
                long deadline = System.currentTimeMillis() + 20_000L;
                while (System.currentTimeMillis() < deadline) {
                    TmuxProcessManager candidate = processMap.get(convId);
                    if (candidate != null && !candidate.isWarmupRoundActive()) {
                        mgr = candidate;
                        break;
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
                if (mgr == null) {
                    TmuxProcessManager candidate = processMap.get(convId);
                    long waited = System.currentTimeMillis() - (deadline - 20_000L);
                    log.warn("[HTTP][send] auto-resume timeout convId={} waitedMs={} hasMgr={} warmupActive={}",
                            convId, waited, candidate != null,
                            candidate != null && candidate.isWarmupRoundActive());
                    convProcessing.set(false);
                    return Map.of("error", "会话恢复超时，请重试");
                }
                log.info("[HTTP][send] auto-resume ready convId={}, proceeding with send", convId);
            } else {
                convProcessing.set(false);
                log.warn("[HTTP][send] rejected: no mgr, no resumable sessionId convId={} convExists={}",
                        convId, convForResume != null);
                return Map.of("error", "claude 进程未运行");
            }
        }

        mgr.startTailingIfNotRunning(mgr.getTailOffset());

        if (!mgr.isSessionAlive()) {
            log.warn("[HTTP] tmux dead before send convId={}", convId);
            convProcessing.set(false);
            processMap.remove(convId);
            buf.append("error", "system", "claude 进程已退出，请重新 init\n", 0, 0);
            buf.append("turn_end", "", "", 0, 0);
            return Map.of("error", "claude 进程已退出");
        }

        // 发消息前检查 session conflict（PC 端可能在 init 之后才 resume 同一会话）
        try {
            ClaudeConversation convForConflict = claudeConversationMapper.findByConvId(convId);
            if (convForConflict != null && convForConflict.getClaudeSessionId() != null
                    && detectAndReportConflict(convForConflict.getClaudeSessionId(), false, buf, false, "send", convId)) {
                convProcessing.set(false);
                return Map.of("error", "session_conflict");
            }
        } catch (Exception e) {
            log.error("[HTTP][send] conflict check failed convId={}", convId, e);
        }

        boolean hasFiles = fileIds != null && !fileIds.isEmpty();
        final String displayText = hasFiles
                ? (text != null ? text.strip() : "") + (text == null || text.isBlank() ? "" : " ") + "（附带了 " + fileIds.size() + " 张图片）"
                : (text != null ? text.strip() : "");

        // slash 命令（/context、/compact 等）走临时进程执行，不发给主进程
        // 原因：主进程以 --input-format=stream-json 运行，入队时 skipSlashCommands=true，
        // slash 命令会被当普通文本发给模型，无法触发 Claude Code 内部命令处理逻辑。
        if (text != null && text.strip().startsWith("/")) {
            final String slashCommand = text.strip();
            final AtomicBoolean convProcessingRef = convProcessing;
            final TmuxProcessManager mgrRef = mgr;
            CompletableFuture.runAsync(() ->
                executeSlashCommandInTempProcess(convId, slashCommand, displayText, buf, convProcessingRef, mgrRef)
            );
            long userInputSeqId = buf.append("output", "user_input", displayText, 0, 0);
            log.info("[HTTP][send][slash] queued slash command convId={} cmd={}", convId, slashCommand);
            return Map.of("ok", true, "userInputSeqId", userInputSeqId);
        }

        if (convId != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    claudeMessageMapper.insert(convId, "user", displayText, System.currentTimeMillis());
                    claudeConversationMapper.updateLastActive(convId, System.currentTimeMillis());
                    claudeConversationMapper.updateProcessing(convId, true);
                } catch (Exception e) {
                    log.error("[HTTP] Failed to save user message convId={}", convId, e);
                }
            });
        }

        long userInputSeqId = buf.append("output", "user_input", displayText, 0, 0);

        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (fileIds != null && !fileIds.isEmpty()) {
            for (String fileId : fileIds) {
                try {
                    String path = fileStorage.resolvePath(fileId);
                    contentBlocks.add(Map.of("type", "text", "text", "[图片文件：" + path + "]"));
                } catch (Exception e) {
                    log.error("[HTTP] Failed to attach file {} convId={}", fileId, convId, e);
                }
            }
        }
        if (text != null && !text.strip().isEmpty()) {
            contentBlocks.add(Map.of("type", "text", "text", text.strip()));
        }

        boolean sent = mgr.sendMessageWithContent(contentBlocks);
        if (!sent) {
            convProcessing.set(false);
            log.warn("[HTTP][send] FAILED to write FIFO convId={}", convId);
            buf.append("output", "system", "[消息发送失败，claude 进程可能已退出，请重试]\n", 0, 0);
            buf.append("turn_end", "", "", 0, 0);
            return Map.of("error", "消息发送失败");
        }

        log.info("[HTTP][send] OK convId={} userInputSeqId={} isCurrentlyProcessing={}", convId, userInputSeqId, mgr.isCurrentlyProcessing());
        return Map.of("ok", true, "userInputSeqId", userInputSeqId);
    }

    /**
     * 用临时 claude 进程执行 slash 命令（/context、/compact 等）。
     * 主进程以 stream-json 模式运行，入队时强制 skipSlashCommands=true，
     * 所以 slash 命令必须通过 `echo "cmd" | claude -p --resume <sessionId>` 独立执行。
     */
    private void executeSlashCommandInTempProcess(
            String convId, String slashCommand, String displayText,
            ClaudeOutputBuffer buf, AtomicBoolean convProcessing, TmuxProcessManager mgr) {
        log.info("[slash] executing convId={} cmd={}", convId, slashCommand);
        final String[] outputHolder = {""};
        try {
            // 取 claude session id（用于 --resume）
            ClaudeConversation conv = claudeConversationMapper.findByConvId(convId);
            if (conv == null || conv.getClaudeSessionId() == null) {
                log.error("[slash] no sessionId for convId={}", convId);
                buf.append("output", "text", "[slash 命令执行失败：找不到 claude session id]\n", 0, 0);
                buf.append("turn_end", "", "", 0, 0);
                convProcessing.set(false);
                return;
            }
            String sessionId = conv.getClaudeSessionId();

            // 构造命令：echo "/context" | claude -p --resume <sessionId>
            // 需要 source envFile（企业版）、设置 proxy 环境变量
            String envSourcePrefix = "";
            if (mgr.getEnvFile() != null && !mgr.getEnvFile().isBlank()) {
                envSourcePrefix = "source " + mgr.getEnvFile() + " && ";
            }
            StringBuilder envPrefix = new StringBuilder("NO_COLOR=1 ");
            if (mgr.getProxy() != null && !mgr.getProxy().isBlank()) {
                envPrefix.append("HTTP_PROXY=").append(mgr.getProxy()).append(" ");
                envPrefix.append("HTTPS_PROXY=").append(mgr.getProxy()).append(" ");
            }
            // shell-safe：单引号包裹命令，内部单引号转义
            String quotedCmd = "'" + slashCommand.replace("'", "'\\''") + "'";
            String shellCmd = "cd " + mgr.getWorkDir() + " && "
                    + envSourcePrefix
                    + envPrefix
                    + "echo " + quotedCmd + " | "
                    + TmuxProcessManager.CLAUDE_BIN + " -p --resume " + sessionId;
            log.info("[slash] shell cmd: {}", shellCmd);

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", shellCmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            // 读 stdout
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString().strip();
            }

            // 读 stderr（仅打日志，不展示给用户）
            String stderr;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                stderr = sb.toString().strip();
            }

            boolean exited = proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            int exitCode = exited ? proc.exitValue() : -1;
            log.info("[slash] done convId={} cmd={} exitCode={} outputLen={} stderr={}",
                    convId, slashCommand, exitCode, output.length(),
                    stderr.isEmpty() ? "(empty)" : stderr.substring(0, Math.min(200, stderr.length())));

            if (!exited) {
                proc.destroyForcibly();
                log.error("[slash] timeout convId={} cmd={}", convId, slashCommand);
                outputHolder[0] = "[slash 命令超时（300s）]";
            } else if (output.isEmpty()) {
                outputHolder[0] = "[" + slashCommand + " 执行完毕，无文本输出]";
            } else {
                outputHolder[0] = output;
            }
            buf.append("output", "text", outputHolder[0] + "\n", 0, 0);
        } catch (Exception e) {
            log.error("[slash] error convId={} cmd={}", convId, slashCommand, e);
            outputHolder[0] = "[slash 命令执行出错：" + e.getMessage() + "]";
            buf.append("output", "text", outputHolder[0] + "\n", 0, 0);
        } finally {
            buf.append("turn_end", "", "", 0, 0);
            convProcessing.set(false);
            // 更新 DB 状态
            try {
                claudeConversationMapper.updateProcessing(convId, false);
                claudeConversationMapper.updateLastActive(convId, System.currentTimeMillis());
                claudeMessageMapper.insert(convId, "assistant", outputHolder[0], System.currentTimeMillis());
            } catch (Exception e) {
                log.error("[slash] failed to update DB convId={}", convId, e);
            }
        }
    }

    /**
     * HTTP newConversation：新建会话，返回新会话的 init 数据（含 cursor）。
     */
    public Map<String, Object> httpNewConversation(String deviceId, String accountMode) {
        return httpNewConversation(deviceId, accountMode, null);
    }

    public Map<String, Object> httpNewConversation(String deviceId, String accountMode, String workDir) {
        if (countActiveTmuxSessions() >= 10) {
            return Map.of("error", "活跃会话已达上限（10个），请先删除不需要的会话再新建。");
        }
        ClaudeConversation conv = createNewConversation(deviceId, workDir);
        return httpInit(deviceId, conv.getConvId(), accountMode);
    }

    /**
     * HTTP switchConversation：切换到指定会话，返回该会话的 init 数据。
     */
    public Map<String, Object> httpSwitchConversation(String deviceId, String convId, String accountMode) {
        return httpInit(deviceId, convId, accountMode);
    }

    /**
     * HTTP deleteConversation：删除会话（DB + tmux），返回更新后的会话列表。
     */
    public Map<String, Object> httpDeleteConversation(String deviceId, String convId) {
        if (convId == null || convId.isBlank()) return Map.of("error", "convId is required");
        log.info("[HTTP][delete] → convId={} deviceId={}", convId, deviceId);

        claudeMessageMapper.deleteByConvId(convId);
        claudeConversationMapper.deleteByConvId(convId);
        log.info("[HTTP][delete] DB deleted convId={}", convId);

        TmuxProcessManager mgr = processMap.remove(convId);
        if (mgr != null) {
            log.info("[HTTP][delete] killing tmux convId={} isAlive={}", convId, mgr.isSessionAlive());
            mgr.killTmuxSession();
        }
        outputBuffers.remove(convId);
        convProcessingMap.remove(convId);
        accountMap.remove(convId);

        List<Map<String, Object>> convList = buildConversationList(deviceId);
        int active = countActiveTmuxSessions();
        log.info("[HTTP][delete] ← remaining convs={} activeTmux={}", convList.size(), active);
        return Map.of("conversations", convList, "activeTmuxSessions", active);
    }

    /**
     * HTTP killCurrentSession：kill 当前会话 tmux 进程（不删 DB）。
     */
    public Map<String, Object> httpKillCurrentSession(String deviceId, String convId) {
        if (convId == null) return Map.of("error", "convId is required");
        log.info("[HTTP][kill] → convId={}", convId);
        ClaudeOutputBuffer buf = outputBuffers.get(convId);
        AtomicBoolean convProcessing = convProcessingMap.get(convId);
        if (convProcessing != null) convProcessing.set(false);

        TmuxProcessManager mgr = processMap.get(convId);
        if (mgr != null) {
            log.info("[HTTP][kill] killing tmux convId={} isAlive={}", convId, mgr.isSessionAlive());
            mgr.silenceOutput();
            mgr.killTmuxSession();
            processMap.remove(convId);
        } else {
            log.info("[HTTP][kill] no mgr in processMap convId={}", convId);
        }
        if (buf != null) {
            buf.append("turn_end", "", "", 0, 0);
            buf.append("output", "system", "\n[已终止当前会话进程，下次发消息时将自动重建]\n", 0, 0);
        }
        log.info("[HTTP][kill] ← done convId={}", convId);
        return Map.of("ok", true, "conversations", buildConversationList(deviceId));
    }

    /**
     * HTTP killAllSessions：kill 所有 tmux session（不删 DB）。
     */
    public Map<String, Object> httpKillAllSessions(String deviceId) {
        int mapSize = processMap.size();
        log.info("[HTTP][killAll] → deviceId={} processMapSize={}", deviceId, mapSize);
        new ArrayList<>(processMap.entrySet()).forEach(entry -> {
            log.info("[HTTP][killAll] killing convId={}", entry.getKey());
            entry.getValue().killTmuxSession();
            processMap.remove(entry.getKey());
        });
        // 兜底 kill 系统里所有 claude_ tmux session
        try {
            Process listProc = new ProcessBuilder("bash", "-c",
                    "tmux list-sessions -F '#{session_name}' 2>/dev/null")
                .redirectErrorStream(true).start();
            String allSessions = new String(listProc.getInputStream().readAllBytes()).trim();
            listProc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            for (String s : allSessions.split("\n")) {
                String name = s.trim();
                if (name.startsWith("claude_")) {
                    try {
                        new ProcessBuilder("bash", "-c", "tmux kill-session -t " + name)
                            .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        log.info("[HTTP] Killed orphan tmux session: {}", name);
                    } catch (Exception ex) {
                        log.warn("[HTTP] Failed to kill tmux session {}: {}", name, ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[HTTP] Failed to list/kill tmux sessions: {}", e.getMessage());
        }
        // 通知所有 buffer turn_end 解除 loading
        outputBuffers.forEach((cid, b) -> b.append("turn_end", "", "", 0, 0));
        return Map.of("ok", true, "conversations", buildConversationList(deviceId),
                "activeTmuxSessions", countActiveTmuxSessions());
    }

    /**
     * HTTP interrupt：向当前 convId 的 tmux session 发送 Ctrl+C。
     */
    public Map<String, Object> httpInterrupt(String convId) {
        TmuxProcessManager mgr = convId != null ? processMap.get(convId) : null;
        if (mgr == null) return Map.of("error", "无正在运行的 claude 进程");
        log.info("[HTTP] Interrupt convId={}", convId);
        mgr.sendInterrupt();
        return Map.of("ok", true);
    }

    private static final long IDLE_KILL_THRESHOLD_MS = 60 * 60 * 1000L; // 1小时

    /**
     * 定时清理空闲的 claude tmux session（每 5 分钟执行一次）：
     * 1. 扫 processMap：对后端正在管理的 session，用 CAS 与 httpSendMessage 互斥，超时则 kill
     * 2. 扫系统孤儿 session：后端重启后 processMap 为空，但系统里仍有 claude_* tmux session，
     *    通过 session 名反查 DB lastActiveAt，超时则直接 kill tmux（无需 processMap 中有记录）
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void killIdleSessions() {
        long now = System.currentTimeMillis();

        // ── Part 1：扫 processMap（正在管理的 session）──────────────────
        if (!processMap.isEmpty()) {
            log.info("[idle-kill] scanning {} managed session(s)", processMap.size());
            for (String convId : new ArrayList<>(processMap.keySet())) {
                AtomicBoolean convProcessing = convProcessingMap.get(convId);
                if (convProcessing == null) continue;
                if (!convProcessing.compareAndSet(false, true)) {
                    log.debug("[idle-kill] convId={} is processing, skip", convId);
                    continue;
                }
                try {
                    ClaudeConversation conv = claudeConversationMapper.findByConvId(convId);
                    if (conv == null) { processMap.remove(convId); continue; }
                    long idleMs = now - conv.getLastActiveAt();
                    if (idleMs < IDLE_KILL_THRESHOLD_MS) {
                        log.debug("[idle-kill] convId={} idle={}min, skip", convId, idleMs / 60000);
                        continue;
                    }
                    TmuxProcessManager mgr = processMap.remove(convId);
                    if (mgr != null) {
                        log.info("[idle-kill] killing managed idle session convId={} idleMin={}", convId, idleMs / 60000);
                        mgr.silenceOutput();
                        mgr.killTmuxSession();
                        ClaudeOutputBuffer buf = outputBuffers.get(convId);
                        if (buf != null) buf.append("disconnected", "system",
                            "\n[" + ts() + "] 会话因超过1小时无操作已自动关闭，下次打开将自动恢复\n", 0, 0);
                    }
                } catch (Exception e) {
                    log.error("[idle-kill] error checking convId={}", convId, e);
                } finally {
                    convProcessing.set(false);
                }
            }
        }

        // ── Part 2：扫系统孤儿 session（后端重启后 processMap 为空的场景）──
        try {
            Process listProc = new ProcessBuilder("bash", "-c",
                    "tmux list-sessions -F '#{session_name}' 2>/dev/null | grep '^claude_'")
                .redirectErrorStream(true).start();
            String out = new String(listProc.getInputStream().readAllBytes()).trim();
            listProc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (out.isEmpty()) return;

            // 从 DB 拉所有会话，建立 tmuxSessionName → conv 的映射
            List<ClaudeConversation> allConvs = claudeConversationMapper.findAll();
            Map<String, ClaudeConversation> tmuxNameToConv = new java.util.HashMap<>();
            for (ClaudeConversation c : allConvs) {
                String name = "claude_" + c.getConvId().replace("-", "").substring(0, 8);
                tmuxNameToConv.put(name, c);
            }

            for (String rawSession : out.split("\n")) {
                final String sessionName = rawSession.trim();
                if (sessionName.isEmpty()) continue;
                boolean managedByProcessMap = processMap.keySet().stream().anyMatch(cid ->
                        sessionName.equals("claude_" + cid.replace("-", "").substring(0, 8)));
                if (managedByProcessMap) continue; // processMap 已管理，Part 1 已处理
                ClaudeConversation conv = tmuxNameToConv.get(sessionName);
                if (conv == null) {
                    log.warn("[idle-kill] orphan tmux session {} not in DB, skipping", sessionName);
                    continue;
                }
                long idleMs = now - conv.getLastActiveAt();
                if (idleMs < IDLE_KILL_THRESHOLD_MS) {
                    log.debug("[idle-kill] orphan {} idle={}min, skip", sessionName, idleMs / 60000);
                    continue;
                }
                log.info("[idle-kill] killing orphan tmux session={} convId={} idleMin={}",
                    sessionName, conv.getConvId(), idleMs / 60000);
                new ProcessBuilder("tmux", "kill-session", "-t", sessionName)
                    .start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("[idle-kill] error scanning orphan sessions", e);
        }
    }

    /**
     * 检测 session 冲突并将结果写入 buffer。
     * @param sessionId      要检测的 claude session id
     * @param killResiduals  是否 kill 后端残留进程（重建路径传 true，attach 路径传 false）
     * @param buf            输出 buffer
     * @param prependConnected  是否在 session_conflict 前先写一条 connected（init 路径需要，send 路径不需要）
     * @param context        日志上下文标识（如 "attach"、"rebuild"、"send"）
     * @param convId         日志用
     * @return true 表示检测到冲突
     */
    private boolean detectAndReportConflict(String sessionId, boolean killResiduals,
            ClaudeOutputBuffer buf, boolean prependConnected, String context, String convId) {
        List<String> pids = checkSessionConflict(sessionId, killResiduals);
        if (pids.isEmpty()) return false;
        log.warn("[HTTP][{}] session_conflict detected convId={} pids={}", context, convId, pids);
        if (prependConnected) buf.append("connected", "", "", 0, 0);
        buf.append("session_conflict", "",
                "\n⚠️ 该会话正在被其他进程占用（PID: " + String.join(", ", pids) + "），无法发送消息。\n请关闭 PC 端的 Claude 进程后重新连接。\n",
                0, 0);
        return true;
    }

    /**
     * 检测并处理 claude session 的进程冲突：
     * 1. 带 --input-format=stream-json 的进程：后端残留进程，killBackendResiduals=true 时直接 kill
     * 2. 不带 --input-format=stream-json 的进程：PC 端手动启动的进程，报 session conflict
     * 返回 PC 端冲突进程的 PID 列表，为空表示无冲突。
     *
     * @param killBackendResiduals 是否 kill 后端残留进程。
     *   tmux 存活的 attach 路径（Branch 1a）传 false：此时带 stream-json 的进程是活跃的 tmux 进程，不能 kill。
     *   重建路径（Branch 1b/2b）传 true：需要清理旧的残留进程。
     */
    private List<String> checkSessionConflict(String claudeSessionId, boolean killBackendResiduals) {
        String sessionShort = claudeSessionId.substring(0, Math.min(8, claudeSessionId.length()));
        try {
            // 一次性用 ps aux 获取所有进程，避免多次 pgrep 的竞争条件
            Process psAll = new ProcessBuilder("ps", "aux").redirectErrorStream(true).start();
            String psOutput = new String(psAll.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            psAll.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            Set<String> backendPidSet = new HashSet<>();
            List<String> manualPids = new ArrayList<>();

            for (String line : psOutput.split("\n")) {
                if (!line.contains(claudeSessionId)) continue;

                // 解析 PID（第二列）
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 11) continue;
                String pid = parts[1];

                // 判断进程类型
                boolean isBackend = line.contains("--input-format=stream-json");
                // shell 包装进程（bash/sh/zsh 命令行里含有 sessionId 是因为它是父进程）
                String comm = parts[10]; // COMMAND 列第一个 token
                boolean isShell = comm.endsWith("/bash") || comm.equals("bash")
                        || comm.endsWith("/sh") || comm.equals("sh")
                        || comm.endsWith("/zsh") || comm.equals("zsh");

                // info 级别打出完整命令行，便于排查误判
                log.info("[ConflictCheck] pid={} isBackend={} isShell={} comm={} cmdline=\"{}\"", pid, isBackend, isShell, comm, line);

                if (isBackend) {
                    backendPidSet.add(pid);
                } else if (!isShell) {
                    // 既不是后端进程也不是 shell 包装 → PC 端手动进程
                    manualPids.add(pid);
                }
            }

            // kill 后端残留进程（仅重建路径）
            if (!backendPidSet.isEmpty()) {
                if (killBackendResiduals) {
                    log.warn("[ConflictCheck] Killing {} backend residual PID(s)={} for sessionId={}",
                            backendPidSet.size(), backendPidSet, sessionShort);
                    for (String pid : backendPidSet) {
                        try {
                            new ProcessBuilder("kill", "-9", pid).start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ex) {
                            log.warn("[ConflictCheck] Failed to kill pid={}: {}", pid, ex.getMessage());
                        }
                    }
                } else {
                    log.info("[ConflictCheck] Skipping kill of {} backend PID(s)={} for sessionId={} (tmux alive, attach path)",
                            backendPidSet.size(), backendPidSet, sessionShort);
                }
            }

            if (!manualPids.isEmpty()) {
                log.warn("[ConflictCheck] Found {} manual (PC) PID(s)={} for sessionId={}",
                        manualPids.size(), manualPids, sessionShort);
            }
            return manualPids;
        } catch (Exception e) {
            log.warn("[ConflictCheck] Failed to check session conflict for sessionId={}: {}", sessionShort, e.getMessage());
            return List.of();
        }
    }

    /** 构建会话列表（不依赖 WS session） */
    public List<Map<String, Object>> buildConversationList(String deviceId) {
        if (deviceId == null) return List.of();
        try {
            List<ClaudeConversation> all = claudeConversationMapper.findAllByDevice(deviceId);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ClaudeConversation c : all) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("convId", c.getConvId());
                item.put("name", c.getName());
                item.put("createdAt", c.getCreatedAt());
                item.put("claudeSessionId", c.getClaudeSessionId());
                String tmuxSession = "claude_" + c.getConvId().replace("-", "").substring(0, 8);
                item.put("tmuxAlive", isTmuxSessionAlive(tmuxSession));
                // 优先用内存 map（进程刚创建时最新），map 无记录时从 DB 读（后端重启场景）
                item.put("accountMode", accountMap.getOrDefault(c.getConvId(), c.getAccountMode()));
                // 会话 workDir：null 表示用全局默认（UI 按 fallback 显示）
                item.put("workDir", c.getWorkDir() != null ? c.getWorkDir() : workDir);
                item.put("workDirIsDefault", c.getWorkDir() == null);
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("[HTTP] Failed to build conversation list for deviceId={}", deviceId, e);
            return List.of();
        }
    }

    // ── Hook 事件处理 ─────────────────────────────────────────────────

    /**
     * 处理 Claude Code Hook 推送的事件（Stop / Notification）。
     * Hook 通道只做推送通知，不参与数据流（数据流由 tail 线程独占）。
     */
    public void handleHookEvent(String convId, String event, String data) {
        log.info("[hook] event={} convId={} dataLen={}", event, convId,
                data != null ? data.length() : 0);

        ClaudeConversation conv = claudeConversationMapper.findByConvId(convId);
        if (conv == null) {
            log.warn("[hook] conversation not found for convId={}", convId);
            return;
        }
        String deviceId = conv.getDeviceId();

        switch (event) {
            case "stop" -> {
                pushWebSocketHandler.pushToDevice(deviceId, "Claude", "任务已完成");
            }
            case "notification" -> {
                String message = "需要你的输入";
                if (data != null && !data.isBlank()) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(data);
                        if (node.has("message")) {
                            String msg = node.get("message").asText();
                            if (!msg.isBlank()) message = msg;
                        }
                    } catch (Exception ignored) {}
                }
                pushWebSocketHandler.pushToDevice(deviceId, "Claude 需要输入", message);
            }
            default -> log.warn("[hook] unknown event type: {}", event);
        }
    }
}
