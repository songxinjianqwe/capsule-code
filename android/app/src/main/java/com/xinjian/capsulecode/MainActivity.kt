package com.xinjian.capsulecode

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.activity.viewModels
import com.xinjian.capsulecode.service.PushForegroundService
import com.xinjian.capsulecode.ui.claude.ClaudeScreen
import com.xinjian.capsulecode.ui.settings.SettingsViewModel
import com.xinjian.capsulecode.ui.settings.SettingsScreen
import com.xinjian.capsulecode.ui.theme.CapsuleAppTheme
import com.xinjian.capsulecode.util.RemoteLogger
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLastCrashIfAny()
        enableEdgeToEdge()
        // 深色主题下，状态栏图标用亮色
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // 高刷屏幕（对齐主 app）
        enableHighRefreshRate()
        // 运行时权限：Android 6+ Manifest 声明不够，要动态申请
        val needed = mutableListOf<String>().apply {
            // 讯飞语音录音
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            // Android 13+ 通知（Push + Crash Dialog）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 0)
        }
        // 启动 Push ForegroundService（否则 WS 不会自动连）
        PushForegroundService.start(this)
        // 冷启动时静默检查新版本，有则弹对话框让用户决定是否立即更新
        checkUpdateOnStart()

        // 对齐主 app：主页（claude）按返回需连续三次才退出；子页面正常 popBackStack
        // 单页版 capsule-code app 的"主页"只有 "claude"，其他路由都是子页
        var backPressCount = 0
        var backPressedTime = 0L
        var currentRoute: String? = null
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentRoute == "claude") {
                    val now = System.currentTimeMillis()
                    if (now - backPressedTime > 2000) backPressCount = 0   // 超时重置
                    backPressedTime = now
                    backPressCount++
                    when (backPressCount) {
                        1 -> Toast.makeText(this@MainActivity, "再按两次退出", Toast.LENGTH_SHORT).show()
                        2 -> Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                        else -> finish()
                    }
                } else {
                    // 子页面：临时禁用自身回调让系统默认行为 popBackStack，然后再启用
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            CapsuleAppTheme {
                val nav = rememberNavController()
                val backStackEntry by nav.currentBackStackEntryAsState()
                LaunchedEffect(backStackEntry) {
                    currentRoute = backStackEntry?.destination?.route
                }
                NavHost(
                    nav,
                    startDestination = "claude",
                    // 全局都用 slide（Claude 退出时也往左滑，和 Settings 进入连贯，避免露出背景闪烁）
                    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) },
                    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                ) {
                    composable("claude") {
                        ClaudeScreen(
                            navController = nav,
                            onOpenSettings = { nav.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    /**
     * 冷启动时检查 filesDir/last_crash.txt（由 CapsuleCodeApplication 里的 UncaughtExceptionHandler 写入），
     * 有则弹窗展示堆栈 + 支持一键复制到剪贴板，同时把全文再上报一次到后端 /callback/log。
     * 展示后立刻 delete 文件，避免重复弹。
     */
    private fun showLastCrashIfAny() {
        val crashFile = File(filesDir, "last_crash.txt")
        if (!crashFile.exists()) return
        val msg = try { crashFile.readText(Charsets.UTF_8) } catch (e: Exception) { "(读取 last_crash.txt 失败: ${e.message})" }
        crashFile.delete()
        RemoteLogger.e("CrashDialog", "上次崩溃原因（首行）: ${msg.lineSequence().firstOrNull()?.take(200)}")
        AlertDialog.Builder(this)
            .setTitle("上次崩溃原因")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .setNeutralButton("复制") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", msg))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private val settingsViewModel: SettingsViewModel by viewModels()

    /** 冷启动静默查一次更新。有更新弹对话框让用户选择立即更新 / 以后再说。 */
    private fun checkUpdateOnStart() {
        settingsViewModel.checkForUpdateSilently { newVersion ->
            if (isFinishing || isDestroyed) return@checkForUpdateSilently
            AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n最新版本：$newVersion\n\n建议立即更新以获取最新功能和修复。")
                .setPositiveButton("立即更新") { _, _ -> settingsViewModel.checkAndUpdate() }
                .setNegativeButton("以后再说", null)
                .show()
        }
    }

    /** 启用屏幕支持的最高刷新率，默认 60Hz（主 app 同款） */
    private fun enableHighRefreshRate() {
        try {
            val modes = display?.supportedModes ?: return
            val best = modes.maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        } catch (e: Exception) {
            RemoteLogger.w("MainActivity", "enableHighRefreshRate failed: ${e.message}")
        }
    }
}
