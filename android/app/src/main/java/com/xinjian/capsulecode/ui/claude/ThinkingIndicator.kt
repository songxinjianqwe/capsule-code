package com.xinjian.capsulecode.ui.claude

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ThinkingIndicator(
    elapsedSeconds: Int,
    modifier: Modifier = Modifier,
) {
    var word by remember { mutableStateOf(THINKING_WORDS.random()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            word = THINKING_WORDS.random()
        }
    }

    val transition = rememberInfiniteTransition(label = "thinking")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha"
    )
    val pulseScale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale"
    )
    val hueStep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hueStep"
    )

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val targetColor = when (hueStep.toInt()) {
        0 -> primary
        1 -> onSurface
        else -> lerp(primary, onSurface, 0.5f)
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(1500, easing = LinearEasing),
        label = "colorShift"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "✻",
            fontSize = 14.sp,
            color = animatedColor,
            fontWeight = FontWeight(590),
            modifier = Modifier
                .alpha(pulseAlpha)
                .scale(pulseScale)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = word,
            fontSize = 12.sp,
            color = animatedColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight(510),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "(${elapsedSeconds}s)",
            fontSize = 11.sp,
            color = muted,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
