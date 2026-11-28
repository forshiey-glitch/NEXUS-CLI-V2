package com.nexus.cli.nexusdrawer

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nexus.nexus_cli_v2.ui.theme.PandoraDark
import com.nexus.nexus_cli_v2.ui.theme.PandoraGreen

/**
 * Overlay drawer kiri. [content] = UI lama (Dashboard + Terminal) dan TIDAK diubah.
 * Drawer hanya terbuka lewat swipe dari tepi kiri (zona 28dp) atau viewModel.open().
 * Saat tertutup, gesture drawer dimatikan supaya swipe ViewPager2 tidak terganggu.
 */
@Composable
fun NexusShell(
    viewModel: NexusDrawerViewModel,
    onMicClick: () -> Unit,
    onKeyboardClick: () -> Unit,
    onSettingsClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val message by viewModel.message.collectAsState()
    val isOpen by viewModel.isOpen.collectAsState()
    val micOn by viewModel.isMicOn.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // ViewModel -> drawer
    LaunchedEffect(isOpen) {
        if (isOpen) drawerState.open() else drawerState.close()
    }
    // drawer -> ViewModel (swipe tutup, scrim, back)
    LaunchedEffect(drawerState.currentValue) {
        viewModel.setOpen(drawerState.currentValue == DrawerValue.Open)
    }

    // Animasi robot hanya jalan saat drawer terlihat.
    val visible = drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed

    val scheme = darkColorScheme(
        primary = PandoraGreen,
        background = PandoraDark,
        surface = PandoraDark,
        onSurface = PandoraGreen
    )

    MaterialTheme(colorScheme = scheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.currentValue != DrawerValue.Closed,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(320.dp),
                    drawerShape = RectangleShape,
                    drawerContainerColor = PandoraDark,
                    drawerContentColor = PandoraGreen,
                    drawerTonalElevation = 0.dp
                ) {
                    NexusDrawer(
                        state = state,
                        amplitude = amplitude,
                        statusText = statusText,
                        message = message,
                        micActive = micOn,
                        active = visible,
                        onMic = onMicClick,
                        onKeyboard = onKeyboardClick,
                        onSettings = onSettingsClick,
                        onClose = { viewModel.close() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                EdgeSwipeZone(
                    onOpen = { viewModel.open() },
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
    }
}

@Composable
private fun EdgeSwipeZone(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val trigger = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .systemGestureExclusion()
            .pointerInput(trigger) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { total = 0f },
                    onDragCancel = { total = 0f }
                ) { change, dx ->
                    total += dx
                    if (total > trigger) {
                        change.consume()
                        onOpen()
                        total = 0f
                    }
                }
            }
    )
}