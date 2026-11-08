package com.mazwas.coverlauncher.feature.launcher.presentation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.presentation.ObserveAsEvents
import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LauncherRoot(
  onOpen: (OpenTarget) -> Unit,
  onSettings: () -> Unit,
  reply: MutableStateFlow<String?>,
  viewModel: LauncherViewModel = koinViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  // the pop-up's Reply: taken once resumed, after the ON_START reset below has run
  val replyKey by reply.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
  LaunchedEffect(replyKey) {
    replyKey?.let { viewModel.onAction(LauncherAction.StartReply(it)) }
    reply.value = null
  }
  ObserveAsEvents(viewModel.events) { event ->
    when (event) {
      is LauncherEvent.Open -> onOpen(event.target)
      LauncherEvent.NavigateSettings -> onSettings()
    }
  }
  // left for an app or the screen went off: come back to the clock. Not after a blip such as Samsung's torch
  // screen (CoverService closes it at once), or tapping the torch tile would close the panel.
  var stoppedAt by remember { mutableLongStateOf(0L) }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stoppedAt = SystemClock.uptimeMillis() }
  LifecycleEventEffect(Lifecycle.Event.ON_START) {
    if (stoppedAt != 0L && SystemClock.uptimeMillis() - stoppedAt > 1_000) viewModel.onAction(LauncherAction.Close)
  }
  // we are the cover's home: Back only closes overlays
  BackHandler { viewModel.onAction(if (state.replying != null) LauncherAction.CloseReply else LauncherAction.Close) }
  LauncherScreen(state, viewModel::onAction)
}

@Composable
fun LauncherScreen(state: LauncherState, onAction: (LauncherAction) -> Unit) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    WallpaperLayer(state.wallpaper)
    HomePage(state, onAction)
    AnimatedVisibility(
      visible = state.overlay == Overlay.Drawer,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(150)),
    ) { AppDrawer(state, onAction) }
    AnimatedVisibility(
      visible = state.overlay == Overlay.Panel,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(150)),
    ) { NotificationPanel(state, onAction) }
    AnimatedVisibility(
      visible = state.replying != null,
      enter = fadeIn(tween(200)),
      exit = fadeOut(tween(150)),
    ) {
      // fading out after it closed: keep showing the last one
      var last by remember { mutableStateOf(state.replying) }
      state.replying?.let { last = it }
      last?.let { ReplySheet(it, state.replyText, state.listening, onAction) }
    }
  }
}

private val previewState = LauncherState(
  time = "22:28",
  date = "WED 04 NOV",
  now = 1_000_000,
  status = SystemStatus(batteryPercent = 72, wifiOn = true, bluetoothOn = true),
  coverBrightness = CoverBrightness(100, auto = true),
  nowPlaying = NowPlaying("app.music", "Track title", "Artist", playing = true),
  dock = listOf("Camera", "Chrome", "Gallery", "Messages").map { LaunchableApp(it, it, it) },
  apps = listOf("Calculator", "Calendar", "Camera", "Chrome", "Clock", "Gallery", "Maps", "Messages", "Phone", "Spotify")
    .map { LaunchableApp(it, it, it) },
  appCount = 10,
  notifications = listOf(
    LauncherNotification("a", "wa", "WhatsApp", "Dimas", "Nanti malam jadi?", 940_000, true, listOf("Reply", "Mark as read")),
    LauncherNotification("b", "gm", "Gmail", "Your order shipped", "Arrives Friday", 400_000, true),
  ),
)

@Preview(widthDp = 352, heightDp = 339)
@Composable
private fun HomePreview() = NothingTheme { LauncherScreen(previewState) {} }

@Preview(widthDp = 352, heightDp = 339)
@Composable
private fun DrawerPreview() = NothingTheme { LauncherScreen(previewState.copy(overlay = Overlay.Drawer)) {} }

@Preview(widthDp = 352, heightDp = 339)
@Composable
private fun PanelPreview() = NothingTheme { LauncherScreen(previewState.copy(overlay = Overlay.Panel)) {} }
