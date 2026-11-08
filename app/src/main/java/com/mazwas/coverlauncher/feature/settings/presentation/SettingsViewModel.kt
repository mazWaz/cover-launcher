package com.mazwas.coverlauncher.feature.settings.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.feature.cover.Grants
import com.mazwas.coverlauncher.feature.cover.SetupCheck
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.moved
import com.mazwas.coverlauncher.feature.launcher.domain.tileOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
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
  val popups: Boolean = false,
  val aod: Boolean = false,
  val density: Int? = null, // the cover's dpi; null = its own 320
  val fontScale: Float = 1f,
  val animationScale: Float = 1f,
  val importing: Boolean = false,
  val shizuku: Boolean = false,
  val notificationAccess: Boolean = false,
  val coverService: Boolean = false,
  val checks: List<SetupCheck> = emptyList(), // filled in off the main thread on every refresh
  val tiles: List<QuickToggle> = QuickToggle.entries, // every tile, panel order
  val hiddenTiles: Set<String> = emptySet(),
)

sealed interface SettingsAction {
  data object PickWallpaper : SettingsAction
  data class WallpaperPicked(val uri: Uri) : SettingsAction
  data object ResetWallpaper : SettingsAction
  data class SetDotFilter(val on: Boolean) : SettingsAction
  data class SetIconStyle(val style: IconStyle) : SettingsAction
  data class SetPopups(val on: Boolean) : SettingsAction
  data class SetAod(val on: Boolean) : SettingsAction
  data class SetDensity(val dpi: Int?) : SettingsAction
  data class SetFontScale(val scale: Float) : SettingsAction
  data class SetAnimationScale(val scale: Float) : SettingsAction
  data class MoveTile(val tile: QuickToggle, val by: Int) : SettingsAction
  data class ToggleTile(val tile: QuickToggle) : SettingsAction
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
  private val notifications: NotificationStore,
  private val grants: Grants,
  private val system: SystemStatusSource,
) : ViewModel() {
  private data class Local(
    val importing: Boolean = false,
    val shizuku: Boolean,
    val coverService: Boolean,
    val fontScale: Float,
    val animationScale: Float,
    val checks: List<SetupCheck> = emptyList(),
  )

  private val local = MutableStateFlow(read(importing = false))
  private val eventChannel = Channel<SettingsEvent>()
  val events = eventChannel.receiveAsFlow()

  val state: StateFlow<SettingsState> = combine(settings.settings, notifications.connected, local) { s, access, l ->
    SettingsState(
      hasWallpaper = s.wallpaperVersion != 0L, dotFilter = s.dotFilter, iconStyle = s.iconStyle, popups = s.popups,
      aod = s.aod, density = s.density, fontScale = l.fontScale, animationScale = l.animationScale,
      importing = l.importing, shizuku = l.shizuku, notificationAccess = access, coverService = l.coverService,
      checks = l.checks, tiles = tileOrder(s.tiles), hiddenTiles = s.hiddenTiles,
    )
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
      // without Shizuku Samsung's pop-ups would stay as they are and both would show: the screen hides the switch then
      is SettingsAction.SetPopups -> if (Shell.ready()) viewModelScope.launch {
        grants.samsungPopups(!action.on)
        settings.setPopups(action.on)
      }
      is SettingsAction.SetAod -> viewModelScope.launch { settings.setAod(action.on) }
      // CoverDisplay applies it (through the shell) for every app without its own
      is SettingsAction.SetDensity -> viewModelScope.launch { settings.setDensity(action.dpi) }
      is SettingsAction.SetFontScale -> {
        system.setFontScale(action.scale)
        local.update { it.copy(fontScale = action.scale) }
      }
      is SettingsAction.SetAnimationScale -> {
        system.setAnimationScale(action.scale)
        local.update { it.copy(animationScale = action.scale) }
      }
      is SettingsAction.MoveTile -> viewModelScope.launch {
        settings.setTileOrder(state.value.tiles.moved(action.tile, action.by).map { it.name })
      }
      is SettingsAction.ToggleTile -> viewModelScope.launch {
        settings.setTileHidden(action.tile.name, action.tile.name !in state.value.hiddenTiles)
      }
      SettingsAction.FixPermissions -> viewModelScope.launch {
        grants.fix(settings.settings.first())
        delay(1_000) // the shell commands run on their own threads; re-read once they've had time to land
        onAction(SettingsAction.Refresh)
      }
      SettingsAction.Refresh -> {
        local.update { read(it.importing).copy(checks = it.checks) }
        viewModelScope.launch {
          val checks = withContext(Dispatchers.IO) { grants.checks(notifications.connected.value, settings.settings.first()) }
          local.update { it.copy(checks = checks) }
        }
      }
      SettingsAction.Back -> send(SettingsEvent.NavigateBack)
    }
  }

  private fun read(importing: Boolean) = Local(
    importing, Shell.ready(), grants.coverServiceEnabled(), system.fontScale(), system.animationScale(),
  )

  private fun send(event: SettingsEvent) {
    viewModelScope.launch { eventChannel.send(event) }
  }
}
