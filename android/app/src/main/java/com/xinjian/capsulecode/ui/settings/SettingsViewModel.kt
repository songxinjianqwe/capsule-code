package com.xinjian.capsulecode.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.xinjian.capsulecode.util.RemoteLogger
import androidx.lifecycle.viewModelScope
import com.xinjian.capsulecode.BuildConfig
import com.xinjian.capsulecode.data.repository.SettingsRepository
import com.xinjian.capsulecode.service.PushForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject

data class UpdateState(val status: String, val progress: Int = 0)
// status: "idle" | "checking" | "downloading" | "installing" | "up_to_date" | "error"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val serverIp = settingsRepository.serverIp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULT_IP
    )

    val pushEnabled = settingsRepository.pushEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val accountMode = settingsRepository.accountMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "max"
    )

    private val _updateState = MutableStateFlow(UpdateState("idle"))
    val updateState: StateFlow<UpdateState> = _updateState

    private val _notifListenerGranted = MutableStateFlow(false)
    val notifListenerGranted: StateFlow<Boolean> = _notifListenerGranted

    private val _a11yGranted = MutableStateFlow(false)
    val a11yGranted: StateFlow<Boolean> = _a11yGranted

    fun refreshNotifListenerPermission() {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        _notifListenerGranted.value = flat.contains(context.packageName)
        val a11y = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        _a11yGranted.value = a11y.contains(context.packageName)
    }

    fun openNotifListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openA11ySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun saveIp(ip: String) {
        viewModelScope.launch {
            settingsRepository.saveIp(ip)
        }
    }

    fun setAccountMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.saveAccountMode(mode)
        }
    }

    fun setPushEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.savePushEnabled(enabled)
            if (enabled) {
                PushForegroundService.start(context)
            } else {
                PushForegroundService.stop(context)
            }
        }
    }

    fun queryCmccBalance() {
        PushForegroundService.queryCmccBalanceNow(context)
    }

    /**
     * 静默检查版本（供 MainActivity 冷启动调用）。有新版本时回调 onUpdateAvailable(versionName)，
     * 无更新 / 请求失败都静默返回。超时 5 秒，不阻塞 UI。
     */
    fun checkForUpdateSilently(onUpdateAvailable: (serverVersionName: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ip = settingsRepository.serverIp.first()
                val conn = URL("http://$ip:8082/app/version?t=${System.currentTimeMillis()}")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val code = json.getInt("versionCode")
                val name = json.optString("versionName", "")
                if (code > BuildConfig.VERSION_CODE && json.optLong("size", 0L) > 0) {
                    RemoteLogger.i("SettingsVM", "silent check: new version $name ($code) available (current ${BuildConfig.VERSION_CODE})")
                    withContext(Dispatchers.Main) { onUpdateAvailable(name) }
                }
            } catch (e: Exception) {
                RemoteLogger.w("SettingsVM", "silent check failed: ${e.message}")
            }
        }
    }

    fun checkAndUpdate() {
        if (_updateState.value.status != "idle") return
        viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = UpdateState("checking")
            try {
                // 0. 检查安装权限（Android 8.0+）
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        RemoteLogger.w("SettingsVM", "No install permission, redirecting to settings")
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        _updateState.value = UpdateState("idle")
                        return@launch
                    }
                }

                val ip = settingsRepository.serverIp.first()
                // 1. 获取服务端 versionCode，设置 10s 超时防止卡死
                val versionConn = URL("http://$ip:8082/app/version?t=${System.currentTimeMillis()}").openConnection() as java.net.HttpURLConnection
                versionConn.connectTimeout = 10_000
                versionConn.readTimeout = 10_000
                val versionJson = versionConn.inputStream.bufferedReader().readText()
                val versionObj = org.json.JSONObject(versionJson)
                val serverVersionCode = versionObj.getInt("versionCode")
                val serverApkSize = versionObj.optLong("size", 0L)

                // 2. 用 versionCode 整数比较，与系统安装判断逻辑一致
                if (serverVersionCode <= BuildConfig.VERSION_CODE) {
                    _updateState.value = UpdateState("up_to_date")
                    delay(2000)
                    _updateState.value = UpdateState("idle")
                    return@launch
                }

                // 2.5 下载前校验：APK 文件必须存在（size > 0）
                if (serverApkSize <= 0L) {
                    RemoteLogger.e("SettingsVM", "APK not ready on server: size=$serverApkSize")
                    _updateState.value = UpdateState("error")
                    delay(2000)
                    _updateState.value = UpdateState("idle")
                    return@launch
                }

                // 3. 下载 APK，用时间戳命名避免任何文件缓存，同时清理旧文件
                _updateState.value = UpdateState("downloading", 0)
                val apkDir = File(context.cacheDir, "apk").also { it.mkdirs() }
                // 清理目录下所有旧 APK
                apkDir.listFiles()?.forEach { it.delete() }
                val apkFile = File(apkDir, "app-${System.currentTimeMillis()}.apk")
                // 加时间戳参数彻底绕过任何系统级 HTTP 缓存
                val conn = URL("http://$ip:8082/app/apk?t=${System.currentTimeMillis()}").openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                conn.setRequestProperty("Pragma", "no-cache")
                conn.useCaches = false
                conn.connectTimeout = 10_000   // 连接超时 10s
                conn.readTimeout = 60_000      // 读超时 60s（大文件分块传输，每块之间不超过 60s）
                conn.connect()  // 先建立连接，响应头才可用
                val total = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            output.write(buf, 0, len)
                            downloaded += len
                            if (total > 0) {
                                _updateState.value = UpdateState("downloading", (downloaded * 100 / total).toInt())
                            }
                        }
                    }
                }

                // 校验文件完整性：大小必须与服务端一致（total > 0 时），且不能为 0
                if (apkFile.length() == 0L || (total > 0 && apkFile.length() != total)) {
                    apkFile.delete()
                    RemoteLogger.e("SettingsVM", "APK download incomplete: got ${apkFile.length()} expected $total")
                    _updateState.value = UpdateState("error")
                    delay(2000)
                    _updateState.value = UpdateState("idle")
                    return@launch
                }

                // 4. 调系统安装器
                _updateState.value = UpdateState("installing")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _updateState.value = UpdateState("idle")
            } catch (e: Exception) {
                RemoteLogger.e("SettingsVM", "Update failed", e)
                _updateState.value = UpdateState("error")
                delay(2000)
                _updateState.value = UpdateState("idle")
            }
        }
    }
}

