package com.xinjian.capsulecode.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xinjian.capsulecode.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val savedIp by viewModel.serverIp.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    // 快捷 IP 选项
    data class IpOption(val label: String, val ip: String)
    val ipOptions = listOf(
        IpOption("局域网", "192.168.1.100"),
        IpOption("公网", "10.0.0.1")
    )

    var ipInput by remember(savedIp) { mutableStateOf(savedIp) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 服务器 IP ──────────────────────────────────────────────
            Text("服务器 IP 地址", style = MaterialTheme.typography.titleMedium)

            // 快捷选择
            ipOptions.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = savedIp == option.ip,
                        onClick = {
                            viewModel.saveIp(option.ip)
                            ipInput = option.ip
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${option.label}（${option.ip}）")
                }
            }

            // 自定义输入
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("自定义 IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { viewModel.saveIp(ipInput) }) {
                        Text("保存")
                    }
                }
            )

            HorizontalDivider()

            // ── OTA 更新 ────────────────────────────────────────────────
            Text("版本更新", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (updateState.status) {
                "checking" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在检查更新…")
                    }
                }
                "downloading" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("正在下载 APK… ${updateState.progress}%")
                        LinearProgressIndicator(
                            progress = { updateState.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                "installing" -> Text("正在安装…")
                "up_to_date" -> Text("已是最新版本", color = MaterialTheme.colorScheme.primary)
                "error" -> Text("更新失败，请重试", color = MaterialTheme.colorScheme.error)
                else -> {
                    Button(
                        onClick = { viewModel.checkAndUpdate() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("检查更新")
                    }
                }
            }

        }
    }
}
