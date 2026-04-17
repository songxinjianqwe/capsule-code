package com.xinjian.capsulecode.ui.claude

import android.graphics.Typeface
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .thematicBreakColor(android.graphics.Color.parseColor("#555555"))
                        .thematicBreakHeight(2)
                }
            })
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(color.toArgb())
                textSize = fontSize.value
                setLineSpacing(0f, lineHeight.value / fontSize.value)
                autoLinkMask = Linkify.WEB_URLS
                linksClickable = true
                setLinkTextColor(android.graphics.Color.parseColor("#569CD6"))
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            markwon.setMarkdown(textView, markdown)
        }
    )
}
