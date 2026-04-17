package com.xinjian.capsulecode.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 豆包风格的长按录音浮层。
 *
 * 设计思路：
 * - 底部 220dp 大区域，顶部大圆角 24dp 形成明确的卡片边界
 * - 背景用 Linear 的最亮层 surfaceContainerHighest (#2E2F33)，和 background (#111213) 对比明显
 * - 顶部一圈 primary tint 描边，强化"浮起来的卡片"的层次感
 * - 不放图标：整个区域都是录音响应区，不要让"圆形图标"误导用户以为只有它能按
 * - 只保留极简的文字提示 + partial 识别文本
 * - cancel 状态主文字用 error 色强调
 *
 * 使用 Popup(focusable = false) 让触摸事件穿透，不干扰原 pointerInput 手势锁定。
 */
@Composable
fun VoiceRecordingOverlay(
    willCancel: Boolean,
    partialText: String?,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = false)
    ) {
        val overlayShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(overlayShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = overlayShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text(
                    text = if (willCancel) "松开取消" else "松开发送",
                    color = if (willCancel) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight(590)
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "上移手指取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight(400)
                )

                // partial 文本预览（固定高度区域，避免内容跳动）
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (!willCancel && !partialText.isNullOrBlank()) partialText else "",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight(400),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
