package com.nexus.cli.nexusdrawer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexus.nexus_cli_v2.ui.theme.PandoraGreen
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun NexusWaveMini(
    state: NexusPresenceState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "wave")
    val phase by inf.animateFloat(
        0f, (2.0 * PI).toFloat(),
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "phase"
    )
    val amp by animateFloatAsState(
        amplitude.coerceIn(0.04f, 1f), tween(120), label = "amp"
    )
    val color = if (state == NexusPresenceState.ERROR) Color(0xFFFF5252) else PandoraGreen

    Canvas(modifier) {
        val bars = 21
        val gap = size.width / bars
        val barW = gap * 0.5f
        val minH = 3.dp.toPx()
        val mid = size.height / 2f
        for (i in 0 until bars) {
            val x = gap * (i + 0.5f)
            val env = sin(PI * (i + 0.5) / bars).toFloat()
            val wob = 0.5f + 0.5f * sin(phase + i * 0.55f)
            val h = (minH + (size.height - minH) * env * wob * amp).coerceAtLeast(minH)
            drawRoundRect(
                color.copy(alpha = 0.35f + 0.65f * env),
                Offset(x - barW / 2f, mid - h / 2f),
                Size(barW, h),
                CornerRadius(barW / 2f)
            )
        }
    }
}