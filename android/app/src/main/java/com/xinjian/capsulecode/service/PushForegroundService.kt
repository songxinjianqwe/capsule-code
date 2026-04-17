package com.xinjian.capsulecode.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings.Secure
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.xinjian.capsulecode.MainActivity
import com.xinjian.capsulecode.util.RemoteLogger
import com.xinjian.capsulecode.R
import com.xinjian.capsulecode.data.network.PushEventBus
import com.xinjian.capsulecode.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class PushForegroundService : Service() {

    companion object {
        private const val TAG = "PushService"
        const val CHANNEL_PERSISTENT = "push_persistent"
        const val CHANNEL_ALERT = "push_alert"
        const val CHANNEL_UPDATE = "push_update"
        private const val NOTIF_ID_PERSISTENT = 1001
        private const val NOTIF_ID_UPDATE = 1002
        private const val RECONNECT_DELAY_MS = 5000L
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        // 事件驱动为主、轮询为辅（2 分钟兜底），降低电量消耗
        private const val MONITOR_INTERVAL_MS = 120_000L
        // VPN onLost 触发后，给 Clash→Tailscale 正常切换留 3 秒缓冲再诊断
        private const val VPN_LOST_DEBOUNCE_MS = 3_000L
        private const val MONITOR_TAG = "TailscaleMonitor"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PushForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushForegroundService::class.java))
        }

        fun queryCmccBalanceNow(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java).apply {
                action = ACTION_QUERY_CMCC_NOW
            }
            context.startForegroundService(intent)
        }

        const val ACTION_QUERY_CMCC_NOW = "ACTION_QUERY_CMCC_NOW"
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var pushEventBus: PushEventBus

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var shouldReconnect = true

    // 上一次触发 Tailscale 自动修复的时间戳（monitor loop 和 NetworkCallback 共用，5 分钟冷却）
    @Volatile
    private var lastAutoFixAttempt: Long = 0L
    private var vpnNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification())
        scope.launch { connectLoop() }
        scope.launch { cmccBalanceQueryLoop() }
        scope.launch { heartbeatLoop() }
        scope.launch { tailscaleMonitorLoop() }
        registerVpnNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUERY_CMCC_NOW) {
            scope.launch { sendCmccBalanceQuery() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shouldReconnect = false
        unregisterVpnNetworkCallback()
        webSocket?.close(1000, "Service stopped")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildDeviceId(): String {
        val androidId = Secure.getString(contentResolver, Secure.ANDROID_ID)
        return "MOBILE_$androidId"
    }

    /**
     * 每天早上 8:00 发一条短信 "103001" 给 10086 查询话费余额。
     * 10086 回复后由 SmsNotificationListener 捕获并推送后端。
     */
    private suspend fun cmccBalanceQueryLoop() {
        while (shouldReconnect) {
            val delayMs = millisUntilNextQuery(hour = 8, minute = 0)
            RemoteLogger.d(TAG, "CMCC balance query scheduled in ${delayMs / 60000}min")
            delay(delayMs)
            if (!shouldReconnect) break
            sendCmccBalanceQuery()
        }
    }

    private fun millisUntilNextQuery(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    private fun sendCmccBalanceQuery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            RemoteLogger.w(TAG, "CMCC balance query skipped: SEND_SMS permission not granted")
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage("10086", null, "103001", null, null)
            RemoteLogger.i(TAG, "CMCC balance query SMS sent")
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "Failed to send CMCC balance query SMS: ${e.message}")
        }
    }

    private suspend fun heartbeatLoop() {
        val deviceId = Secure.getString(contentResolver, Secure.ANDROID_ID)
        while (shouldReconnect) {
            try {
                val ip = settingsRepository.serverIp.first()
                val url = "http://$ip:8082/app/device/heartbeat?deviceId=$deviceId"
                val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
                okHttpClient.newCall(request).execute().use { resp ->
                    RemoteLogger.d(TAG, "Heartbeat ${if (resp.isSuccessful) "ok" else "failed: ${resp.code}"}")
                }
            } catch (e: Exception) {
                RemoteLogger.w(TAG, "Heartbeat error: ${e.message}")
            }
            delay(5 * 60 * 1000L) // 每 5 分钟
        }
    }

    private suspend fun tailscaleMonitorLoop() {
        while (shouldReconnect) {
            try {
                val ip = settingsRepository.serverIp.first()
                val diagnosis = runTailscaleDiagnosis(ip)
                val msg = buildDiagnosisMessage(diagnosis)
                if (diagnosis.result == DiagnosisResult.OK || diagnosis.result == DiagnosisResult.SLOW) {
                    RemoteLogger.d(MONITOR_TAG, msg)
                } else {
                    RemoteLogger.w(MONITOR_TAG, msg)

                    // 自动修复 Tailscale 假死：TCP 不通 + VPN 未激活 + Tailscale 已安装
                    // 注：ActivityManager.runningAppProcesses 在 Android 5.0+ 只返回自己进程，无法检测 Tailscale 进程，所以不做进程存活判断
                    if (diagnosis.result == DiagnosisResult.VPN_DOWN && diagnosis.tailscaleInstalled) {
                        val now = System.currentTimeMillis()
                        // 限制修复频率：至少间隔 5 分钟
                        if (now - lastAutoFixAttempt > 5 * 60 * 1000) {
                            RemoteLogger.i(MONITOR_TAG, "检测到 Tailscale 假死（TCP 不通且 VPN 未激活），尝试自动修复")
                            autoRestartTailscale()
                            lastAutoFixAttempt = now
                        }
                    }
                }
            } catch (e: Exception) {
                RemoteLogger.w(MONITOR_TAG, "Monitor error: ${e.message}")
            }
            delay(MONITOR_INTERVAL_MS)
        }
    }

    private enum class DiagnosisResult {
        OK, SLOW, TCP_FAIL, VPN_DOWN, NO_NETWORK, TAILSCALE_NOT_INSTALLED
    }

    private data class TailscaleDiagnosis(
        val result: DiagnosisResult,
        val latencyMs: Long,       // -1 表示连接失败
        val vpnActive: Boolean,
        val netType: String,       // "WIFI" / "CELLULAR" / "NONE"
        val tailscaleInstalled: Boolean
    )

    private fun runTailscaleDiagnosis(serverIp: String): TailscaleDiagnosis {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        // 检查物理网络类型
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val netType = when {
            caps == null -> "NONE"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }

        // 检查 VPN 是否活跃
        val vpnActive = cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }

        // 检查 Tailscale 是否安装
        val tailscaleInstalled = try {
            packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        // 无网络时直接返回
        if (netType == "NONE") {
            return TailscaleDiagnosis(DiagnosisResult.NO_NETWORK, -1, vpnActive, netType, tailscaleInstalled)
        }

        // Tailscale 未安装
        if (!tailscaleInstalled) {
            return TailscaleDiagnosis(DiagnosisResult.TAILSCALE_NOT_INSTALLED, -1, vpnActive, netType, tailscaleInstalled)
        }

        // TCP 连通性测试（无论 VPN 状态都先测一次：局域网直连也算通）
        val startMs = System.currentTimeMillis()
        val tcpSuccess = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(serverIp, 8082), 3000)
            }
            true
        } catch (e: Exception) {
            false
        }
        val latencyMs = if (tcpSuccess) System.currentTimeMillis() - startMs else -1L

        val result = when {
            tcpSuccess && latencyMs >= 1000 -> DiagnosisResult.SLOW
            tcpSuccess -> DiagnosisResult.OK
            // TCP 不通 + VPN 未激活 → Tailscale 假死，需要修复
            !vpnActive -> DiagnosisResult.VPN_DOWN
            // TCP 不通 + VPN 在（可能是 Clash 之类抢占）→ 不修复，用户主动开的
            else -> DiagnosisResult.TCP_FAIL
        }
        return TailscaleDiagnosis(result, latencyMs, vpnActive, netType, tailscaleInstalled)
    }

    /**
     * 自动修复 Tailscale 假死：
     * killBackgroundProcesses 对持有前台 VPN 服务的 Tailscale 无效，必须通过无障碍服务打开系统
     * "应用信息"页点击"强制停止"才能等价于手动操作。
     *
     * 流程：
     * 1. 触发 TailscaleRescueAccessibilityService.requestRescue() → 打开详情页
     * 2. 无障碍服务扫描窗口、点"强制停止"、点确认、拉起 Tailscale MainActivity
     * 3. 本方法 delay 10 秒后重新诊断，判断修复结果
     *
     * 前置：用户必须在"设置 → 无障碍 → 已下载的应用 → Capsule Tailscale 救援"开启服务。
     */
    private suspend fun autoRestartTailscale() {
        // TailscaleRescueAccessibilityService 未迁移到此项目，自动修复功能跳过
        RemoteLogger.w(MONITOR_TAG, "Tailscale 自动修复：无障碍服务未启用，跳过 rescue")
    }

    private fun buildDiagnosisMessage(d: TailscaleDiagnosis): String {
        return "${d.result} latency=${d.latencyMs}ms vpn=${d.vpnActive} net=${d.netType} tailscale=${if (d.tailscaleInstalled) "installed" else "not_installed"}"
    }

    /**
     * 注册 VPN 网络变化回调：Tailscale / Clash 的 VPN 网络消失时，立即触发快速诊断 + rescue。
     * 比 tailscaleMonitorLoop 的 2 分钟轮询快得多——假死发生后 3 秒内就能启动修复流程。
     */
    private fun registerVpnNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    RemoteLogger.i(MONITOR_TAG, "VPN onLost: $network, scheduling fast diagnosis in ${VPN_LOST_DEBOUNCE_MS}ms")
                    scope.launch { fastDiagnosisAndRescue() }
                }

                override fun onAvailable(network: android.net.Network) {
                    RemoteLogger.d(MONITOR_TAG, "VPN onAvailable: $network")
                }
            }
            cm.registerNetworkCallback(request, cb)
            vpnNetworkCallback = cb
            RemoteLogger.i(MONITOR_TAG, "VPN network callback registered (event-driven rescue)")
        } catch (e: Exception) {
            RemoteLogger.e(MONITOR_TAG, "Failed to register VPN network callback", e)
        }
    }

    private fun unregisterVpnNetworkCallback() {
        val cb = vpnNetworkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(cb)
            RemoteLogger.i(MONITOR_TAG, "VPN network callback unregistered")
        } catch (e: Exception) {
            RemoteLogger.w(MONITOR_TAG, "unregisterNetworkCallback failed: ${e.message}")
        }
        vpnNetworkCallback = null
    }

    /**
     * VPN 丢失事件触发的快速路径：等 3 秒缓冲（给正常的 VPN 切换留时间），
     * 然后诊断一次。若仍是 VPN_DOWN 且不在冷却期内，立即触发 rescue。
     */
    private suspend fun fastDiagnosisAndRescue() {
        try {
            delay(VPN_LOST_DEBOUNCE_MS)
            val ip = settingsRepository.serverIp.first()
            val diagnosis = runTailscaleDiagnosis(ip)
            RemoteLogger.i(MONITOR_TAG, "Fast diagnosis: ${buildDiagnosisMessage(diagnosis)}")
            if (diagnosis.result == DiagnosisResult.VPN_DOWN && diagnosis.tailscaleInstalled) {
                val now = System.currentTimeMillis()
                if (now - lastAutoFixAttempt > 5 * 60 * 1000) {
                    RemoteLogger.i(MONITOR_TAG, "事件驱动：检测到 VPN_DOWN，立即触发 rescue（不等 monitor loop）")
                    lastAutoFixAttempt = now
                    autoRestartTailscale()
                } else {
                    RemoteLogger.d(MONITOR_TAG, "Rescue skipped: within 5min cooldown")
                }
            }
        } catch (e: Exception) {
            RemoteLogger.e(MONITOR_TAG, "fastDiagnosisAndRescue failed", e)
        }
    }

    private suspend fun connectLoop() {
        val deviceId = buildDeviceId()
        while (shouldReconnect) {
            try {
                // 先关闭旧连接，防止重连时新旧连接同时存活导致收到重复消息
                webSocket?.close(1000, "Reconnecting")
                webSocket = null

                val ip = settingsRepository.serverIp.first()
                val url = "ws://$ip:8082/ws/push?deviceId=$deviceId"
                RemoteLogger.d(TAG, "Connecting to $url")
                val request = Request.Builder().url(url).build()
                webSocket = okHttpClient.newWebSocket(request, PushListener())
                // 等待连接断开再重连（通过 webSocket=null 标志位控制）
                while (shouldReconnect && webSocket != null) {
                    delay(1000)
                }
            } catch (e: Exception) {
                RemoteLogger.w(TAG, "Connect error: ${e.message}")
            }
            if (shouldReconnect) {
                RemoteLogger.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms")
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private inner class PushListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            RemoteLogger.d(TAG, "Push WebSocket connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            RemoteLogger.d(TAG, "Push message: $text")
            try {
                val json = JSONObject(text)
                val title = json.optString("title", "Capsule Code")
                val body = json.optString("body", "")
                val goodsLink = json.optString("goodsLink", "").takeIf { it.isNotEmpty() }
                if (body == "UPDATE_AVAILABLE") {
                    scope.launch { triggerAutoUpdate() }
                } else if (body == "QUERY_CMCC_NOW") {
                    sendCmccBalanceQuery()
                } else if (body == "MEMO_UPDATE") {
                    pushEventBus.emit("MEMO_UPDATE")
                } else if (body.startsWith("SERVICE_DOWN:")) {
                    val name = body.removePrefix("SERVICE_DOWN:")
                    showAlertNotification("巡检告警", "服务异常：$name", null)
                    pushEventBus.emit("SERVICE_HEALTH_UPDATED")
                } else if (body.isNotEmpty()) {
                    showAlertNotification(title, body, goodsLink)
                }
            } catch (e: Exception) {
                RemoteLogger.w(TAG, "Failed to parse push message: $text")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            RemoteLogger.d(TAG, "Push WebSocket closed: $code $reason")
            this@PushForegroundService.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            RemoteLogger.w(TAG, "Push WebSocket failure: ${t.message}")
            this@PushForegroundService.webSocket = null
        }
    }

    private suspend fun triggerAutoUpdate() {
        try {
            // 0. 检查安装权限（Android 8.0+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    RemoteLogger.w(TAG, "No install permission, showing notification to guide user")
                    showUpdateNotification("发现新版本")
                    return
                }
            }

            val ip = settingsRepository.serverIp.first()
            RemoteLogger.i(TAG, "Auto update triggered, checking version from $ip")

            // 1. 检查版本
            val versionConn = java.net.URL("http://$ip:8082/app/version?t=${System.currentTimeMillis()}")
                .openConnection() as java.net.HttpURLConnection
            versionConn.connectTimeout = 10_000
            versionConn.readTimeout = 10_000
            val versionJson = versionConn.inputStream.bufferedReader().readText()
            versionConn.disconnect()
            val serverVersionCode = org.json.JSONObject(versionJson).getInt("versionCode")
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

            if (serverVersionCode <= currentVersionCode) {
                RemoteLogger.i(TAG, "Already up to date (server=$serverVersionCode, current=$currentVersionCode)")
                return
            }

            RemoteLogger.i(TAG, "New version available: $serverVersionCode, downloading...")

            // 2. 下载 APK（最多重试 3 次，Tailscale 链路偶发下载失败）
            val apkDir = File(cacheDir, "apk").also { it.mkdirs() }
            apkDir.listFiles()?.forEach { it.delete() }
            val apkFile = File(apkDir, "app-${System.currentTimeMillis()}.apk")
            val maxRetries = 3
            var downloaded = false

            for (attempt in 1..maxRetries) {
                try {
                    if (attempt > 1) {
                        RemoteLogger.i(TAG, "Download retry $attempt/$maxRetries after 5s delay")
                        delay(5000)
                        apkFile.delete()
                    }
                    val conn = java.net.URL("http://$ip:8082/app/apk?t=${System.currentTimeMillis()}")
                        .openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                    conn.useCaches = false
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 120_000
                    conn.connect()
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()

                    if (apkFile.length() > 0L && (total <= 0 || apkFile.length() == total)) {
                        downloaded = true
                        break
                    }
                    RemoteLogger.w(TAG, "Download attempt $attempt incomplete: got ${apkFile.length()} expected $total")
                } catch (e: Exception) {
                    RemoteLogger.w(TAG, "Download attempt $attempt failed: ${e.message}")
                }
            }

            if (!downloaded) {
                apkFile.delete()
                RemoteLogger.e(TAG, "APK download failed after $maxRetries attempts")
                showAlertNotification("更新下载失败", "自动下载失败，请点击手动更新", null)
                return
            }

            RemoteLogger.i(TAG, "APK downloaded (${apkFile.length()} bytes), launching installer")

            // 3. 调系统安装器
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apkFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            RemoteLogger.e(TAG, "Auto update failed", e)
            showAlertNotification("自动更新失败", "请前往设置页手动检查更新", null)
        }
    }

    private fun showUpdateNotification(title: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SETTINGS, true)
        }
        val pi = PendingIntent.getActivity(this, NOTIF_ID_UPDATE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = Notification.Builder(this, CHANNEL_UPDATE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("点击前往设置页检查更新")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_UPDATE, notif)
    }

    private fun showAlertNotification(title: String, body: String, goodsLink: String? = null) {
        val intent = if (goodsLink != null) {
            // 商品链接：直接用浏览器或拼多多 App 打开
            android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(goodsLink)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        val pi = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun buildPersistentNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Capsule Code")
            .setContentText("CC 推送监听运行中")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // 常驻通知（静默，无声音）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PERSISTENT, "CC 推送服务", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )

        // 推送提醒（有声音）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "CC 推送提醒", NotificationManager.IMPORTANCE_HIGH)
        )

        // 更新通知（有声音）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, "CC 应用更新", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
