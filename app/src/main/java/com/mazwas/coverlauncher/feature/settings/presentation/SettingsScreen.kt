package com.mazwas.coverlauncher.feature.settings.presentation

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.Label
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.designsystem.PillButton
import com.mazwas.coverlauncher.core.designsystem.SegmentedControl
import com.mazwas.coverlauncher.core.designsystem.status
import com.mazwas.coverlauncher.core.presentation.ObserveAsEvents
import com.mazwas.coverlauncher.core.system.unlockThen
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.presentation.pressable
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsRoot(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
    uri?.let { viewModel.onAction(SettingsAction.WallpaperPicked(it)) }
  }
  ObserveAsEvents(viewModel.events) { event ->
    when (event) {
      // the launcher may be showing over the lock screen; the picker can't
      SettingsEvent.LaunchPicker -> activity?.unlockThen { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
      SettingsEvent.NavigateBack -> onBack()
    }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(SettingsAction.Refresh) }
  SettingsScreen(state, viewModel::onAction)
}

@Composable
fun SettingsScreen(state: SettingsState, onAction: (SettingsAction) -> Unit) {
  val cutout = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .verticalScroll(rememberScrollState())
      .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = cutout + 16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(40.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
          .pressable(onClick = { onAction(SettingsAction.Back) }),
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_chevron_left), "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
      }
      Spacer(Modifier.width(14.dp))
      Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }

    Section("wallpaper")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PillButton(if (state.importing) "importing…" else "choose image", onClick = { onAction(SettingsAction.PickWallpaper) }, primary = true)
      if (state.hasWallpaper) PillButton("reset", onClick = { onAction(SettingsAction.ResetWallpaper) })
    }
    Setting("dot filter") {
      SegmentedControl(listOf(true to "on", false to "off"), state.dotFilter, { onAction(SettingsAction.SetDotFilter(it)) })
    }

    Section("home")
    Setting("icons") {
      SegmentedControl(listOf(IconStyle.Mono to "mono", IconStyle.Color to "color"), state.iconStyle, { onAction(SettingsAction.SetIconStyle(it)) })
    }

    Section("system")
    Setting("shizuku") { StatusDot(state.shizuku, if (state.shizuku) "ready" else "off") }
    Setting("notifications") { StatusDot(state.notificationAccess, if (state.notificationAccess) "on" else "off") }
    Setting("cover service") { StatusDot(state.coverService, if (state.coverService) "on" else "off") }
    if (!state.shizuku || !state.notificationAccess || !state.coverService) {
      Spacer(Modifier.height(8.dp))
      PillButton("fix permissions", onClick = { onAction(SettingsAction.FixPermissions) })
    }
  }
}

@Composable
private fun Section(title: String) {
  Spacer(Modifier.height(18.dp))
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  Spacer(Modifier.height(12.dp))
  Label(title, small = true, color = MaterialTheme.status.disabled)
  Spacer(Modifier.height(10.dp))
}

@Composable
private fun Setting(name: String, control: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Label(name, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    control()
  }
}

@Composable
private fun StatusDot(ok: Boolean, text: String) {
  val color = if (ok) MaterialTheme.status.success else MaterialTheme.colorScheme.error
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(8.dp))
    Label(text, color = if (ok) MaterialTheme.colorScheme.onSurface else color)
  }
}

@Preview(widthDp = 352, heightDp = 339, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun SettingsPreview() = NothingTheme {
  SettingsScreen(SettingsState(hasWallpaper = true, shizuku = true, notificationAccess = false, coverService = true)) {}
}
