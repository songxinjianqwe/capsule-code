package com.xinjian.capsulecode

import android.app.Application
import android.provider.Settings
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xinjian.capsulecode.BuildConfig
import com.xinjian.capsulecode.data.network.ApiService
import com.xinjian.capsulecode.data.repository.SettingsRepository
import com.xinjian.capsulecode.util.RemoteLogger
import com.xinjian.capsulecode.util.XfyunAsrManager
import com.xinjian.capsulecode.worker.ServiceWatchdogWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CapsuleCodeApplication : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var apiService: ApiService

    override fun onCreate() {
        super.onCreate()

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        RemoteLogger.init(this, SettingsRepository.DEFAULT_IP, androidId)
        CoroutineScope(Dispatchers.IO).launch {
            val ip = settingsRepository.serverIp.first()
            RemoteLogger.updateServerIp(ip)
            // 上报当前设备版本（让后端 /app/device/versions 知道 app 装了哪版）
            try {
                apiService.reportDeviceVersion(
                    deviceId = androidId,
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME,
                    manufacturer = android.os.Build.MANUFACTURER
                )
                RemoteLogger.i("CapsuleCodeApp", "Version reported: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            } catch (e: Exception) {
                RemoteLogger.w("CapsuleCodeApp", "Failed to report version: ${e.message}")
            }
            // 拉讯飞凭证 + 初始化 SparkChain SDK（key 不硬编码进 APK）
            try {
                val creds = apiService.getXfyunCredentials()
                XfyunAsrManager.initSdk(
                    this@CapsuleCodeApplication,
                    creds["appid"].orEmpty(),
                    creds["apikey"].orEmpty(),
                    creds["apisecret"].orEmpty()
                )
            } catch (e: Exception) {
                RemoteLogger.e("CapsuleCodeApp", "拉取 xfyun credentials 失败：${e.message}", e)
            }
            settingsRepository.serverIp.collect { RemoteLogger.updateServerIp(it) }
        }

        scheduleServiceWatchdog()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(throwable.stackTraceToString(), Charsets.UTF_8)
            } catch (_: Exception) {}
            RemoteLogger.e("CapsuleCodeApp", "UncaughtException on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun scheduleServiceWatchdog() {
        val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ServiceWatchdogWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        RemoteLogger.d("CapsuleCodeApp", "ServiceWatchdog scheduled (15 min interval)")
    }
}
