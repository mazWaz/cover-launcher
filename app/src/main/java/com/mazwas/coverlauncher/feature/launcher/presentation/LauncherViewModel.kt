package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.coverlauncher.core.system.Clock
import com.mazwas.coverlauncher.feature.launcher.data.AppsSource
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.DOCK_SIZE
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.filterApps
import com.mazwas.coverlauncher.feature.launcher.domain.resolveDock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class LauncherViewModel(
  apps: AppsSource,
  private val settings: SettingsStore,
  private val notifications: NotificationStore,
  private val system: SystemStatusSource,
  private val clock: Clock,
) : ViewModel() {
  private data class Ui(val query: String = "", val overlay: Overlay = Overlay.None, val brightness: CoverBrightness? = null)

  private val ui = MutableStateFlow(Ui())
  private val eventChannel = Channel<LauncherEvent>()
  val events = eventChannel.receiveAsFlow()

  private val alerts = combine(system.status, notifications.notifications, notifications.connected, ::Triple)

  val state: StateFlow<LauncherState> = combine(apps.apps, settings.settings, ui, clock.ticks, alerts) { all, s, u, now, alert ->
    val (status, notes, access) = alert
    val shown = filterApps(all, u.query)
    LauncherState(
      time = now.format(if (clock.is24Hour()) H24 else H12),
      date = now.format(DATE).uppercase(),
      now = System.currentTimeMillis(),
      status = status,
      dock = resolveDock(s.dock, all),
      apps = shown,
      appCount = shown.size,
      query = u.query,
      overlay = u.overlay,
      notifications = notes,
      notificationAccess = access,
      iconStyle = s.iconStyle,
      wallpaper = if (s.wallpaperVersion == 0L) null
      else Wallpaper(settings.wallpaperFile.path, s.wallpaperVersion, s.dotFilter),
      coverBrightness = u.brightness,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState())

  fun onAction(action: LauncherAction) {
    when (action) {
      LauncherAction.OpenDrawer -> ui.update { it.copy(overlay = Overlay.Drawer) }
      LauncherAction.OpenPanel -> {
        ui.update { it.copy(overlay = Overlay.Panel) }
        viewModelScope.launch(Dispatchers.IO) { system.coverBrightness()?.let { b -> ui.update { it.copy(brightness = b) } } }
      }
      LauncherAction.Close -> ui.value = Ui()
      LauncherAction.OpenSettings -> send(LauncherEvent.NavigateSettings)
      is LauncherAction.Search -> ui.update { it.copy(query = action.query) }
      LauncherAction.LaunchFirst -> state.value.apps.firstOrNull()?.let(::launch)
      is LauncherAction.Launch -> launch(action.app)
      is LauncherAction.TogglePin -> togglePin(action.app)
      is LauncherAction.AppInfo -> send(LauncherEvent.Open(OpenTarget.Info(action.app.packageName)))
      is LauncherAction.Uninstall -> send(LauncherEvent.Open(OpenTarget.Uninstall(action.app.packageName)))
      is LauncherAction.OpenNotification -> send(LauncherEvent.Open(OpenTarget.Notification(action.key)))
      is LauncherAction.NotificationAction -> send(LauncherEvent.Open(OpenTarget.NotificationAction(action.key, action.index)))
      is LauncherAction.Dismiss -> notifications.dismiss(action.key)
      LauncherAction.ClearAll -> notifications.clearAll()
      is LauncherAction.Toggle -> with(state.value.status) {
        if (action.toggle == QuickToggle.Sound) system.setSound(sound.next)
        else system.toggle(action.toggle, !isOn(action.toggle))
      }
      is LauncherAction.ToggleSettings -> send(LauncherEvent.Open(OpenTarget.Settings(action.toggle)))
      is LauncherAction.SetCoverBrightness -> {
        system.setCoverBrightness(action.level)
        ui.update { it.copy(brightness = CoverBrightness(action.level, auto = false)) }
      }
      LauncherAction.CoverBrightnessAuto -> {
        system.setCoverBrightnessAuto()
        ui.update { it.copy(brightness = it.brightness?.copy(auto = true)) }
      }
    }
  }

  private fun launch(app: LaunchableApp) = send(LauncherEvent.Open(OpenTarget.App(app.packageName, app.className)))

  /** Pinning to a full dock drops its first app. */
  private fun togglePin(app: LaunchableApp) {
    val dock = state.value.dock.map { it.key }
    val next = if (app.key in dock) dock - app.key else (dock + app.key).takeLast(DOCK_SIZE)
    viewModelScope.launch { settings.setDock(next) }
  }

  private fun send(event: LauncherEvent) {
    viewModelScope.launch { eventChannel.send(event) }
  }

  private companion object {
    val H24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val H12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd MMM")
  }
}
