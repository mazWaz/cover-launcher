package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.MediaCommand
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.Label
import com.mazwas.coverlauncher.core.designsystem.PillButton
import com.mazwas.coverlauncher.core.designsystem.RoundToggle
import com.mazwas.coverlauncher.core.designsystem.status
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.SoundMode
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus
import com.mazwas.coverlauncher.feature.launcher.domain.relativeAge

/** Quick toggles and notifications. Swipe up (or Back) to close; swipe a notification sideways to dismiss it. */
@Composable
fun NotificationPanel(state: LauncherState, onAction: (LauncherAction) -> Unit, modifier: Modifier = Modifier) {
  val cutout = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
  val pullToClose = rememberPullToClose(down = false) { onAction(LauncherAction.Close) }
  Column(
    modifier
      .fillMaxSize()
      .background(Color.Black)
      .verticalSwipe(onUp = { onAction(LauncherAction.Close) })
      .padding(top = 14.dp),
  ) {
    QuickToggles(state, onAction)
    state.coverBrightness?.let { BrightnessRow(it, onAction) }
    state.nowPlaying?.let { MediaRow(it, onAction) }
    Row(
      Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Label("alerts · ${state.notifications.size}", small = true, modifier = Modifier.weight(1f))
      if (state.notifications.any { it.clearable }) {
        Label(
          "clear all", small = true, color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onAction(LauncherAction.ClearAll) }.padding(8.dp),
        )
      }
    }
    when {
      !state.notificationAccess -> Empty("notification access off", Modifier.weight(1f)) {
        PillButton("open settings", onClick = { onAction(LauncherAction.OpenSettings) })
      }
      state.notifications.isEmpty() -> Empty("no alerts", Modifier.weight(1f))
      else -> LazyColumn(
        Modifier.weight(1f).nestedScroll(pullToClose),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = cutout + 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items(state.notifications, key = { it.key }) {
          NotificationRow(it, state.now, onAction, Modifier.animateItem())
        }
      }
    }
  }
}

private class Tile(@DrawableRes val icon: Int, val label: String, val on: Boolean, val tap: LauncherAction, val hold: LauncherAction?)

private fun tiles(s: SystemStatus): List<Tile> = QuickToggle.entries.map { t ->
  val (icon, label) = when (t) {
    QuickToggle.Wifi -> (if (s.wifiOn) R.drawable.ic_wifi else R.drawable.ic_wifi_off) to "wi-fi"
    QuickToggle.Bluetooth -> R.drawable.ic_bluetooth to "bt"
    QuickToggle.Data -> R.drawable.ic_arrow_down_up to "data"
    QuickToggle.Torch -> R.drawable.ic_flashlight to "torch"
    QuickToggle.Dnd -> R.drawable.ic_moon to "dnd"
    QuickToggle.Airplane -> R.drawable.ic_plane to "flight"
    QuickToggle.Sound -> when (s.sound) {
      SoundMode.Ring -> R.drawable.ic_bell to "sound"
      SoundMode.Vibrate -> R.drawable.ic_vibrate to "vibrate"
      SoundMode.Silent -> R.drawable.ic_bell_off to "mute"
    }
    QuickToggle.Location -> R.drawable.ic_map_pin to "location"
    QuickToggle.Saver -> R.drawable.ic_leaf to "saver"
  }
  Tile(icon, label, s.isOn(t), LauncherAction.Toggle(t), LauncherAction.ToggleSettings(t).takeIf { t != QuickToggle.Torch })
} + Tile(R.drawable.ic_settings, "setup", false, LauncherAction.OpenSettings, null)

/** Pages of five; swipe sideways for more. Tap toggles, long-press opens the system popup / settings page. */
@Composable
private fun QuickToggles(state: LauncherState, onAction: (LauncherAction) -> Unit) {
  val pages = tiles(state.status).chunked(TILES_PER_PAGE)
  val pager = rememberPagerState { pages.size }
  HorizontalPager(pager, Modifier.fillMaxWidth()) { page ->
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      pages[page].forEach { tile ->
        RoundToggle(
          tile.icon, tile.label, tile.on, onClick = { onAction(tile.tap) },
          onLongClick = tile.hold?.let { hold -> { onAction(hold) } },
        )
      }
    }
  }
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
    repeat(pages.size) {
      val color = if (it == pager.currentPage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
      Box(Modifier.size(5.dp).background(color, CircleShape))
    }
  }
}

private const val TILES_PER_PAGE = 5

/** The top media session: title, artist and previous / play-pause / next. */
@Composable
private fun MediaRow(playing: NowPlaying, onAction: (LauncherAction) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(
      Modifier
        .weight(1f)
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClickLabel = "Open app") { onAction(LauncherAction.OpenNowPlaying) }
        .padding(vertical = 4.dp),
    ) {
      Text(playing.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (playing.artist.isNotBlank()) {
        Text(
          playing.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
      }
    }
    MediaButton(R.drawable.ic_skip_back, "Previous") { onAction(LauncherAction.Media(MediaCommand.Previous)) }
    MediaButton(if (playing.playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing.playing) "Pause" else "Play") {
      onAction(LauncherAction.Media(MediaCommand.PlayPause))
    }
    MediaButton(R.drawable.ic_skip_forward, "Next") { onAction(LauncherAction.Media(MediaCommand.Next)) }
  }
}

@Composable
private fun MediaButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
  Box(Modifier.size(44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(painterResource(icon), label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
  }
}

/** Cover brightness. Dragging sets a manual level, written once on release; AUTO hands it back to Samsung. */
@Composable
private fun BrightnessRow(brightness: CoverBrightness, onAction: (LauncherAction) -> Unit) {
  var level by remember(brightness.level) { mutableFloatStateOf(brightness.level.toFloat()) }
  Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(R.drawable.ic_sun), "Cover brightness", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    Slider(
      value = level,
      onValueChange = { level = it },
      onValueChangeFinished = { onAction(LauncherAction.SetCoverBrightness(level.roundToInt())) },
      valueRange = 1f..255f,
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
    )
    Label(
      "auto", small = true,
      color = if (brightness.auto) MaterialTheme.colorScheme.onSurface else MaterialTheme.status.disabled,
      modifier = Modifier
        .minimumInteractiveComponentSize()
        .clip(RoundedCornerShape(50))
        .clickable { onAction(LauncherAction.CoverBrightnessAuto) }
        .padding(8.dp),
    )
  }
}

@Composable
private fun NotificationRow(note: LauncherNotification, now: Long, onAction: (LauncherAction) -> Unit, modifier: Modifier) {
  val shape = RoundedCornerShape(14.dp)
  SwipeToDismissBox(
    state = rememberSwipeToDismissBoxState(),
    backgroundContent = {},
    modifier = modifier,
    gesturesEnabled = note.clearable,
    onDismiss = { onAction(LauncherAction.Dismiss(note.key)) },
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable { onAction(LauncherAction.OpenNotification(note.key)) }
        .padding(12.dp),
    ) {
      NotificationIcon(note.key, 16.dp, MaterialTheme.colorScheme.onSurface, Modifier.padding(top = 1.dp))
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Row {
          Label(note.appLabel, small = true, modifier = Modifier.weight(1f))
          Label(relativeAge(note.postedAt, now), small = true, color = MaterialTheme.status.disabled)
        }
        Spacer(Modifier.height(2.dp))
        if (note.title.isNotBlank()) {
          Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note.text.isNotBlank()) {
          Text(
            note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
          )
        }
        if (note.actions.isNotEmpty()) {
          FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            note.actions.forEachIndexed { i, title ->
              // icon-only buttons have no title: skip them, the index still points at the app's own action
              if (title.isNotBlank()) PillButton(title, onClick = { onAction(LauncherAction.NotificationAction(note.key, i)) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Empty(text: String, modifier: Modifier, action: (@Composable () -> Unit)? = null) {
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Label(text, color = MaterialTheme.status.disabled)
      action?.invoke()
    }
  }
}
