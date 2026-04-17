package com.xinjian.capsulecode.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 远程日志工具，将日志先写入本地缓冲文件，flush 协程定期上传到后端 /callback/log。
 * 网络断开时日志保留在本地，网络恢复后自动补传。
 * 不依赖 Hilt，在 Application.onCreate 中调用 init() 初始化。
 */
object RemoteLogger {

    private const val TAG = "RemoteLogger"
    private const val MAX_BUFFER_BYTES = 1 * 1024 * 1024L  // 1MB
    private const val FLUSH_INTERVAL_MS = 5_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var serverIp: String = "192.168.1.100"
    private var deviceId: String = "unknown"
    private var bufferFile: File? = null

    fun init(context: Context, serverIp: String, deviceId: String) {
        this.serverIp = serverIp
        this.deviceId = deviceId
        bufferFile = File(context.filesDir, "remote_log_buffer.txt")
        CoroutineScope(Dispatchers.IO).launch { flushLoop() }
    }

    /** 更新 serverIp（Settings 里用户修改 IP 后调用）*/
    fun updateServerIp(ip: String) {
        serverIp = ip
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg); writeToBuffer("D", tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); writeToBuffer("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); writeToBuffer("W", tag, msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); writeToBuffer("E", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        writeToBuffer("E", tag, "$msg\n${tr.stackTraceToString()}")
    }

    @Synchronized
    private fun writeToBuffer(level: String, tag: String, msg: String) {
        val file = bufferFile ?: return
        try {
            // 超出 1MB 时丢弃旧数据：保留后 80% 的内容
            if (file.exists() && file.length() > MAX_BUFFER_BYTES) {
                val lines = file.readLines(Charsets.UTF_8)
                val keep = lines.drop(lines.size / 5)
                file.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
                Log.w(TAG, "Buffer truncated, kept ${keep.size}/${lines.size} lines")
            }
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("deviceId", deviceId)
                put("level", level)
                put("tag", tag)
                put("msg", msg)
            }.toString()
            file.appendText(entry + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write to buffer: ${e.message}")
        }
    }

    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    @Synchronized
    private fun flush() {
        val file = bufferFile ?: return
        if (!file.exists() || file.length() == 0L) return
        val lines = try {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read buffer: ${e.message}")
            return
        }
        if (lines.isEmpty()) return

        try {
            for (line in lines) {
                val json = JSONObject(line)
                val body = FormBody.Builder()
                    .add("deviceId", json.optString("deviceId", deviceId))
                    .add("level", json.optString("level", "D"))
                    .add("tag", json.optString("tag", "unknown"))
                    .add("msg", json.optString("msg", ""))
                    .build()
                val request = Request.Builder()
                    .url("http://$serverIp:8082/callback/log")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { /* 忽略响应 */ }
            }
            // 全部成功，清空缓冲文件
            file.writeText("", Charsets.UTF_8)
            Log.d(TAG, "Flushed ${lines.size} log entries")
        } catch (e: Exception) {
            // 上传失败，保留文件等下次 flush
            Log.w(TAG, "Flush failed, will retry: ${e.message}")
        }
    }
}
