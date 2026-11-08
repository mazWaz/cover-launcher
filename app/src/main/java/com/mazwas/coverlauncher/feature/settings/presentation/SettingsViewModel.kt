package com.mazwas.coverlauncher.feature.settings.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.feature.cover.Grants
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
  val hasWallpaper: Boolean = false,
  val dotFilter: Boolean = true,
  val iconStyle: IconStyle = IconStyle.Mono,
  val importing: Boolean = false,
  val shizuku: Boolean = false,
  val notificationAccess: Boolean = false,
  val coverService: Boolean = false,
)

sealed interface SettingsAction {
  data object PickWallpaper : SettingsAction
  data class WallpaperPicked(val uri: Uri) : SettingsAction
  data object ResetWallpaper : SettingsAction
  data class SetDotFilter(val on: Boolean) : SettingsAction
  data class SetIconStyle(val style: IconStyle) : SettingsAction
  data object FixPermissions : SettingsAction
  data object Refresh : SettingsAction
  data object Back : SettingsAction
}

sealed interface SettingsEvent {
  data object LaunchPicker : SettingsEvent
  data object NavigateBack : SettingsEvent
}

class SettingsViewModel(
  private val settings: SettingsStore,
  notifications: NotificationStore,
  private val grants: Grants,
) : ViewModel() {
  private data class Local(val importing: Boolean = false, val shizuku: Boolean, val coverService: Boolean)

  private val local = MutableStateFlow(Local(shizuku = Shell.ready(), coverService = grants.coverServiceEnabled()))
  private val eventChannel = Channel<SettingsEvent>()
  val events = eventChannel.receiveAsFlow()

  val state: StateFlow<SettingsState> = combine(settings.settings, notifications.connected, local) { s, access, l ->
    SettingsState(s.wallpaperVersion != 0L, s.dotFilter, s.iconStyle, l.importing, l.shizuku, access, l.coverService)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

  fun onAction(action: SettingsAction) {
    when (action) {
      SettingsAction.PickWallpaper -> send(SettingsEvent.LaunchPicker)
      is SettingsAction.WallpaperPicked -> viewModelScope.launch {
        local.update { it.copy(importing = true) }
        runCatching { settings.importWallpaper(action.uri) }
        local.update { it.copy(importing = false) }
      }
      SettingsAction.ResetWallpaper -> viewModelScope.launch { settings.resetWallpaper() }
      is SettingsAction.SetDotFilter -> viewModelScope.launch { settings.setDotFilter(action.on) }
      is SettingsAction.SetIconStyle -> viewModelScope.launch { settings.setIconStyle(action.style) }
      SettingsAction.FixPermissions -> viewModelScope.launch {
        grants.apply()
        delay(1_000) // the shell commands run on their own threads; re-read once they've had time to land
        onAction(SettingsAction.Refresh)
      }
      SettingsAction.Refresh -> local.update { it.copy(shizuku = Shell.ready(), coverService = grants.coverServiceEnabled()) }
      SettingsAction.Back -> send(SettingsEvent.NavigateBack)
    }
  }

  private fun send(event: SettingsEvent) {
    viewModelScope.launch { eventChannel.send(event) }
  }
}
