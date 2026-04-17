package com.xinjian.capsulecode.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import com.xinjian.capsulecode.util.RemoteLogger

/**
 * 封装讯飞星火语音识别（ASR）+ Android AudioRecord 录音。
 *
 * 使用方式（push-to-talk）：
 *   startRecording()  → 用户按住按钮时调用
 *   stopRecording()   → 用户松开按钮时调用
 *
 * 回调（在调用线程上触发，需 UI 操作时请切到主线程）：
 *   onPartialResult  → 识别中间结果（实时显示）
 *   onFinalResult    → 识别最终结果（status == 2）
 *   onError          → 出错信息
 */
class XfyunAsrManager(private val context: Context) {

    companion object {
        private const val TAG = "XfyunAsrManager"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 1280  // 40ms @16kHz 16bit mono

        @Volatile private var initialized = false
        fun isInitialized(): Boolean = initialized

        /**
         * 用从后端拉来的凭证初始化 SparkChain SDK。
         * 凭证为空时不初始化，让后续录音时报错而不是启动时挂。
         */
        fun initSdk(context: Context, appId: String, apiKey: String, apiSecret: String) {
            if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
                RemoteLogger.w(TAG, "SparkChain SDK 凭证为空，跳过初始化（录音功能不可用）")
                initialized = false
                return
            }
            val config = SparkChainConfig.builder()
                .appID(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
            val ret = SparkChain.getInst().init(context.applicationContext, config)
            initialized = (ret == 0)
            if (initialized) {
                RemoteLogger.i(TAG, "SparkChain SDK 初始化成功 appid=${appId.take(4)}***")
            } else {
                RemoteLogger.e(TAG, "SparkChain SDK 初始化失败，错误码：$ret")
            }
        }
    }

    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var asr: ASR? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null
    private var sessionCount = 0

    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(result: ASR.ASRResult, o: Any?) {
            val text = result.bestMatchText ?: return
            val status = result.status
            RemoteLogger.d(TAG, "ASR onResult status=$status text=$text")
            when (status) {
                0, 1 -> onPartialResult?.invoke(text)  // 中间结果
                2 -> {
                    onFinalResult?.invoke(text)         // 最终结果
                    cleanup()
                }
            }
        }

        override fun onError(error: ASR.ASRError, o: Any?) {
            val msg = "识别出错 code=${error.code} msg=${error.errMsg}"
            RemoteLogger.e(TAG, msg)
            onError?.invoke(msg)
            cleanup()
        }

        override fun onBeginOfSpeech() {
            RemoteLogger.d(TAG, "ASR onBeginOfSpeech")
        }

        override fun onEndOfSpeech() {
            RemoteLogger.d(TAG, "ASR onEndOfSpeech")
        }
    }

    fun startRecording() {
        if (isRecording) return
        if (!isInitialized()) {
            RemoteLogger.w(TAG, "SparkChain SDK 未初始化，无法录音（凭证未下发或下发失败）")
            onError?.invoke("语音服务未就绪，请检查后端连接")
            return
        }
        RemoteLogger.i(TAG, "开始录音")

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_BYTES * 4
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("AudioRecord 初始化失败")
            record.release()
            return
        }

        val asrInstance = ASR().also {
            it.registerCallbacks(asrCallbacks)
            it.language("zh_cn")
            it.domain("iat")
            it.accent("mandarin")
            it.vinfo(true)
            it.dwa("wpgs")  // 动态修正，支持中间结果
        }

        sessionCount++
        val ret = asrInstance.start(sessionCount.toString())
        if (ret != 0) {
            onError?.invoke("ASR start 失败，错误码：$ret")
            record.release()
            return
        }

        audioRecord = record
        asr = asrInstance
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(FRAME_BYTES)
            RemoteLogger.d(TAG, "录音线程启动")
            while (isRecording) {
                val read = record.read(buffer, 0, FRAME_BYTES)
                if (read > 0 && isRecording) {
                    asrInstance.write(buffer.copyOf(read))
                }
            }
            RemoteLogger.d(TAG, "录音线程结束")
        }.also { it.start() }
    }

    fun stopRecording() {
        if (!isRecording) return
        RemoteLogger.i(TAG, "停止录音，等待最终识别结果")
        isRecording = false
        recordingThread?.join(500)
        safeStopAudioRecord()
        asr?.stop(false)  // false = 等云端返回最后一帧再结束
    }

    fun release() {
        isRecording = false
        recordingThread?.join(300)
        safeStopAudioRecord()
        audioRecord?.release()
        audioRecord = null
        asr?.stop(true)
        asr = null
    }

    private fun cleanup() {
        isRecording = false
        safeStopAudioRecord()
        audioRecord?.release()
        audioRecord = null
        asr = null
    }

    private fun safeStopAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            RemoteLogger.w(TAG, "AudioRecord.stop() 忽略：${e.message}")
        }
    }
}
