package com.xinjian.capsulecode.ui.claude

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjian.capsulecode.data.network.ApiService
import com.xinjian.capsulecode.data.network.ClaudeApiClient
import com.xinjian.capsulecode.data.network.ConversationInfo
import com.xinjian.capsulecode.data.repository.SettingsRepository
import com.xinjian.capsulecode.util.RemoteLogger
import com.xinjian.capsulecode.util.XfyunAsrManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import org.json.JSONObject

@HiltViewModel
class ClaudeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val claudeApiClient: ClaudeApiClient,
    private val settingsRepository: SettingsRepository,
    private val apiService: ApiService
) : ViewModel() {

    private val deviceId: String by lazy {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    val serverIp = settingsRepository.serverIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULT_IP
    )

    val accountMode = settingsRepository.accountMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "max"
    )

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    data class OutputSegment(val text: String, val subtype: String, val id: Long = segIdCounter.incrementAndGet())

    companion object {
        private val segIdCounter = java.util.concurrent.atomic.AtomicLong(0)
        private const val NOTIF_ID_CLAUDE_TURN_END = 2001
        // 后端已改为长轮询（空结果阻塞最多 10s），这里仅作兜底防热循环
        private const val POLL_INTERVAL_MS = 200L
        private const val POLL_ERROR_RETRY_MS = 3000L
        private const val TAG = "ClaudeViewModel"

        private val THINKING_BLOCK_REGEX = Regex(
            """<thinking>(.*?)</thinking>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

    }

    private val _segments = MutableStateFlow<List<OutputSegment>>(emptyList())
    val segments: StateFlow<List<OutputSegment>> = _segments


    // context 信息：workDir、branch、changedFiles
    data class ClaudeContext(val workDir: String, val branch: String, val changedFiles: Int)
    private val _context = MutableStateFlow<ClaudeContext?>(null)
    val context: StateFlow<ClaudeContext?> = _context

    // 当前会话累计 token 用量
    private val _totalInputTokens = MutableStateFlow(0L)
    val totalInputTokens: StateFlow<Long> = _totalInputTokens
    private val _totalOutputTokens = MutableStateFlow(0L)
    val totalOutputTokens: StateFlow<Long> = _totalOutputTokens

    // 会话恢复失败
    private val _resumeFailed = MutableStateFlow(false)
    val resumeFailed: StateFlow<Boolean> = _resumeFailed
    // 恢复失败的原因文本（用于区分 limit 超限等特殊情况）
    private val _resumeFailedReason = MutableStateFlow<String?>(null)
    val resumeFailedReason: StateFlow<String?> = _resumeFailedReason

    // sessionConflict 已无意义（HTTP 无连接竞争），保留字段兼容 ClaudeScreen
    private val _sessionConflict = MutableStateFlow(false)
    val sessionConflict: StateFlow<Boolean> = _sessionConflict

    // 会话相关状态
    private val _currentConvId = MutableStateFlow<String?>(null)
    val currentConvId: StateFlow<String?> = _currentConvId

    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations: StateFlow<List<ConversationInfo>> = _conversations

    private val _activeTmuxSessions = MutableStateFlow(0)
    val activeTmuxSessions: StateFlow<Int> = _activeTmuxSessions

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching

    private val _switchingToConvId = MutableStateFlow<String?>(null)
    val switchingToConvId: StateFlow<String?> = _switchingToConvId

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _isWarmingUp = MutableStateFlow(false)
    val isWarmingUp: StateFlow<Boolean> = _isWarmingUp

    private val _processingStartMs = MutableStateFlow<Long?>(null)
    val processingStartMs: StateFlow<Long?> = _processingStartMs

    // 语音识别
    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording: StateFlow<Boolean> = _isVoiceRecording
    // 点击麦克风模式：实时转写到输入框，不弹浮层；长按输入框模式：弹浮层+松开发送
    private val _voiceTapMode = MutableStateFlow(false)
    val voiceTapMode: StateFlow<Boolean> = _voiceTapMode

    private val _voicePartialText = MutableStateFlow<String?>(null)
    val voicePartialText: StateFlow<String?> = _voicePartialText

    private val _voiceFinalText = MutableStateFlow<String?>(null)
    val voiceFinalText: StateFlow<String?> = _voiceFinalText

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError

    private var asrManagerInstance: XfyunAsrManager? = null
    // 取消语音录音标志：cancel 后 asr 仍可能继续推送最后的 partial/final，需全部丢弃
    @Volatile
    private var voiceCanceling: Boolean = false
    private val asrManager: XfyunAsrManager
        get() = asrManagerInstance ?: XfyunAsrManager(appContext).apply {
            onPartialResult = { text ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (!voiceCanceling) _voicePartialText.value = text
                }
            }
            onFinalResult = { text ->
                viewModelScope.launch(Dispatchers.Main) {
                    val canceled = voiceCanceling
                    voiceCanceling = false
                    _isVoiceRecording.value = false
                    if (!canceled) _voiceFinalText.value = text
                }
            }
            onError = { msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    voiceCanceling = false
                    _isVoiceRecording.value = false
                    RemoteLogger.e(TAG, "语音识别错误：$msg")
                    _voiceError.value = msg
                }
            }
        }.also { asrManagerInstance = it }

    fun startVoiceInput(tapMode: Boolean = false) {
        if (_isVoiceRecording.value) return
        voiceCanceling = false
        _voiceTapMode.value = tapMode
        _voicePartialText.value = null
        _voiceFinalText.value = null
        _isVoiceRecording.value = true
        viewModelScope.launch(Dispatchers.IO) { asrManager.startRecording() }
    }

    fun stopVoiceInput() {
        viewModelScope.launch(Dispatchers.IO) { asrManager.stopRecording() }
    }

    // 取消录音：停止 ASR 并丢弃识别结果（partial/final 都不会 emit）
    fun cancelVoiceInput() {
        voiceCanceling = true
        _voicePartialText.value = null
        viewModelScope.launch(Dispatchers.IO) { asrManager.stopRecording() }
    }

    fun clearVoiceFinalText() {
        _voiceFinalText.value = null
    }

    fun clearVoiceError() {
        _voiceError.value = null
    }

    // 输入历史（最近50条）
    private val inputHistory = mutableListOf<String>()
    private val _inputHistorySize = MutableStateFlow(0)
    val inputHistorySizeFlow: StateFlow<Int> = _inputHistorySize
    private var inputHistoryConvId: String? = null

    // App 前后台状态
    var isAppInForeground: Boolean = true

    // 轮询协程
    private var pollJob: Job? = null
    private var currentCursor: Long = 0L
    private var sendStartMs: Long? = null
    // 正在飞行中的 sendMessage 请求数，用于防止 turn_end 竞态清掉 isProcessing
    private var pendingSendCount: Int = 0

    // 记录 init 时后端 processing 状态（轮询协程判断前台通知用）
    private var wasProcessingOnInit: Boolean = false

    // 每个 convId 的 processing 状态（纯客户端，不依赖服务端）
    private val convProcessingMap = mutableMapOf<String, Boolean>()
    private val convProcessingStartMap = mutableMapOf<String, Long>()
    private val _convProcessingStartMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val convProcessingStartMs: StateFlow<Map<String, Long>> = _convProcessingStartMs

    private fun syncConvProcessing(convId: String, processing: Boolean) {
        convProcessingMap[convId] = processing
        if (processing) {
            convProcessingStartMap[convId] = System.currentTimeMillis()
        } else {
            convProcessingStartMap.remove(convId)
        }
        _convProcessingStartMs.value = convProcessingStartMap.toMap()
        _conversations.value = _conversations.value.map { conv ->
            conv.copy(processing = convProcessingMap[conv.convId] ?: false)
        }
        RemoteLogger.d(TAG, "[STATE] syncConvProcessing convId=${convId.take(8)} processing=$processing activeCount=${convProcessingStartMap.size}")
    }

    /** applyInitResponse 刷新 _conversations 后调用，把已知状态重新覆盖上去 */
    private fun reapplyProcessingMap() {
        if (convProcessingMap.isEmpty()) return
        _conversations.value = _conversations.value.map { conv ->
            conv.copy(processing = convProcessingMap[conv.convId] ?: false)
        }
        RemoteLogger.d(TAG, "[STATE] reapplyProcessingMap entries=${convProcessingMap.size} active=${convProcessingMap.count { it.value }}")
    }

    init {
        // 监听当前会话的实时 processing/warmingUp 状态，自动同步到 conversations 列表
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                _isProcessing, _isWarmingUp, _currentConvId
            ) { processing, warming, convId -> Triple(processing || warming, convId, Unit) }
                .collect { (active, convId) ->
                    if (convId != null) syncConvProcessing(convId, active)
                }
        }
    }

    // ── 初始化与轮询 ──────────────────────────────────────────────────

    fun init(serverIp: String, convId: String? = null) {
        // 已连接且正在 poll 同一个会话时，跳过重新初始化（避免切 tab 回来卡顿）
        if (convId == null && _isConnected.value && pollJob?.isActive == true && _currentConvId.value != null) {
            RemoteLogger.d(TAG, "[STATE] init skipped: already connected, convId=${_currentConvId.value?.take(8)}")
            return
        }
        RemoteLogger.i(TAG, "[STATE] init serverIp=$serverIp convId=${convId?.take(8)} currentConvId=${_currentConvId.value?.take(8)}")
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = claudeApiClient.init(deviceId, convId ?: _currentConvId.value, accountMode.value)
                val isNewConv = resp.convId != _currentConvId.value
                withContext(Dispatchers.Main) { applyInitResponse(resp) }
                // re-init 同一个会话时保留 currentCursor，避免 buffer 历史内容被重复 poll
                if (isNewConv || currentCursor == 0L) {
                    currentCursor = resp.cursor
                } else {
                    RemoteLogger.i(TAG, "[STATE] re-init same conv, keep cursor=$currentCursor (resp.cursor=${resp.cursor})")
                }
                _isConnected.value = true
                // 空状态（convId=null）：不启动 polling，直接等用户点"开始新会话"
                if (resp.convId == null) {
                    RemoteLogger.i(TAG, "[STATE] empty state — no conversations, skip polling")
                } else {
                    fetchContext(serverIp)
                    startPolling(convId ?: resp.convId)
                }
            } catch (e: CancellationException) {
                throw e  // 协程被取消时正常退出，不改变 isConnected 状态
            } catch (e: Exception) {
                RemoteLogger.w(TAG, "[STATE] init failed, retry in ${POLL_ERROR_RETRY_MS}ms: ${e.message}")
                _isConnected.value = false
                delay(POLL_ERROR_RETRY_MS)
                if (isActive) init(serverIp, convId)
            }
        }
    }

    private var emptyPollCount: Long = 0L

    private fun startPolling(convId: String) {
        emptyPollCount = 0L
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            RemoteLogger.i(TAG, "[POLL] start polling convId=${convId.take(8)} cursor=$currentCursor isProcessing=${_isProcessing.value}")
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val currentConv = _currentConvId.value
                    if (currentConv == null) {
                        RemoteLogger.w(TAG, "[POLL] currentConvId is null, skip")
                        continue
                    }
                    val stream = claudeApiClient.pollStream(currentConv, currentCursor, deviceId)
                    if (stream.cursorExpired) {
                        RemoteLogger.w(TAG, "[POLL] cursor expired, re-init convId=${currentConv.take(8)} cursor=$currentCursor")
                        // buffer 被裁剪，从 DB 重建完整历史：清空 segments 使 applyInitResponse 强制重载
                        withContext(Dispatchers.Main) { _segments.value = emptyList() }
                        val resp = claudeApiClient.init(deviceId, currentConv, accountMode.value)
                        currentCursor = resp.cursor
                        withContext(Dispatchers.Main) { applyInitResponse(resp) }
                        _isConnected.value = true
                        continue
                    }
                    if (stream.entries.isNotEmpty()) {
                        emptyPollCount = 0L
                        currentCursor = stream.nextCursor
                        withContext(Dispatchers.Main) {
                            stream.entries.forEach { handleEntry(it) }
                        }
                    } else {
                        emptyPollCount++
                        // 每 60 次空 poll 打一次心跳日志（约 1 分钟）
                        if (emptyPollCount % 60 == 1L) {
                            RemoteLogger.i(TAG, "[POLL] empty heartbeat convId=${currentConv.take(8)} cursor=$currentCursor isProcessing=${_isProcessing.value} emptyPolls=$emptyPollCount")
                        }
                    }
                    if (!_isConnected.value) {
                        _isConnected.value = true
                        RemoteLogger.i(TAG, "[POLL] reconnected convId=${_currentConvId.value?.take(8)}")
                    }
                } catch (e: CancellationException) {
                    throw e  // 协程被取消时正常退出，不改变 isConnected 状态
                } catch (e: Exception) {
                    val wasConnected = _isConnected.value
                    RemoteLogger.w(TAG, "[POLL] error convId=${_currentConvId.value?.take(8)} wasConnected=$wasConnected cursor=$currentCursor isProcessing=${_isProcessing.value}: ${e.message}")
                    _isConnected.value = false
                    delay(POLL_ERROR_RETRY_MS)
                }
            }
            RemoteLogger.i(TAG, "[POLL] loop exited convId=${_currentConvId.value?.take(8)}")
        }
    }

    private fun applyInitResponse(resp: ClaudeApiClient.InitResponse) {
        // 空状态：没会话，清空所有 state，让 UI 渲染欢迎页
        if (resp.convId == null) {
            RemoteLogger.i(TAG, "[STATE] applyInitResponse empty state (no conversations)")
            _currentConvId.value = null
            _conversations.value = emptyList()
            _segments.value = emptyList()
            _activeTmuxSessions.value = 0
            _totalInputTokens.value = 0L
            _totalOutputTokens.value = 0L
            _isProcessing.value = false
            _processingStartMs.value = null
            _isWarmingUp.value = false
            _isSwitching.value = false
            _switchingToConvId.value = null
            _resumeFailed.value = false
            _resumeFailedReason.value = null
            _sessionConflict.value = false
            return
        }
        val isNewConv = resp.convId != _currentConvId.value
        RemoteLogger.i(TAG, "[STATE] applyInitResponse convId=${resp.convId.take(8)} isNewConv=$isNewConv processing=${resp.processing} cursor=${resp.cursor}")

        _currentConvId.value = resp.convId
        _conversations.value = resp.conversations
        reapplyProcessingMap()  // 服务端不再返回 processing，用本地 map 恢复已知状态
        _activeTmuxSessions.value = resp.activeTmuxSessions
        _totalInputTokens.value = resp.totalInputTokens
        _totalOutputTokens.value = resp.totalOutputTokens
        _resumeFailed.value = false
        _resumeFailedReason.value = null
        _sessionConflict.value = false
        _isSwitching.value = false
        _switchingToConvId.value = null

        // isWarmingUp 始终取服务端值，不受 processing 影响
        // 历史 bug：processing=false 时写死 isWarmingUp=false，导致 warmup 期间输入框可用，消息被丢弃
        _isWarmingUp.value = resp.warmingUp
        if (!resp.processing) {
            _isProcessing.value = false
            _processingStartMs.value = null
        } else {
            _isProcessing.value = true
            if (resp.processingStartMs > 0L) {
                _processingStartMs.value = resp.processingStartMs
            } else if (_processingStartMs.value == null) {
                _processingStartMs.value = System.currentTimeMillis()
            }
        }
        wasProcessingOnInit = resp.processing

        // 加载历史（新会话或切换会话时替换 segments）
        if (isNewConv || _segments.value.isEmpty()) {
            val segs = mutableListOf<OutputSegment>()
            resp.history.forEach { m ->
                val segSubtype = when (m.role) {
                    "user" -> "user_input"
                    "assistant" -> if (m.subtype.isNotBlank()) m.subtype else "text"
                    else -> "system"
                }
                // 历史记录里的 text segment 可能含有 <thinking> XML（DB 存的是原始流式文本），
                // 将 <thinking>...</thinking> 块拆成独立 thinking segment（与流式处理保持一致）
                if (segSubtype == "text") {
                    var text = m.text
                    val thinkingBlocks = mutableListOf<String>()
                    text = THINKING_BLOCK_REGEX.replace(text) { mr ->
                        thinkingBlocks.add(mr.groupValues[1].trim())
                        ""
                    }.trim()
                    thinkingBlocks.forEach { block -> segs.add(OutputSegment(block, "thinking")) }
                    if (text.isNotEmpty()) segs.add(OutputSegment(text, segSubtype))
                } else {
                    segs.add(OutputSegment(m.text, segSubtype))
                }
            }
            _segments.value = segs
        }

        // 输入历史：新会话时重新加载
        if (isNewConv) {
            inputHistory.clear()
            inputHistoryConvId = resp.convId
            _inputHistorySize.value = 0
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val messages = apiService.getMessages(resp.convId)
                    val userTexts = messages
                        .filter { it["role"] == "user" }
                        .mapNotNull { it["text"] as? String }
                        .filter { it.isNotBlank() }
                    inputHistory.addAll(userTexts.takeLast(50))
                    _inputHistorySize.value = inputHistory.size
                } catch (e: Exception) {
                    RemoteLogger.e(TAG, "Failed to load input history", e)
                }
            }
        }

    }

    private fun handleEntry(entry: ClaudeApiClient.OutputEntry) {
        when (entry.type) {
            "output" -> {
                val subtype = entry.subtype.ifEmpty { "text" }
                // user_input 由 sendInput 本地立即展示，poll 里跳过避免重复
                if (subtype == "user_input") return
                val preview = entry.text.take(60).replace("\n", "↵")
                RemoteLogger.i(TAG, "[ENTRY] output/$subtype seqId=${entry.seqId} \"$preview\"")
                val displayText = if (entry.text.trim() == "No response requested.")
                    "No response requested.（Claude 认为无需回复）"
                else entry.text
                val current = _segments.value
                val last = current.lastOrNull()
                // tool 合并规则：同 subtype 且新 chunk 不是新工具调用的 header（不以 [工具: 开头）才合并
                // 这样 header+JSON chunks 能合并成完整卡片，但不同工具调用不会混在一起
                val isNewToolCall = subtype == "tool" && displayText.trimStart().startsWith("[工具:")
                if (last != null && last.subtype == subtype && subtype in listOf("text", "thinking", "tool") && !isNewToolCall) {
                    _segments.value = current.dropLast(1) + last.copy(text = last.text + displayText)
                } else {
                    _segments.value = current + OutputSegment(displayText, subtype)
                }
            }
            "disconnected", "error" -> {
                RemoteLogger.i(TAG, "[ENTRY] ${entry.type} seqId=${entry.seqId} text=\"${entry.text.take(60).replace("\n","↵")}\"")
                _segments.value += OutputSegment(entry.text, "system")
                // 进程退出后刷新会话列表，确保 tmuxAlive 绿点状态准确
                viewModelScope.launch {
                    try {
                        val convs = claudeApiClient.listConversations(deviceId)
                        _conversations.value = convs
                        RemoteLogger.i(TAG, "[STATE] refreshed conversations after disconnected size=${convs.size}")
                    } catch (e: Exception) {
                        RemoteLogger.w(TAG, "[STATE] failed to refresh conversations after disconnected: ${e.message}")
                    }
                }
            }
            "turn_end" -> {
                RemoteLogger.i(TAG, "[ENTRY] turn_end seqId=${entry.seqId} wasProcessing=${_isProcessing.value} pendingSendCount=$pendingSendCount outputTokens=${entry.outputTokens} contextWindow=${entry.contextWindow}")
                val wasProcessing = _isProcessing.value
                // 只有没有飞行中的 sendMessage 请求时才清 isProcessing，
                // 防止竞态：sendInput 设 isProcessing=true 后，turn_end 把它盖回 false
                if (pendingSendCount == 0) {
                    _isProcessing.value = false
                    _processingStartMs.value = null
                } else {
                    RemoteLogger.i(TAG, "[ENTRY] turn_end skipped clearing isProcessing: pendingSendCount=$pendingSendCount")
                }
                if (entry.outputTokens > 0) {
                    _totalOutputTokens.value += entry.outputTokens
                }
                val elapsed = sendStartMs?.let { (System.currentTimeMillis() - it) / 1000.0 }
                sendStartMs = null
                if (elapsed != null) {
                    _segments.value += OutputSegment("⏱ 响应用时 ${String.format("%.1f", elapsed)}s", "system")
                }
                // 插入不可见分隔符，防止下一轮 text output 追加到本轮末尾的 text segment
                _segments.value += OutputSegment("", "turn_sep")
                // 从最后一个 text segment 提取 <thinking> XML，拆成独立 thinking segment
                val segs = _segments.value.toMutableList()
                val lastTextIdx = segs.indexOfLast { it.subtype == "text" }
                if (lastTextIdx >= 0) {
                    var text = segs[lastTextIdx].text
                    val thinkingBlocks = mutableListOf<String>()
                    text = THINKING_BLOCK_REGEX.replace(text) { mr ->
                        thinkingBlocks.add(mr.groupValues[1].trim())
                        ""
                    }.trimStart('\n').trimEnd()
                    segs[lastTextIdx] = segs[lastTextIdx].copy(text = text)
                    if (thinkingBlocks.isNotEmpty()) {
                        val insertAt = lastTextIdx
                        thinkingBlocks.reversed().forEach { block ->
                            segs.add(insertAt, OutputSegment(block, "thinking"))
                        }
                        RemoteLogger.i(TAG, "[STATE] extracted ${thinkingBlocks.size} thinking blocks from text")
                    }
                    _segments.value = segs
                }
                // App 在后台且本轮是用户主动发的消息时发通知
                if (!isAppInForeground && (wasProcessing || wasProcessingOnInit)) {
                    wasProcessingOnInit = false
                    showTurnEndNotification()
                }
            }
            "warmup_complete" -> {
                val hasPendingMessage = sendStartMs != null
                RemoteLogger.i(TAG, "[ENTRY] warmup_complete seqId=${entry.seqId} hasPendingMessage=$hasPendingMessage → isWarmingUp=false isProcessing=${if (hasPendingMessage) "keep true" else "false"}")
                _isWarmingUp.value = false
                if (!hasPendingMessage) {
                    _isProcessing.value = false
                    _processingStartMs.value = null
                }
            }
            "session_conflict" -> {
                RemoteLogger.i(TAG, "[ENTRY] session_conflict seqId=${entry.seqId} text=\"${entry.text.take(80)}\"")
                _sessionConflict.value = true
                _isWarmingUp.value = false
                _isProcessing.value = false
                _processingStartMs.value = null
                _segments.value += OutputSegment(entry.text, "system")
            }
            "resume_failed" -> {
                RemoteLogger.i(TAG, "[ENTRY] resume_failed seqId=${entry.seqId} text=\"${entry.text.take(80)}\"")
                _resumeFailed.value = true
                _resumeFailedReason.value = entry.text
                _isWarmingUp.value = false
                _isProcessing.value = false
                _processingStartMs.value = null
                _segments.value += OutputSegment(entry.text, "system")
            }
            "connected" -> {
                RemoteLogger.i(TAG, "[ENTRY] connected seqId=${entry.seqId} text=\"${entry.text.take(60).replace("\n","↵")}\"")
                // init 流程已处理 connected 语义（applyInitResponse），buffer 里的 connected 做追加展示
                if (entry.text.isNotBlank()) {
                    _segments.value += OutputSegment(entry.text, "system")
                }
            }
        }
    }

    // ── 发消息 ────────────────────────────────────────────────────────

    // 已选图片
    data class PendingImage(val uri: Uri, val fileId: String?)

    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages: StateFlow<List<PendingImage>> = _pendingImages

    private val _imageUploading = MutableStateFlow(false)
    val imageUploading: StateFlow<Boolean> = _imageUploading

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            // 去重：过滤掉已在 pendingImages 里的 URI，防止 rememberLauncherForActivityResult 回调二次触发重复追加
            val existingUris = _pendingImages.value.map { it.uri }.toSet()
            val newUris = uris.filter { it !in existingUris }
            if (newUris.isEmpty()) {
                RemoteLogger.d(TAG, "addImages: all uris already exist, skip (possible duplicate callback)")
                return@launch
            }
            RemoteLogger.d(TAG, "addImages: newUris=${newUris.size} (filtered ${uris.size - newUris.size} duplicates)")
            _imageUploading.value = true
            val results = mutableListOf<PendingImage>()
            for (uri in newUris) {
                try {
                    val file = uriToTempFile(uri) ?: continue
                    val mimeType = appContext.contentResolver.getType(uri) ?: "image/jpeg"
                    val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
                    val part = MultipartBody.Part.createFormData("file", file.name, requestFile)
                    val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaTypeOrNull())
                    val resp = apiService.uploadFile(part, deviceIdBody)
                    results.add(PendingImage(uri, resp.fileId))
                    RemoteLogger.d(TAG, "Image uploaded: uri=$uri fileId=${resp.fileId}")
                } catch (e: Exception) {
                    RemoteLogger.e(TAG, "Failed to upload image: $uri", e)
                    results.add(PendingImage(uri, null))
                }
            }
            _pendingImages.value = _pendingImages.value + results
            _imageUploading.value = false
        }
    }

    fun removeImage(uri: Uri) {
        _pendingImages.value = _pendingImages.value.filter { it.uri != uri }
    }

    fun sendInput(text: String) {
        val convId = _currentConvId.value
        RemoteLogger.i(TAG, "[STATE] sendInput isProcessing=${_isProcessing.value} convId=${convId?.take(8)} text=\"${text.take(40)}\"")
        if (convId == null) {
            RemoteLogger.w(TAG, "sendInput: no currentConvId, ignored")
            return
        }

        val images = _pendingImages.value
        val fileIds = images.mapNotNull { it.fileId }
        _pendingImages.value = emptyList()
        _isProcessing.value = true
        pendingSendCount++
        val now = System.currentTimeMillis()
        sendStartMs = now
        _processingStartMs.value = now

        // 记录输入历史
        if (text.isNotBlank() && inputHistory.lastOrNull() != text) {
            inputHistory.add(text)
            if (inputHistory.size > 50) inputHistory.removeAt(0)
            _inputHistorySize.value = inputHistory.size
        }

        RemoteLogger.i(TAG, "[STATE] sendInput → isProcessing=true pendingSendCount=$pendingSendCount processingStartMs=$now convId=${convId.take(8)}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = claudeApiClient.sendMessage(deviceId, convId, text, fileIds)
                if (result.error != null) {
                    RemoteLogger.w(TAG, "[STATE] sendInput server rejected convId=${convId.take(8)} error=${result.error}")
                    withContext(Dispatchers.Main) {
                        pendingSendCount = (pendingSendCount - 1).coerceAtLeast(0)
                        _isProcessing.value = false
                        _processingStartMs.value = null
                        sendStartMs = null
                        _segments.value += OutputSegment("[消息发送失败：${result.error}]\n", "system")
                    }
                } else {
                    // 立即本地显示 user_input，并把 cursor 推进到该 seqId，轮询自然从下一条开始
                    RemoteLogger.i(TAG, "[STATE] sendInput OK convId=${convId.take(8)} userInputSeqId=${result.userInputSeqId} pendingSendCount=$pendingSendCount")
                    if (result.userInputSeqId > 0) currentCursor = result.userInputSeqId
                    withContext(Dispatchers.Main) {
                        pendingSendCount = (pendingSendCount - 1).coerceAtLeast(0)
                        _segments.value += OutputSegment(result.displayText, "user_input")
                    }
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "sendInput HTTP failed convId=${convId.take(8)}", e)
                withContext(Dispatchers.Main) {
                    pendingSendCount = (pendingSendCount - 1).coerceAtLeast(0)
                    _isProcessing.value = false
                    _processingStartMs.value = null
                    sendStartMs = null
                    _segments.value += OutputSegment("[消息发送失败: ${e.message}]\n", "system")
                }
            }
        }
    }

    fun getInputHistory(index: Int): String? {
        if (inputHistory.isEmpty() || index < 0 || index >= inputHistory.size) return null
        return inputHistory[inputHistory.size - 1 - index]
    }

    val inputHistorySize: Int get() = inputHistory.size

    // ── 会话操作 ──────────────────────────────────────────────────────

    fun setAccountMode(mode: String) {
        viewModelScope.launch { settingsRepository.saveAccountMode(mode) }
    }

    fun newConversation(workDir: String? = null) {
        RemoteLogger.i(TAG, "[STATE] newConversation currentConvId=${_currentConvId.value?.take(8)} workDir=$workDir")
        _sessionConflict.value = false
        _resumeFailed.value = false
        _resumeFailedReason.value = null
        _isSwitching.value = true
        _switchingToConvId.value = null
        pollJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = claudeApiClient.newConversation(deviceId, accountMode.value, workDir)
                withContext(Dispatchers.Main) {
                    currentCursor = resp.cursor
                    applyInitResponse(resp)
                }
                startPolling(resp.convId!!)
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "newConversation failed", e)
                val errDetail = if (e is retrofit2.HttpException) {
                    val body = e.response()?.errorBody()?.string()
                    if (!body.isNullOrBlank()) "HTTP ${e.code()}\n$body" else e.message
                } else e.message
                withContext(Dispatchers.Main) {
                    _isSwitching.value = false
                    _segments.value += OutputSegment("[新建会话失败: $errDetail]\n", "system")
                }
            }
        }
    }

    fun switchConversation(convId: String) {
        RemoteLogger.i(TAG, "[STATE] switchConversation from=${_currentConvId.value?.take(8)} to=${convId.take(8)}")
        _sessionConflict.value = false
        _resumeFailed.value = false
        _resumeFailedReason.value = null
        _isSwitching.value = true
        _switchingToConvId.value = convId
        pollJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = claudeApiClient.switchConversation(deviceId, convId, accountMode.value)
                withContext(Dispatchers.Main) {
                    currentCursor = resp.cursor
                    applyInitResponse(resp)
                }
                startPolling(resp.convId!!)
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "switchConversation failed", e)
                withContext(Dispatchers.Main) {
                    _isSwitching.value = false
                    _segments.value += OutputSegment("[切换会话失败: ${e.message}]\n", "system")
                }
            }
        }
    }

    fun deleteConversation(convId: String) {
        val isDeletingCurrent = convId == _currentConvId.value
        RemoteLogger.i(TAG, "[STATE] deleteConversation convId=${convId.take(8)} isDeletingCurrent=$isDeletingCurrent")
        if (isDeletingCurrent) {
            _segments.value = emptyList()
            _currentConvId.value = null
            pollJob?.cancel()
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val convs = claudeApiClient.deleteConversation(deviceId, convId)
                withContext(Dispatchers.Main) {
                    _conversations.value = convs
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "deleteConversation failed convId=${convId.take(8)}", e)
                withContext(Dispatchers.Main) {
                    _segments.value += OutputSegment("[删除会话失败: ${e.message}]\n", "system")
                }
            }
        }
    }

    fun sendInterrupt() {
        val convId = _currentConvId.value ?: return
        RemoteLogger.i(TAG, "[STATE] sendInterrupt convId=${convId.take(8)}")
        _segments.value += OutputSegment("[⏹ 已中断]\n", "system")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                claudeApiClient.interrupt(convId)
                RemoteLogger.i(TAG, "[STATE] sendInterrupt OK convId=${convId.take(8)}")
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "sendInterrupt failed convId=${convId.take(8)}", e)
            }
        }
    }

    fun killAllTmuxSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = claudeApiClient.killAllSessions(deviceId)
                withContext(Dispatchers.Main) {
                    // 取消轮询，避免 pollJob 对已死进程持续报错重试
                    pollJob?.cancel()
                    pollJob = null
                    // 清空所有运行态，等用户自己选择会话
                    _currentConvId.value = null
                    _isProcessing.value = false
                    _processingStartMs.value = null
                    _isConnected.value = false
                    _conversations.value = resp.conversations
                    _activeTmuxSessions.value = resp.activeTmuxSessions
                    _segments.value = emptyList()
                    RemoteLogger.i(TAG, "[STATE] killAll OK, pollJob cancelled, waiting for user to pick a session")
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "killAllSessions failed", e)
            }
        }
    }

    fun killCurrentSession() {
        val convId = _currentConvId.value ?: return
        RemoteLogger.i(TAG, "[STATE] killCurrentSession convId=${convId.take(8)}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                claudeApiClient.killSession(deviceId, convId)
                withContext(Dispatchers.Main) {
                    // kill 后旧状态失效，清空 segments 避免错误提示残留
                    _segments.value = emptyList()
                    RemoteLogger.i(TAG, "[STATE] killCurrentSession OK, segments cleared convId=${convId.take(8)}, re-init")
                    init(serverIp.value, convId)
                }
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "killSession failed", e)
            }
        }
    }

    /**
     * 切换账号模式并强制重启当前会话（kill → re-init with new mode）。
     * 用于"Pro 额度用完 → 切换 Max"场景，直接传 newMode 避免 DataStore 异步竞争。
     */
    fun switchModeAndRestart(newMode: String) {
        val convId = _currentConvId.value ?: return
        RemoteLogger.i(TAG, "[STATE] switchModeAndRestart convId=${convId.take(8)} newMode=$newMode")
        _resumeFailed.value = false
        _resumeFailedReason.value = null
        _segments.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.saveAccountMode(newMode)
            try {
                claudeApiClient.killSession(deviceId, convId)
                RemoteLogger.i(TAG, "[STATE] switchModeAndRestart killSession OK convId=${convId.take(8)}")
            } catch (e: Exception) {
                RemoteLogger.w(TAG, "[STATE] switchModeAndRestart killSession failed (may be OK): ${e.message}")
            }
            try {
                val resp = claudeApiClient.init(deviceId, convId, newMode)
                withContext(Dispatchers.Main) {
                    currentCursor = resp.cursor
                    applyInitResponse(resp)
                }
                startPolling(resp.convId!!)
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "[STATE] switchModeAndRestart re-init failed", e)
                withContext(Dispatchers.Main) {
                    _segments.value += OutputSegment("[切换失败: ${e.message}]\n", "system")
                }
            }
        }
    }


    // ── 前后台切换 ────────────────────────────────────────────────────

    fun onAppForeground() {
        isAppInForeground = true
        RemoteLogger.i(TAG, "[STATE] onAppForeground pollActive=${pollJob?.isActive} convId=${_currentConvId.value?.take(8)}")
        if (pollJob?.isActive != true) {
            // 重新 init 拿最新状态
            val ip = serverIp.value
            init(ip)
        }
    }

    fun onAppBackground() {
        isAppInForeground = false
        RemoteLogger.i(TAG, "[STATE] onAppBackground convId=${_currentConvId.value?.take(8)}")
        pollJob?.cancel()
    }

    // ── 其他 ──────────────────────────────────────────────────────────

    fun openDrawer() { _drawerOpen.value = true }
    fun closeDrawer() { _drawerOpen.value = false }

    fun clearOutput() {
        _segments.value = emptyList()
    }

    private fun fetchContext(serverIp: String) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    URL("http://$serverIp:8082/claude/context").readText()
                }
                val obj = JSONObject(json)
                _context.value = ClaudeContext(
                    workDir = obj.getString("workDir"),
                    branch = obj.getString("branch"),
                    changedFiles = obj.getString("changedFiles").toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
                RemoteLogger.d(TAG, "fetchContext failed (non-critical): ${e.message}")
            }
        }
    }

    private fun uriToTempFile(uri: Uri): File? {
        val contentResolver: ContentResolver = appContext.contentResolver
        val fileName = run {
            var name: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
            name ?: "img_${System.currentTimeMillis()}.jpg"
        }
        val tempFile = File(appContext.cacheDir, fileName)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            tempFile
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "uriToTempFile failed: uri=$uri", e)
            null
        }
    }

    private fun showTurnEndNotification() {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: return
        val pi = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val convName = _currentConvId.value
            ?.let { id -> _conversations.value.find { it.convId == id }?.name }
        val title = if (convName != null) "Claude 回复完成：$convName" else "Claude 回复完成"
        val notif = Notification.Builder(appContext, com.xinjian.capsulecode.service.PushForegroundService.CHANNEL_ALERT)
            .setSmallIcon(com.xinjian.capsulecode.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("点击查看回复")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID_CLAUDE_TURN_END, notif)
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        asrManagerInstance?.release()
    }
}
