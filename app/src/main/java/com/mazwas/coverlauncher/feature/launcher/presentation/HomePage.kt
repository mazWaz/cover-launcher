package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.Label
import com.mazwas.coverlauncher.core.designsystem.SegmentedBar
import com.mazwas.coverlauncher.core.designsystem.status

/** Clock, status and dock. Swipe up: apps. Swipe down: alerts. Long-press: settings. */
@Composable
fun HomePage(state: LauncherState, onAction: (LauncherAction) -> Unit, modifier: Modifier = Modifier) {
  val cutout = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
  Box(
    modifier
      .fillMaxSize()
      .verticalSwipe(onUp = { onAction(LauncherAction.OpenDrawer) }, onDown = { onAction(LauncherAction.OpenPanel) })
      .pointerInput(Unit) { detectTapGestures(onLongPress = { onAction(LauncherAction.OpenSettings) }) },
  ) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = cutout + 12.dp)) {
      StatusRow(state)
      Spacer(Modifier.weight(1f))
      Clock(state.time)
      DateRow(state)
      Spacer(Modifier.weight(1f))
      Dock(state, onAction)
    }
    // the strip left of the camera is free: use it for the gesture hint
    if (cutout > 0.dp) {
      Label(
        "↑ apps   ↓ alerts", small = true, color = MaterialTheme.status.disabled,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp).height(cutout).wrapContentHeight(),
      )
    }
  }
}

@Composable
private fun Clock(time: String) {
  val style = MaterialTheme.typography.displayLarge
  val color = MaterialTheme.colorScheme.primary
  val parts = time.split(':', limit = 2)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(parts[0], style = style, color = color)
    if (parts.size == 2) {
      // Doto's heavy colon clumps into crosses: draw two dots the size of the digits' dots
      Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { Box(Modifier.size(7.dp).background(color, CircleShape)) }
      }
      Text(parts[1], style = style, color = color)
    }
  }
}

@Composable
private fun StatusRow(state: LauncherState) {
  val status = state.status
  Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
    // one icon per app with alerts
    state.notifications.distinctBy { it.packageName }.take(5).forEach {
      NotificationIcon(it.key, 14.dp, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(end = 8.dp))
    }
    Spacer(Modifier.weight(1f))
    if (status.dndOn) StatusGlyph(R.drawable.ic_moon)
    if (status.bluetoothOn) StatusGlyph(R.drawable.ic_bluetooth)
    StatusGlyph(if (status.wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
    Label("${status.batteryPercent}%", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 6.dp))
    val color = when {
      status.charging -> MaterialTheme.status.success
      status.batteryPercent <= 15 -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.primary
    }
    SegmentedBar(status.batteryPercent / 100f, Modifier.width(30.dp), segments = 5, height = 8.dp, color = color)
  }
}

@Composable
private fun StatusGlyph(icon: Int) = Icon(
  painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(end = 8.dp).size(14.dp),
)

@Composable
private fun DateRow(state: LauncherState) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Label(state.date, color = MaterialTheme.colorScheme.onSurface)
    val count = state.notifications.size
    if (count > 0) {
      // the one red thing on the screen
      Box(Modifier.padding(start = 12.dp, end = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
      Label(if (count == 1) "1 alert" else "$count alerts")
    }
  }
}

@Composable
private fun Dock(state: LauncherState, onAction: (LauncherAction) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    state.dock.forEach { app ->
      AppIcon(app, state.iconStyle, 52.dp, Modifier.pressable(onClick = { onAction(LauncherAction.Launch(app)) }))
    }
    Box(
      Modifier
        .size(52.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        .pressable(onClick = { onAction(LauncherAction.OpenDrawer) }),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_layout_grid), "Apps", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
  }
}
