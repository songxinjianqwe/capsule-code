package com.xinjian.capsulecode.ui.claude

import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.xinjian.capsulecode.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.zIndex
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.navigation.NavController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.xinjian.capsulecode.ui.common.VoiceRecordingOverlay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ClaudeScreen(
    viewModel: ClaudeViewModel = hiltViewModel(),
    navController: NavController? = null,
    onOpenSettings: () -> Unit = {}
) {
    val isConnected by viewModel.isConnected.collectAsState()
    // 延迟显示"未连接"横幅，避免 poll 短暂抖动/切 Tab 重连期间的闪烁（800ms 内重连不显示）
    var showDisconnectedBanner by remember { mutableStateOf(false) }
    // 新建会话对话框（选择 workDir）
    var showNewConvDialog by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            showDisconnectedBanner = false
        } else {
            delay(800)
            showDisconnectedBanner = true
        }
    }
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isWarmingUp by viewModel.isWarmingUp.collectAsState()
    val processingStartMs by viewModel.processingStartMs.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val switchingToConvId by viewModel.switchingToConvId.collectAsState()
    val convProcessingStartMs by viewModel.convProcessingStartMs.collectAsState()
    val sessionConflict by viewModel.sessionConflict.collectAsState()
    val resumeFailed by viewModel.resumeFailed.collectAsState()
    val resumeFailedReason by viewModel.resumeFailedReason.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val totalInputTokens by viewModel.totalInputTokens.collectAsState()
    val totalOutputTokens by viewModel.totalOutputTokens.collectAsState()
    val blocked = sessionConflict || resumeFailed
    val inputEnabled = isConnected && !isProcessing && !isWarmingUp && !isSwitching && !blocked
    val inputWritable = !isSwitching && !blocked
    val serverIp by viewModel.serverIp.collectAsState()
    val accountMode by viewModel.accountMode.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val activeTmuxSessions by viewModel.activeTmuxSessions.collectAsState()
    val currentConvId by viewModel.currentConvId.collectAsState()
    val currentConvAccountMode = conversations.find { it.convId == currentConvId }?.accountMode ?: "max"
    val inputHistorySize by viewModel.inputHistorySizeFlow.collectAsState()
    val pendingImages by viewModel.pendingImages.collectAsState()
    val imageUploading by viewModel.imageUploading.collectAsState()
    val isVoiceRecording by viewModel.isVoiceRecording.collectAsState()
    val voiceTapMode by viewModel.voiceTapMode.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()
    val voiceFinalText by viewModel.voiceFinalText.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()
    val context = LocalContext.current
    // 用 remember 而非 rememberLazyListState（rememberSaveable）：
    // rememberSaveable 切 Tab 回来会恢复旧的 firstVisibleItemIndex，导致先在旧位置渲染再跳底部（弹跳感）。
    // remember 每次 Composable 重建都从 segments 当前末尾开始，直接在底部渲染，不需要 scroll 修正。
    val listState = remember {
        LazyListState(firstVisibleItemIndex = viewModel.segments.value.size)
    }
    // 首次加载遮罩：冷启动进入 Claude Tab 时 segments 空 + 未连接，init 加载历史填充 segments 会产生"从顶跳到底"的闪动。
    // 用 loading 遮住这一过程，等 isConnected 变 true 且 scrollToBottom 完成后再显示列表。
    // ViewModel 已保留数据或已连接时直接为 true（切 Tab 回来不显示 loading）。
    var initialLoadComplete by remember {
        mutableStateOf(viewModel.segments.value.isNotEmpty() || viewModel.isConnected.value)
    }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var historyIndex by remember { mutableStateOf(-1) }  // -1 = 当前新输入
    var savedInput by remember { mutableStateOf("") }    // 翻历史前保存的输入内容
    var isInputFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val inputFocusRequester = remember { FocusRequester() }
    // 录音中边框脉冲动画
    val recordingPulse = rememberInfiniteTransition(label = "recordingPulse")
    val recordingBorderAlpha by recordingPulse.animateFloat(
        initialValue = 0.5f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "recordingBorderAlpha"
    )
    // 录音时整体缩放，模拟"被按下"的反馈
    val recordingScale by animateFloatAsState(
        targetValue = if (isVoiceRecording) 0.97f else 1f,
        animationSpec = tween(150),
        label = "recordingScale"
    )
    // 录音浮层状态：按下时显示底部录音浮层，手指上移超过阈值标记为"松开取消"
    var voiceWillCancel by remember { mutableStateOf(false) }
    // 按下时的 inputText 快照，取消时恢复
    var savedInputBeforeVoice by remember { mutableStateOf(TextFieldValue("")) }
    // 长按录音松开后自动发送标记
    var autoSendAfterVoice by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isProcessing, processingStartMs) {
        val startMs = processingStartMs
        if (isProcessing && startMs != null) {
            while (isActive) {
                elapsedSeconds = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    // tap 模式：partial 实时写入输入框（拼接在录音前已有文本之后）
    LaunchedEffect(voicePartialText, voiceTapMode) {
        if (voiceTapMode && voicePartialText != null) {
            val base = savedInputBeforeVoice.text
            val combined = if (base.isNotEmpty()) "$base $voicePartialText" else voicePartialText.orEmpty()
            inputText = TextFieldValue(combined, TextRange(combined.length))
        }
    }
    // final 到达时写入 inputText；长按模式下 autoSendAfterVoice=true 时自动发送
    LaunchedEffect(voiceFinalText) {
        voiceFinalText?.let { t ->
            if (autoSendAfterVoice && t.isNotBlank()) {
                viewModel.sendInput(t.trim())
                inputText = TextFieldValue("")
                autoSendAfterVoice = false
            } else {
                val base = savedInputBeforeVoice.text
                val combined = if (base.isNotEmpty()) "$base $t" else t
                inputText = TextFieldValue(combined, TextRange(combined.length))
            }
            viewModel.clearVoiceFinalText()
        }
    }
    LaunchedEffect(voiceError) {
        voiceError?.let { msg ->
            Toast.makeText(context, "语音识别失败：$msg", Toast.LENGTH_LONG).show()
            viewModel.clearVoiceError()
        }
    }

    // 初始化（HTTP 轮询模式）
    // key 同时包含 accountMode：DataStore 异步加载完真实值后重新触发，确保 init 携带正确的账号模式
    LaunchedEffect(serverIp, accountMode) {
        viewModel.init(serverIp)
    }

    // isSwitching 变 false（收到 connected）时自动关闭抽屉
    LaunchedEffect(isSwitching) {
        if (!isSwitching && drawerState.isOpen) {
            drawerState.close()
        }
    }

    // 会话切换时重置历史翻页位置 + 清空输入框
    LaunchedEffect(currentConvId) {
        historyIndex = -1
        savedInput = ""
        inputText = TextFieldValue("")
    }

    // 始终滚到底部：用 StateFlow 当前值而非闭包捕获值，避免 segments.size 过期
    suspend fun scrollToBottom() {
        val size = viewModel.segments.value.size
        if (size > 0) listState.scrollToItem(size)
    }
    LaunchedEffect(segments.size) {
        scrollToBottom()
    }
    // 首次连接成功后解除遮罩（先 scrollToBottom 到底部再显示，避免闪动）
    LaunchedEffect(isConnected) {
        if (isConnected && !initialLoadComplete) {
            scrollToBottom()
            initialLoadComplete = true
        }
    }
    // 流式输出时用 canScrollForward 响应式滚底（不轮询）：
    // 只要 anchor 被推到视口外（canScrollForward=true），立刻滚到底，反复直到内容停止增长。
    // 比 50ms 轮询更即时、不受定时器延迟影响；用户上翻后手动滑回底部同样会立刻恢复跟随。
    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            snapshotFlow { listState.canScrollForward }
                .collect { canScrollForward ->
                    if (canScrollForward) scrollToBottom()
                }
        }
    }
    // 补丁：单个 segment 内容持续增长（如 thinking 块）时，canScrollForward snapshotFlow 可能因值不变而停止 emit。
    // 额外监听最后一个 segment 文本长度变化，只要有增长且未在底部就滚底。
    val lastSegTextLen by remember { derivedStateOf { segments.lastOrNull()?.text?.length ?: 0 } }
    LaunchedEffect(lastSegTextLen) {
        if (isProcessing && listState.canScrollForward) scrollToBottom()
    }

    val clipboardManager = LocalClipboardManager.current
    var deleteConfirmConvId by remember { mutableStateOf<String?>(null) }

    // 删除确认弹框
    deleteConfirmConvId?.let { convId ->
        val conv = conversations.find { it.convId == convId }
        AlertDialog(
            onDismissRequest = { deleteConfirmConvId = null },
            title = { Text("删除会话") },
            text = { Text("确认删除「${conv?.name ?: convId.take(8)}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(convId)
                    deleteConfirmConvId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmConvId = null }) { Text("取消") }
            }
        )
    }

    // 新建会话对话框：选工作目录
    if (showNewConvDialog) {
        NewConversationDialog(
            presets = listOf(
                "/workspace",
                "/workspace/example-project",
            ),
            defaultSelection = conversations.firstOrNull { it.convId == currentConvId }?.workDir
                ?: "/workspace",
            onConfirm = { workDir ->
                showNewConvDialog = false
                coroutineScope.launch { drawerState.close() }
                viewModel.newConversation(workDir.takeIf { it.isNotBlank() })
            },
            onDismiss = { showNewConvDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 录音时禁用抽屉手势，避免按住输入框后手指横向移动触发抽屉拖动 → pointerInput 被取消 → 录音中断
        gesturesEnabled = !isVoiceRecording,
        drawerContent = {
            ModalDrawerSheet {
                // 顶部标题 + 新建按钮
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("会话列表", style = MaterialTheme.typography.titleMedium)
                        if (activeTmuxSessions > 0) {
                            Text(
                                " $activeTmuxSessions",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                            var showKillConfirm by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showKillConfirm = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "清理所有 tmux session",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            if (showKillConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showKillConfirm = false },
                                    title = { Text("清理所有进程") },
                                    text = { Text("将 kill 所有活跃 tmux session（共 $activeTmuxSessions 个），不删除会话记录。下次进入会话时会重建进程。") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            viewModel.killAllTmuxSessions()
                                            showKillConfirm = false
                                        }) { Text("确认清理", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showKillConfirm = false }) { Text("取消") }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            showNewConvDialog = true
                        },
                        modifier = Modifier.testTag("btn_new_conversation")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建会话")
                    }
                }
                HorizontalDivider()
                // 会话列表
                LazyColumn {
                    items(conversations, key = { it.convId }) { conv ->
                        val isActive = conv.convId == currentConvId
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (conv.tmuxAlive) {
                                            Text(
                                                "● ",
                                                color = Color(0xFF4CAF50),
                                                fontSize = 8.sp,
                                                modifier = Modifier.alignBy(androidx.compose.ui.layout.FirstBaseline)
                                            )
                                        }
                                        Text(conv.name, maxLines = 1,
                                            modifier = Modifier.testTag("conv_name_${conv.convId}").weight(1f, fill = false),
                                            style = MaterialTheme.typography.bodyMedium)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = when (conv.accountMode) {
                                                "enterprise" -> MaterialTheme.colorScheme.primaryContainer
                                                "pro"        -> MaterialTheme.colorScheme.tertiaryContainer
                                                else         -> MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        ) {
                                            Text(
                                                text = when (conv.accountMode) {
                                                    "enterprise" -> "企"
                                                    "pro"        -> "Pro"
                                                    else         -> "Max"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (conv.accountMode) {
                                                    "enterprise" -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    "pro"        -> MaterialTheme.colorScheme.onTertiaryContainer
                                                    else         -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (conv.processing) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                strokeWidth = 1.5.dp,
                                                color = Color(0xFFFFB300)
                                            )
                                            val startMs = convProcessingStartMs[conv.convId]
                                            if (startMs != null) {
                                                Spacer(modifier = Modifier.width(3.dp))
                                                var drawerElapsed by remember(conv.convId) { mutableStateOf(0) }
                                                LaunchedEffect(startMs) {
                                                    while (true) {
                                                        drawerElapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                                                        kotlinx.coroutines.delay(500)
                                                    }
                                                }
                                                Text(
                                                    text = "${drawerElapsed}s",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFFFFB300)
                                                )
                                            }
                                        }
                                    }
                                    // 工作目录（完整路径，稍淡色）
                                    conv.workDir?.let { wd ->
                                        Text(
                                            text = "📁 $wd" + if (conv.workDirIsDefault) " (默认)" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            maxLines = 1,
                                            modifier = Modifier.padding(top = 1.dp)
                                        )
                                    }
                                    // Claude Session ID（完整）+ 复制按钮紧贴
                                    conv.claudeSessionId?.let { sessionId ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 1.dp)
                                        ) {
                                            Text(
                                                text = sessionId,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                maxLines = 1
                                            )
                                            IconButton(
                                                onClick = { clipboardManager.setText(AnnotatedString(sessionId)) },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制 Claude Session ID",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                            }
                                        }
                                    }
                                }
                            },
                            selected = isActive,
                            onClick = {
                                if (!isActive) viewModel.switchConversation(conv.convId)
                                else coroutineScope.launch { drawerState.close() }
                            },
                            badge = {
                                val isTarget = isSwitching && (
                                    conv.convId == switchingToConvId ||
                                    (switchingToConvId == null && isActive)  // 新建时在当前会话旁显示
                                )
                                if (isTarget) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = { deleteConfirmConvId = conv.convId },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .testTag("conv_item_${conv.convId}")
                        )
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // 紧凑顶栏：汉堡 + "Claude" + 会话名/git 信息 + 设置
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 汉堡菜单
                    IconButton(
                        onClick = { coroutineScope.launch { drawerState.open() } },
                        modifier = Modifier.size(36.dp).testTag("btn_menu")
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = "会话列表",
                            modifier = Modifier.size(20.dp))
                    }
                    // 会话名 + 工作目录副标题（居中区域，点击滚到底部）
                    val currentConv = conversations.find { it.convId == currentConvId }
                    val convName = currentConv?.name ?: ""
                    val currentWorkDir = currentConv?.workDir
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (segments.isNotEmpty()) {
                                    coroutineScope.launch { scrollToBottom() }
                                }
                            }
                    ) {
                        Text(
                            text = convName.ifEmpty { "Claude" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (convName.isNotEmpty()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        if (!currentWorkDir.isNullOrBlank()) {
                            // 只显示最后两段路径 + "(默认)" 标记
                            val short = currentWorkDir.trimEnd('/').split('/')
                                .takeLast(2).joinToString("/")
                                .ifEmpty { currentWorkDir }
                            val suffix = if (currentConv?.workDirIsDefault == true) " · 默认" else ""
                            Text(
                                text = "📁 …/$short$suffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                    // 企/个标签（进入会话后显示，反映进程实际启动时的模式）
                    if (currentConvId != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (currentConvAccountMode) {
                                "enterprise" -> MaterialTheme.colorScheme.primaryContainer
                                "pro"        -> MaterialTheme.colorScheme.tertiaryContainer
                                else         -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = when (currentConvAccountMode) {
                                    "enterprise" -> "企"
                                    "pro"        -> "Pro"
                                    else         -> "Max"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (currentConvAccountMode) {
                                    "enterprise" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "pro"        -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else         -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    // input/output token（不再显示 ctx%，Bedrock token 字段无法准确算出使用率）
                    if (totalInputTokens > 0 || totalOutputTokens > 0) {
                        fun Long.toK() = if (this >= 1000) "${this / 1000}k" else "$this"
                        val parts = buildList {
                            if (totalInputTokens > 0) add(Pair("↑${totalInputTokens.toK()}", MaterialTheme.colorScheme.onSurfaceVariant))
                            if (totalOutputTokens > 0) add(Pair("↓${totalOutputTokens.toK()}", MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            parts.forEach { (text, color) ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    // 设置图标
                    IconButton(
                        onClick = { onOpenSettings() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置",
                            modifier = Modifier.size(20.dp))
                    }
                }
            }

            // 连接状态栏（用 showDisconnectedBanner 而非 !isConnected，避免短暂抖动闪烁）
            if (showDisconnectedBanner) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        "未连接到服务器",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 切换/新建会话时显示整屏 loading
            if (isSwitching) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF569CD6))
                }
            } else if (currentConvId == null) {
                // 空状态欢迎页：没有任何会话
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 大图标
                    Image(
                        painter = painterResource(id = R.drawable.welcome_logo),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Capsule Code",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFFF7F8F8)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "还没有会话，开始第一个吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A8F98)
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { showNewConvDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始新会话")
                    }
                }
            } else

            // 输出区域（黑底等宽字体，按 subtype 分色）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("output_list")
                    .background(Color(0xFF1E1E1E))
                    .then(if (initialLoadComplete) Modifier else Modifier.alpha(0f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (segments.isEmpty()) {
                    item {
                        Text(
                            text = "等待 claude 输出...",
                            color = Color(0xFF6A6A6A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    itemsIndexed(segments, key = { _, seg -> seg.id }) { index, seg ->
                        // turn_sep 是纯占位符，不渲染任何内容
                        if (seg.subtype == "turn_sep") return@itemsIndexed
                        val prevSeg = if (index > 0) segments[index - 1] else null
                        val topPadding = if (seg.subtype == "tool" && prevSeg?.subtype == "tool") 4.dp else 0.dp
                        val (color, bgColor) = when (seg.subtype) {
                            "user_input" -> Color(0xFF9CDCFE) to Color(0xFF1A3A4A)   // 蓝色，深蓝背景（用户消息）
                            "thinking"   -> Color(0xFF808080) to Color(0xFF1E1E1E)   // 灰色
                            "tool"       -> Color(0xFFCE9178) to Color(0xFF2D2010)   // 橙色，深棕背景
                            "system"     -> Color(0xFF569CD6) to Color(0xFF1E1E1E)   // 浅蓝
                            else         -> Color(0xFFD4D4D4) to Color(0xFF2A2A2A)   // Claude 输出：浅灰背景
                        }
                        val prefix = when (seg.subtype) {
                            "user_input" -> "❯ "
                            "system"     -> ""
                            "thinking"   -> ""
                            else         -> "· "
                        }
                        SelectionContainer {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = topPadding)
                                .background(bgColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            if (seg.subtype == "text") {
                                MarkdownText(
                                    markdown = seg.text,
                                    color = color,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            } else if (seg.subtype == "thinking") {
                                val thinkingContent = seg.text.trim()
                                    .removePrefix("[思考]").trim()
                                if (thinkingContent.isEmpty()) {
                                    Text(
                                        text = "💭 思考中...",
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                } else {
                                    Column {
                                        Text(
                                            text = "💭 思考",
                                            color = color,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                        Text(
                                            text = thinkingContent,
                                            color = color,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            } else if (seg.subtype == "tool") {
                                // 格式: "\n[工具: ToolName] {"command":"...","description":"..."}"
                                // 或:   "[工具结果]\n"
                                val trimmed = seg.text.trim()
                                // 流式执行中：最后一个 segment 且 isProcessing=true，只显示工具名 + 执行中
                                val isLastSegment = index == segments.size - 1
                                val toolNameOnly = Regex("""^\[工具:\s*(.+?)\]""").find(trimmed)?.groupValues?.get(1)
                                if (isLastSegment && isProcessing && toolNameOnly != null) {
                                    val restJson = trimmed.substringAfter("]").trim()
                                    val displayName = when (toolNameOnly) {
                                        "Skill" -> {
                                            val skill = Regex(""""skill"\s*:\s*"([^"]*)"""").find(restJson)?.groupValues?.get(1)
                                            if (skill != null) "Skill: $skill" else "Skill"
                                        }
                                        "Agent" -> {
                                            val desc = Regex(""""description"\s*:\s*"([^"]*)"""").find(restJson)?.groupValues?.get(1)
                                            if (desc != null) "Agent: $desc" else "Agent"
                                        }
                                        "Bash" -> {
                                            val desc = Regex(""""description"\s*:\s*"([^"]*)"""").find(restJson)?.groupValues?.get(1)
                                            if (desc != null) "Bash: $desc" else "Bash"
                                        }
                                        else -> toolNameOnly
                                    }
                                    Text(
                                        text = "🔧 $displayName 执行中...",
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp
                                    )
                                } else {
                                val toolCallMatch = Regex("""^\[工具:\s*(.+?)\]\s*(\{.*\})$""", RegexOption.DOT_MATCHES_ALL).find(trimmed)
                                if (toolCallMatch != null) {
                                    val toolName = toolCallMatch.groupValues[1]
                                    val json = toolCallMatch.groupValues[2]
                                    val displayText = if (toolName == "TodoWrite") {
                                        // 提取 todos 数组，每个元素显示 content + status
                                        val contentRegex = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                                        val statusRegex = Regex(""""status"\s*:\s*"([^"]*)"""")
                                        // 按 } 分割每个 todo 对象
                                        val todoItems = json.split("},").mapNotNull { item ->
                                            val content = contentRegex.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
                                            val status = statusRegex.find(item)?.groupValues?.get(1) ?: ""
                                            val icon = when (status) {
                                                "completed" -> "✓"
                                                "in_progress" -> "▶"
                                                else -> "○"
                                            }
                                            "$icon $content"
                                        }
                                        buildString {
                                            append("🔧 TodoWrite")
                                            todoItems.forEach { append("\n  $it") }
                                        }
                                    } else {
                                        // 其他工具：提取所有顶层字符串字段（key: value），每行一条
                                        val fieldRegex = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                                        val fields = fieldRegex.findAll(json).map { m ->
                                            val k = m.groupValues[1]
                                            val v = m.groupValues[2]
                                                .replace("\\n", "\n  ")
                                                .replace("\\\"", "\"")
                                                .replace("\\\\", "\\")
                                            "$k: $v"
                                        }.toList()
                                        buildString {
                                            append("🔧 $toolName")
                                            fields.forEach { append("\n  $it") }
                                        }
                                    }
                                    Text(
                                        text = displayText,
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp
                                    )
                                } else {
                                    // toolCallMatch 失败（header 与 JSON 分离时），尝试提取工具名作为前缀，header 后的内容原样保留
                                    val nameMatch = Regex("""^\[工具:\s*(.+?)\](.*)""", RegexOption.DOT_MATCHES_ALL).find(trimmed)
                                    val displayText = if (nameMatch != null) {
                                        val name = nameMatch.groupValues[1]
                                        val rest = nameMatch.groupValues[2].trim()
                                        if (rest.isNotEmpty()) "🔧 $name\n$rest" else "🔧 $name"
                                    } else trimmed
                                    Text(
                                        text = displayText,
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                                } // end else (not "执行中" branch)
                            } else {
                                Text(
                                    text = prefix + seg.text,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        } // end Box
                        } // end SelectionContainer
                    }
                    // scroll anchor：滚到这里等同于滚到列表绝对底部，避免 Int.MAX_VALUE/2 offset 在动态增高 item 上失效
                    item(key = "scroll_anchor") { Spacer(Modifier.height(0.dp)) }
                }
            }
            // 首次加载遮罩：loading 指示器遮住 LazyColumn 的首帧闪动
            if (!initialLoadComplete) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF7170FF),
                        strokeWidth = 2.dp
                    )
                }
            }
            } // end Box

            // resume_failed 时显示快捷入口
            if (resumeFailed) {
                val isLimitError = resumeFailedReason?.contains("hit your limit", ignoreCase = true) == true
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (isLimitError) {
                                if (currentConvAccountMode == "pro") "Pro 额度已用完，切换 Max 继续"
                                else "Max 额度已用完，请切换企业版"
                            } else "会话恢复失败，无法继续",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (isLimitError) {
                            Button(
                                onClick = {
                                    if (currentConvAccountMode == "pro") viewModel.switchModeAndRestart("max")
                                    else viewModel.switchModeAndRestart("enterprise")
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    if (currentConvAccountMode == "pro") "切换 Max" else "切换企业版",
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Button(
                                onClick = { showNewConvDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("btn_new_conversation_resume_failed")
                            ) {
                                Text("新建会话", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 账号模式与当前会话不一致时提示
            val modeMismatch = currentConvId != null && !isSwitching && !resumeFailed &&
                accountMode != currentConvAccountMode
            if (modeMismatch) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "当前会话是${when (currentConvAccountMode) { "enterprise" -> "企业版"; "pro" -> "Pro"; else -> "Max" }}，点击切换模式（保留上下文）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.killCurrentSession() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("切换模式", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 快捷按钮区 + 输入框（空状态下整体隐藏）
            if (currentConvId != null) {
            Surface(tonalElevation = 2.dp) {
                Column {
                    // y/n/A/B + 历史翻页（可横滚） | 操作菜单（固定右侧）
                    var showSkillMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：流式文字进行中时显示思考动画，文字结束但 result 未到 / 空闲时留空
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (isProcessing && !isWarmingUp) {
                                ThinkingIndicator(elapsedSeconds = elapsedSeconds)
                            }
                        }
                        // yes 快捷按钮（原三个快捷按钮仅保留 yes，移到右侧）
                        OutlinedButton(
                            onClick = { viewModel.sendInput("yes") },
                            enabled = inputEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp).widthIn(min = 40.dp)
                        ) {
                            Text("yes", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        // 右侧固定：历史翻页 + 操作菜单
                        IconButton(
                            onClick = {
                                val nextIndex = historyIndex + 1
                                val item = viewModel.getInputHistory(nextIndex)
                                if (item != null) {
                                    if (historyIndex == -1) savedInput = inputText.text
                                    historyIndex = nextIndex
                                    inputText = TextFieldValue(item, TextRange(item.length))
                                }
                            },
                            enabled = inputWritable && historyIndex + 1 < inputHistorySize,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一条历史",
                                modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                val nextIndex = historyIndex - 1
                                if (nextIndex < 0) {
                                    historyIndex = -1
                                    inputText = TextFieldValue(savedInput, TextRange(savedInput.length))
                                } else {
                                    val item = viewModel.getInputHistory(nextIndex)
                                    if (item != null) {
                                        historyIndex = nextIndex
                                        inputText = TextFieldValue(item, TextRange(item.length))
                                    }
                                }
                            },
                            enabled = inputWritable && historyIndex >= 0,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一条历史",
                                modifier = Modifier.size(18.dp))
                        }
                        Box {
                            OutlinedButton(
                                onClick = { showSkillMenu = true },
                                enabled = isConnected && !isSwitching && !blocked,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("操作 ▾", fontSize = 13.sp)
                            }
                            DropdownMenu(
                                expanded = showSkillMenu,
                                onDismissRequest = { showSkillMenu = false }
                            ) {
                                // 斜杠命令
                                listOf(
                                    "/context", "/compact", "/commit-push"
                                ).forEach { cmd ->
                                    DropdownMenuItem(
                                        text = { Text(cmd, fontSize = 13.sp) },
                                        enabled = inputEnabled,
                                        onClick = {
                                            viewModel.sendInput(cmd)
                                            showSkillMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                // 操作类
                                listOf(
                                    "重启后端" to "帮我重启 Java 后端服务",
                                    "安装 APK" to "帮我编译并安装 Android App",
                                    "重启后端并安装 APK" to "帮我先重启 Java 后端服务，再编译并安装 Android App",
                                    "排查问题" to "Android Claude 有问题，请帮我查看服务端和 Android 端的日志，结合代码和文档排查根因，修复后补充日志和文档",
                                    "清空" to null
                                ).forEach { (label, value) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 13.sp) },
                                        enabled = inputEnabled,
                                        onClick = {
                                            if (label == "排查问题" && value != null) {
                                                inputText = TextFieldValue(value, TextRange(value.length))
                                            } else if (value != null) {
                                                viewModel.sendInput(value)
                                            } else {
                                                viewModel.clearOutput()
                                            }
                                            showSkillMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("终止当前进程", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        viewModel.killCurrentSession()
                                        showSkillMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 输入框
            Surface(tonalElevation = 4.dp) {
                val imagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) viewModel.addImages(uris)
                }
                Column {
                    // 已选图片缩略图预览
                    if (pendingImages.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(pendingImages) { img ->
                                Box(modifier = Modifier.size(56.dp)) {
                                    AsyncImage(
                                        model = img.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { previewImageUri = img.uri }
                                    )
                                    // 未上传成功时显示遮罩
                                    if (img.fileId == null && !imageUploading) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Red.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("!", color = Color.White, fontSize = 18.sp)
                                        }
                                    }
                                    // 删除按钮
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                            .clickable { viewModel.removeImage(img.uri) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "移除图片",
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // + 按钮
                    IconButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = inputWritable,
                        modifier = Modifier.size(40.dp).testTag("btn_add_image")
                    ) {
                        if (imageUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = "添加图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 输入框（Box 覆盖层区分长按/短按，避免长按时弹键盘）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = recordingScale
                                scaleY = recordingScale
                            }
                    ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { isInputFocused = it.isFocused }
                            .testTag("input_field"),
                        placeholder = {
                            Text(if (isVoiceRecording) "正在录音…" else "输入或长按录音…")
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (isVoiceRecording)
                                MaterialTheme.colorScheme.error.copy(alpha = recordingBorderAlpha)
                            else MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = if (isVoiceRecording)
                                MaterialTheme.colorScheme.error.copy(alpha = recordingBorderAlpha)
                            else MaterialTheme.colorScheme.outline,
                            focusedContainerColor = if (isVoiceRecording)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f + 0.18f * recordingBorderAlpha)
                            else Color.Transparent,
                            unfocusedContainerColor = if (isVoiceRecording)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f + 0.18f * recordingBorderAlpha)
                            else Color.Transparent,
                            disabledContainerColor = if (isVoiceRecording)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f + 0.18f * recordingBorderAlpha)
                            else Color.Transparent,
                        ),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        enabled = inputWritable,
                        // 透明占位：给右侧外层 X 按钮预留空间，避免文字和 X 图标重叠
                        // X 按钮本身仍放在外层（覆盖层之上），保证可点击
                        trailingIcon = if (inputText.text.isNotEmpty()) {
                            { Spacer(Modifier.size(36.dp)) }
                        } else null,
                    )
                    // 透明覆盖层：区分长按（录音，不弹键盘）和短按（聚焦弹键盘）
                    // 有焦点时移除覆盖层，让 TextField 正常接收触摸事件（光标移动/选词）
                    // 注意：覆盖层必须在 X 按钮之前渲染，X 按钮在覆盖层上方（z-order 更高）才能响应点击
                    if (inputEnabled && !isInputFocused) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    val cancelThresholdPx = 120.dp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume() // 必须显式消费，否则 TextField 仍会收到 down 事件 → 获得焦点 → 弹键盘
                                        val downY = down.position.y
                                        val upEvt = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            waitForUpOrCancellation()
                                        }
                                        if (upEvt != null) {
                                            // 短按：手动聚焦 TextField 弹键盘
                                            inputFocusRequester.requestFocus()
                                            return@awaitEachGesture
                                        }
                                        // 长按触发：开始录音 + 显示底部录音浮层
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        savedInputBeforeVoice = inputText
                                        viewModel.startVoiceInput()
                                        voiceWillCancel = false
                                        var shouldCancel = false
                                        try {
                                            // 手动事件循环：主动 consume 所有 move，避免被 LazyColumn 等父容器抢占
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                change.consume()
                                                // 相对按下点的 Y 位移：上移超过阈值 → 取消
                                                val dy = change.position.y - downY
                                                shouldCancel = dy < -cancelThresholdPx
                                                voiceWillCancel = shouldCancel
                                                if (change.changedToUp() || !change.pressed) break
                                            }
                                        } finally {
                                            voiceWillCancel = false
                                            if (shouldCancel) {
                                                viewModel.cancelVoiceInput()
                                                inputText = savedInputBeforeVoice
                                                autoSendAfterVoice = false
                                            } else {
                                                autoSendAfterVoice = true
                                                viewModel.stopVoiceInput()
                                            }
                                        }
                                    }
                                }
                        )
                    }
                    // X 按钮：在覆盖层上方渲染，避免被覆盖层拦截点击
                    if (inputText.text.isNotEmpty()) {
                        IconButton(
                            onClick = { inputText = TextFieldValue(""); historyIndex = -1; savedInput = "" },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(36.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "清空输入", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    } // end Box（输入框覆盖层）
                    // 右侧按钮：warmup / 中断 / 发送 / 麦克风
                    // 48dp 与普通 IconButton 等宽，秒数已移到 ThinkingIndicator 显示
                    Box(
                        modifier = Modifier.size(width = 48.dp, height = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isWarmingUp -> Text(
                                text = "恢复中",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.testTag("warming_up_label")
                            )
                            isProcessing -> Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(36.dp).testTag("btn_interrupt").clickable { viewModel.sendInterrupt() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "■",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            !isVoiceRecording && inputText.text.trim().isNotEmpty() -> IconButton(
                                onClick = {
                                    val textToSend = inputText.text.trim().ifEmpty { "看图片" }
                                    viewModel.sendInput(textToSend)
                                    inputText = TextFieldValue(""); historyIndex = -1; savedInput = ""
                                    viewModel.clearVoiceFinalText()
                                    keyboardController?.hide(); focusManager.clearFocus()
                                },
                                enabled = inputEnabled && !imageUploading,
                                modifier = Modifier.testTag("btn_send")
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                            }
                            else -> IconButton(
                                onClick = {
                                    if (isVoiceRecording) viewModel.stopVoiceInput()
                                    else {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        savedInputBeforeVoice = inputText
                                        viewModel.startVoiceInput(tapMode = true)
                                    }
                                },
                                enabled = inputEnabled
                            ) {
                                Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "语音输入",
                                    modifier = Modifier.size(22.dp),
                                    tint = if (isVoiceRecording) MaterialTheme.colorScheme.error
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                } // end Column (Surface内)
            }
            } // end if (currentConvId != null)
        }
    }

    // 底部录音浮层（豆包风格）：仅长按输入框模式下显示，点击麦克风模式不弹浮层
    if (isVoiceRecording && !voiceTapMode) {
        VoiceRecordingOverlay(
            willCancel = voiceWillCancel,
            partialText = voicePartialText
        )
    }

    // 图片全屏预览 Dialog
    previewImageUri?.let { uri ->
        Dialog(
            onDismissRequest = { previewImageUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { previewImageUri = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationDialog(
    presets: List<String>,
    defaultSelection: String,
    onConfirm: (workDir: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 如果 defaultSelection 属于 presets 就选中；否则认为是"自定义"
    var selected by remember { mutableStateOf(if (presets.contains(defaultSelection)) defaultSelection else "__custom__") }
    var customText by remember { mutableStateOf(if (selected == "__custom__") defaultSelection else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Claude CLI 将在所选目录下启动。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                presets.forEach { preset ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = preset },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == preset, onClick = { selected = preset })
                        Spacer(Modifier.width(4.dp))
                        Text(preset, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { selected = "__custom__" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == "__custom__", onClick = { selected = "__custom__" })
                    Spacer(Modifier.width(4.dp))
                    Text("自定义")
                }
                if (selected == "__custom__") {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = { Text("/absolute/path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val workDir = if (selected == "__custom__") customText.trim() else selected
                onConfirm(workDir)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
