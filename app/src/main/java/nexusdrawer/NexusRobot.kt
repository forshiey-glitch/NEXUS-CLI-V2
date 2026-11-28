package com.nexus.cli.nexusdrawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.nexus.nexus_cli_v2.ui.theme.PandoraGlow
import com.nexus.nexus_cli_v2.ui.theme.PandoraGreen
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val RobotCyan = Color(0xFF00E5FF)
private val RobotError = Color(0xFFFF5252)
private val RobotFace = Color(0xFF07281A)
private val RobotShade = Color(0xFF00C97A)
private const val TAU = (2.0 * PI).toFloat()

/** Robot chibi digambar di ruang 200x200 lalu diskalakan ke ukuran Canvas. */
@Composable
fun NexusRobot(
    state: NexusPresenceState,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "robot")
    val floatPhase by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "float"
    )
    val pulsePhase by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse"
    )
    val orbit by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "orbit"
    )
    val rings by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "rings"
    )
    val mouth by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(170, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mouth"
    )

    // Kedip acak
    val blink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200L, 5200L))
            blink.animateTo(1f, tween(70))
            blink.animateTo(0f, tween(110))
            if (Random.nextInt(4) == 0) {
                delay(120)
                blink.animateTo(1f, tween(70))
                blink.animateTo(0f, tween(110))
            }
        }
    }

    val openFrac by animateFloatAsState(
        targetValue = when (state) {
            NexusPresenceState.IDLE -> 0.5f      // mata setengah
            NexusPresenceState.THINKING -> 0.85f
            else -> 1f
        },
        animationSpec = tween(260), label = "open"
    )
    val listenScale by animateFloatAsState(
        targetValue = if (state == NexusPresenceState.LISTENING) 1f else 0f,
        animationSpec = tween(260), label = "listen"
    )
    val amp by animateFloatAsState(amplitude.coerceIn(0f, 1f), tween(120), label = "amp")

    Canvas(modifier) {
        val s = size.minDimension / 200f
        val floatY = sin(floatPhase * TAU) * 6f
        val pulse = 0.5f + 0.5f * sin(pulsePhase * TAU)
        val accent = if (state == NexusPresenceState.ERROR) RobotError else PandoraGlow

        scale(s, Offset.Zero) {
            drawGroundShadow(floatY)
            if (state == NexusPresenceState.LISTENING) drawListenRings(rings, amp)
            translate(top = floatY) {
                drawAura(pulse)
                drawAntenna(pulse, amp, accent)
                drawBody(pulse, accent)
                drawHead()
                drawEyes(state, openFrac, blink.value, listenScale)
                drawMouth(state, mouth, amp)
                if (state == NexusPresenceState.THINKING) drawParticles(orbit, pulse)
            }
        }
    }
}

private fun DrawScope.drawGroundShadow(floatY: Float) {
    val w = 56f + floatY * 1.2f
    drawOval(PandoraGreen.copy(alpha = 0.18f), Offset(100f - w / 2f, 188f), Size(w, 8f))
}

private fun DrawScope.drawListenRings(phase: Float, amp: Float) {
    for (i in 0..1) {
        val p = (phase + i * 0.5f) % 1f
        val r = 56f + 36f * p * (0.8f + 0.4f * amp)
        val a = (1f - p) * (0.25f + 0.45f * amp)
        drawCircle(PandoraGreen.copy(alpha = a), r, Offset(100f, 92f), style = Stroke(2.5f))
    }
}

private fun DrawScope.drawAura(pulse: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(PandoraGreen.copy(alpha = 0.16f + 0.08f * pulse), Color.Transparent),
            center = Offset(100f, 100f), radius = 96f
        ),
        radius = 96f, center = Offset(100f, 100f)
    )
}

private fun DrawScope.drawAntenna(pulse: Float, amp: Float, accent: Color) {
    drawLine(RobotShade, Offset(100f, 42f), Offset(100f, 22f), 4f, StrokeCap.Round)
    val c = Offset(100f, 16f)
    val glowR = 11f + 5f * pulse + 4f * amp
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.85f), Color.Transparent), c, glowR),
        glowR, c
    )
    drawCircle(accent, 6.5f, c)
    drawCircle(Color.White.copy(alpha = 0.7f), 2f, Offset(98f, 14f))
}

private fun DrawScope.drawBody(pulse: Float, accent: Color) {
    // lengan
    drawLine(RobotShade, Offset(72f, 144f), Offset(56f, 160f), 11f, StrokeCap.Round)
    drawLine(RobotShade, Offset(128f, 144f), Offset(144f, 160f), 11f, StrokeCap.Round)
    // kaki
    drawRoundRect(RobotShade, Offset(80f, 172f), Size(16f, 8f), CornerRadius(4f))
    drawRoundRect(RobotShade, Offset(104f, 172f), Size(16f, 8f), CornerRadius(4f))
    // badan
    drawRoundRect(
        Brush.verticalGradient(listOf(PandoraGreen, RobotShade), startY = 124f, endY = 174f),
        Offset(70f, 124f), Size(60f, 50f), CornerRadius(22f)
    )
    // core
    val c = Offset(100f, 152f)
    drawCircle(RobotFace, 12f, c)
    val gr = 14f + 6f * pulse
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.9f), Color.Transparent), c, gr), gr, c)
    drawCircle(accent, 7f, c)
}

private fun DrawScope.drawHead() {
    drawCircle(RobotShade, 11f, Offset(34f, 82f))
    drawCircle(RobotShade, 11f, Offset(166f, 82f))
    drawRoundRect(
        Brush.verticalGradient(listOf(PandoraGreen, RobotShade), startY = 38f, endY = 128f),
        Offset(36f, 38f), Size(128f, 90f), CornerRadius(38f)
    )
    drawOval(Color.White.copy(alpha = 0.22f), Offset(54f, 45f), Size(34f, 9f))
    drawRoundRect(RobotFace, Offset(50f, 56f), Size(100f, 58f), CornerRadius(26f))
    drawCircle(PandoraGreen.copy(alpha = 0.25f), 5f, Offset(62f, 100f))
    drawCircle(PandoraGreen.copy(alpha = 0.25f), 5f, Offset(138f, 100f))
}

private fun DrawScope.drawEyes(
    state: NexusPresenceState,
    openFrac: Float,
    blink: Float,
    listenScale: Float
) {
    val cy = 80f
    val xs = floatArrayOf(80f, 120f)

    if (state == NexusPresenceState.ERROR) {
        for (cx in xs) {
            drawLine(RobotError, Offset(cx - 8f, cy - 8f), Offset(cx + 8f, cy + 8f), 5f, StrokeCap.Round)
            drawLine(RobotError, Offset(cx + 8f, cy - 8f), Offset(cx - 8f, cy + 8f), 5f, StrokeCap.Round)
        }
        return
    }

    val rx = 13f * (1f + 0.12f * listenScale)
    val ry0 = 13f * (1f + 0.25f * listenScale)
    val f = (openFrac * (1f - blink)).coerceIn(0f, 1f)
    val thinking = state == NexusPresenceState.THINKING
    val hx = if (thinking) rx * 0.4f else rx * 0.25f
    val hy = if (thinking) -ry0 * 0.2f else ry0 * 0.2f
    val hr = 5.5f * (1f + 0.2f * listenScale)

    for (cx in xs) {
        drawOval(Color.White, Offset(cx - rx, cy - ry0), Size(rx * 2f, ry0 * 2f))
        drawCircle(RobotCyan, hr, Offset(cx + hx, cy + hy))
        // kelopak: menutup bagian atas mata
        if (f < 0.999f) {
            drawRect(RobotFace, Offset(cx - rx - 1f, cy - ry0 - 1f), Size(rx * 2f + 2f, ry0 * 2f * (1f - f) + 1f))
        }
    }
}

private fun DrawScope.drawMouth(state: NexusPresenceState, mouth: Float, amp: Float) {
    val st = Stroke(3.2f, cap = StrokeCap.Round)
    when (state) {
        NexusPresenceState.IDLE -> {
            val p = Path().apply { moveTo(92f, 102f); quadraticTo(100f, 110f, 108f, 102f) }
            drawPath(p, PandoraGreen, style = st)
        }
        NexusPresenceState.LISTENING ->
            drawCircle(PandoraGreen, 4f, Offset(100f, 105f), style = Stroke(3f))
        NexusPresenceState.THINKING ->
            drawLine(PandoraGreen, Offset(93f, 105f), Offset(107f, 105f), 3.2f, StrokeCap.Round)
        NexusPresenceState.SPEAKING -> {
            val h = 3f + 9f * mouth * (0.4f + 0.6f * amp)
            drawOval(PandoraGreen, Offset(93f, 104f - h / 2f), Size(14f, h))
        }
        NexusPresenceState.ERROR -> {
            val p = Path().apply { moveTo(92f, 109f); quadraticTo(100f, 101f, 108f, 109f) }
            drawPath(p, RobotError, style = st)
        }
    }
}

private fun DrawScope.drawParticles(orbit: Float, pulse: Float) {
    val n = 7
    for (i in 0 until n) {
        val a = (orbit + i / n.toFloat()) * TAU
        val depth = 0.5f + 0.5f * sin(a)
        val pos = Offset(100f + 88f * cos(a), 78f + 54f * sin(a))
        val color = if (i % 2 == 0) PandoraGlow else RobotCyan
        val r = 2.6f + 2.2f * depth + 0.6f * pulse
        drawCircle(color.copy(alpha = 0.35f + 0.55f * depth), r, pos)
    }
}