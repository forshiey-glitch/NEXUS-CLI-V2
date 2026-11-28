package com.nexus.cli.nexusdrawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.nexus_cli_v2.ui.theme.PandoraDark
import com.nexus.nexus_cli_v2.ui.theme.PandoraDim
import com.nexus.nexus_cli_v2.ui.theme.PandoraGlow
import com.nexus.nexus_cli_v2.ui.theme.PandoraGreen
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ErrorRed = Color(0xFFFF5252)
private val BubbleBg = Color(0xFF0F2E20)
private val ButtonBg = Color(0xFF12301F)

@Composable
fun NexusDrawer(
    state: NexusPresenceState,
    amplitude: Float,
    statusText: String,
    message: String,
    micActive: Boolean,
    onMic: () -> Unit,
    onKeyboard: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val statusColor by animateColorAsState(
        if (state == NexusPresenceState.ERROR) ErrorRed else PandoraGreen, label = "status"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PandoraDark)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(PandoraDim, Offset(size.width - w, 0f), Size(w, size.height))
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "N E X U S",
            color = PandoraGreen.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )

        Spacer(Modifier.weight(1f))

        SpeechBubble(message, state)
        Spacer(Modifier.height(8.dp))

        if (active) {
            NexusRobot(state, amplitude, Modifier.size(190.dp))
        } else {
            Spacer(Modifier.size(190.dp))
        }

        Spacer(Modifier.height(4.dp))
        if (active) {
            NexusWaveMini(state, amplitude, Modifier.width(200.dp).height(36.dp))
        } else {
            Spacer(Modifier.height(36.dp))
        }

        Spacer(Modifier.height(10.dp))
        Text(
            statusText,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NexusIconButton("Mikrofon", micActive, onMic) { iconMic(it) }
            NexusIconButton("Keyboard", false, onKeyboard) { iconKeyboard(it) }
            NexusIconButton("Pengaturan", false, onSettings) { iconSettings(it) }
            NexusIconButton("Tutup", false, onClose) { iconClose(it) }
        }
    }
}

@Composable
private fun SpeechBubble(message: String, state: NexusPresenceState) {
    var shown by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        shown = ""
        typing = message.isNotEmpty()
        for (i in 1..message.length) {
            shown = message.substring(0, i)
            delay(26)
        }
        typing = false
    }

    val border = if (state == NexusPresenceState.ERROR) ErrorRed else PandoraDim
    val textColor = if (state == NexusPresenceState.ERROR) ErrorRed else PandoraGreen
    val style = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp
    )

    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.BottomCenter) {
        if (message.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(BubbleBg)
                        .border(1.dp, border, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // teks penuh transparan menjaga ukuran balon stabil selama mengetik
                    Text(message, style = style, color = Color.Transparent, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Text(
                        shown + if (typing) "▌" else "",
                        style = style, color = textColor, maxLines = 5, overflow = TextOverflow.Ellipsis
                    )
                }
                Canvas(Modifier.size(width = 16.dp, height = 9.dp)) {
                    val p = Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                    }
                    drawPath(p, BubbleBg)
                }
            }
        }
    }
}

@Composable
private fun NexusIconButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: DrawScope.(Color) -> Unit
) {
    val bg by animateColorAsState(if (active) PandoraGreen else ButtonBg, label = "btnBg")
    val fg = if (active) PandoraDark else PandoraGreen
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (active) PandoraGlow else PandoraDim, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) { icon(fg) }
    }
}

// ---------- Ikon (ruang 24x24) ----------

private val IconStroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.iconMic(c: Color) = scale(size.minDimension / 24f, Offset.Zero) {
    drawRoundRect(c, Offset(9f, 3f), Size(6f, 11f), CornerRadius(3f), style = IconStroke)
    drawArc(c, 0f, 180f, false, Offset(6f, 7f), Size(12f, 10f), style = IconStroke)
    drawLine(c, Offset(12f, 17f), Offset(12f, 21f), 2f, StrokeCap.Round)
    drawLine(c, Offset(8.5f, 21f), Offset(15.5f, 21f), 2f, StrokeCap.Round)
}

private fun DrawScope.iconKeyboard(c: Color) = scale(size.minDimension / 24f, Offset.Zero) {
    drawRoundRect(c, Offset(2.5f, 6f), Size(19f, 12f), CornerRadius(2.5f), style = IconStroke)
    for (x in floatArrayOf(6.5f, 9.8f, 13.2f, 16.5f)) drawCircle(c, 0.9f, Offset(x, 10f))
    drawLine(c, Offset(8f, 14.2f), Offset(16f, 14.2f), 1.8f, StrokeCap.Round)
}

private fun DrawScope.iconSettings(c: Color) = scale(size.minDimension / 24f, Offset.Zero) {
    drawCircle(c, 5.5f, Offset(12f, 12f), style = IconStroke)
    drawCircle(c, 2f, Offset(12f, 12f))
    for (i in 0 until 8) {
        val a = (i * PI / 4).toFloat()
        drawLine(
            c,
            Offset(12f + 7.2f * cos(a), 12f + 7.2f * sin(a)),
            Offset(12f + 10f * cos(a), 12f + 10f * sin(a)),
            2.6f, StrokeCap.Round
        )
    }
}

private fun DrawScope.iconClose(c: Color) = scale(size.minDimension / 24f, Offset.Zero) {
    drawLine(c, Offset(6f, 6f), Offset(18f, 18f), 2.4f, StrokeCap.Round)
    drawLine(c, Offset(18f, 6f), Offset(6f, 18f), 2.4f, StrokeCap.Round)
}