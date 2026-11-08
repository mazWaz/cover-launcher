package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.designsystem.cutoutSides
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus

/**
 * The cover while it dozes: Home's status row, clock and date on black, what is playing, no dock. The whole block moves a few dp every
 * minute so no pixel stays lit in one place (OLED burn-in).
 */
@Composable
fun AodScreen(state: LauncherState) {
  val minute = state.time.takeLast(2).toIntOrNull() ?: 0
  Box(Modifier.fillMaxSize().background(Color.Black).cutoutSides().padding(horizontal = 28.dp, vertical = 24.dp)) {
    // half bright: full white read as too bright on the dozing cover (2026-11-08)
    Column(Modifier.offset(x = (minute % 5 - 2) * 3.dp, y = (minute / 5 % 5 - 2) * 3.dp).alpha(0.5f)) {
      StatusRow(state)
      Spacer(Modifier.weight(1f))
      Clock(state.time)
      DateRow(state)
      state.nowPlaying?.takeIf { it.playing }?.let { PlayingRow(it) }
      Spacer(Modifier.weight(1f))
    }
  }
}

/** Title · artist; display only, the AOD takes no touches. */
@Composable
private fun PlayingRow(playing: NowPlaying) {
  Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(R.drawable.ic_play), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
    Spacer(Modifier.width(8.dp))
    Text(
      listOf(playing.title, playing.artist).filter(String::isNotBlank).joinToString(" · "),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
  }
}

@Preview(widthDp = 352, heightDp = 339)
@Composable
private fun AodPreview() = NothingTheme {
  AodScreen(
    LauncherState(
      time = "22:28", date = "WED 04 NOV", status = SystemStatus(batteryPercent = 72, wifiOn = true),
      notifications = listOf(LauncherNotification("a", "wa", "WhatsApp", "Dimas", "Nanti malam jadi?", 0, true)),
      nowPlaying = NowPlaying("m", "Track title", "Artist", playing = true),
    ),
  )
}
