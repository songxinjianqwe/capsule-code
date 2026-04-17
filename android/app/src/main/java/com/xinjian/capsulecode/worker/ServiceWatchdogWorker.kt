package com.xinjian.capsulecode.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.xinjian.capsulecode.service.PushForegroundService
import com.xinjian.capsulecode.util.RemoteLogger

/**
 * WorkManager 周期任务：每 15 分钟检查一次 PushForegroundService 是否存活，
 * 如果已被系统杀掉则重新拉起。
 *
 * WorkManager 由系统维护，即使 App 进程被杀，下次调度时仍会重新执行此 Worker。
 */
class ServiceWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            RemoteLogger.d(TAG, "Watchdog tick — restarting PushForegroundService")
            PushForegroundService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            RemoteLogger.w(TAG, "Watchdog failed to start service: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ServiceWatchdog"
        const val WORK_NAME = "service_watchdog"
    }
}
