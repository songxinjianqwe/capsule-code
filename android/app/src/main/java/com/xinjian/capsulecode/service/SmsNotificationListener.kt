package com.xinjian.capsulecode.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.xinjian.capsulecode.data.network.ApiService
import com.xinjian.capsulecode.util.RemoteLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 监听通知栏，根据规则配置自动提取各类验证码并推送后端。
 * 新增渠道只需在 RULES 中加一条 CaptchaRule，无需改动逻辑。
 */
@AndroidEntryPoint
class SmsNotificationListener : NotificationListenerService() {

    /**
     * 通知匹配规则：
     * - keywords：通知文本包含任意一个关键词即命中
     * - valueRegex：从通知文本提取目标值，第一个捕获组为结果
     * - push：拿到提取值后调用的推送函数（suspend lambda，参数为提取到的字符串）
     */
    data class NotificationRule(
        val channel: String,
        val keywords: List<String>,
        val valueRegex: Regex,
        val push: suspend ApiService.(value: String) -> Unit
    )

    companion object {
        private const val TAG = "SmsNotifListener"

        fun buildRules(): List<NotificationRule> = listOf(
            // 国网验证码
            NotificationRule(
                channel = "sgcc",
                keywords = listOf("网上国网", "国网"),
                valueRegex = Regex("""】(\d{6})[，,]"""),
                push = { code -> pushElectricCaptchaCode("sgcc", code) }
            ),
            // 中国移动话费余额（发 103001 给 10086 触发）
            NotificationRule(
                channel = "cmcc",
                keywords = listOf("话费查询", "话费账户余额"),
                valueRegex = Regex("""话费账户余额(\d+\.?\d*)元"""),
                push = { balance -> pushCmccBalance(balance) }
            ),
            // 新增示例（取消注释即生效）：
            // NotificationRule(
            //     channel = "cmb",
            //     keywords = listOf("招商银行", "招行"),
            //     valueRegex = Regex("""验证码[：:]\s*(\d{6})"""),
            //     push = { code -> pushElectricCaptchaCode("cmb", code) }
            // ),
        )
    }

    @Inject lateinit var apiService: ApiService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rules by lazy { buildRules() }

    // 去重：记录最近处理过的通知 key → 时间戳，5 秒内同一条通知不重复触发
    private val recentKeys = HashMap<String, Long>()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // 去重：5 秒内同一条通知（相同 key）只处理一次
        val now = System.currentTimeMillis()
        val key = sbn.key
        val lastSeen = recentKeys[key]
        if (lastSeen != null && now - lastSeen < 5000L) return
        recentKeys[key] = now
        // 清理超过 30 秒的旧记录，防止内存泄漏
        recentKeys.entries.removeAll { now - it.value > 30_000L }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val fullText = "$title $text $bigText"

        RemoteLogger.d(TAG, "Notification from=${sbn.packageName} title=$title text=$text")

        val rule = rules.firstOrNull { r -> r.keywords.any { it in fullText } } ?: return

        RemoteLogger.i(TAG, "[${rule.channel}] notification detected: ${fullText.take(80)}")
        val value = rule.valueRegex.find(fullText)?.groupValues?.get(1)
        if (value == null) {
            RemoteLogger.w(TAG, "[${rule.channel}] failed to extract value from: $fullText")
            return
        }

        RemoteLogger.i(TAG, "[${rule.channel}] value extracted: $value")
        scope.launch {
            try {
                rule.push(apiService, value)
                RemoteLogger.i(TAG, "[${rule.channel}] pushed successfully")
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "[${rule.channel}] failed to push: ${e.message}")
            }
        }
    }
}
