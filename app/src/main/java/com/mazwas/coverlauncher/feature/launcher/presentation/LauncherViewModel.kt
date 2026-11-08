package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.coverlauncher.core.system.Clock
import com.mazwas.coverlauncher.feature.launcher.data.AppsSource
import com.mazwas.coverlauncher.feature.launcher.data.MediaSource
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
import com.mazwas.coverlauncher.feature.launcher.data.VoiceInput
import com.mazwas.coverlauncher.feature.launcher.domain.APP_DENSITIES
import com.mazwas.coverlauncher.feature.launcher.domain.APP_ROTATIONS
import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.after
import com.mazwas.coverlauncher.feature.launcher.domain.tileOrder
import com.mazwas.coverlauncher.feature.launcher.domain.DOCK_SIZE
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.filterApps
import com.mazwas.coverlauncher.feature.launcher.domain.listedNotifications
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

class LauncherViewModel(
  apps: AppsSource,
  private val settings: SettingsStore,
  private val notifications: NotificationStore,
  private val system: SystemStatusSource,
  private val clock: Clock,
  private val media: MediaSource,
  private val voice: VoiceInput,
) : ViewModel() {
  private data class Ui(
    val query: String = "",
    val overlay: Overlay = Overlay.None,
    val brightness: CoverBrightness? = null,
    val replyKey: String? = null,
    val replyText: String = "",
    val listening: Boolean = false,
  )
  private data class Live(val status: SystemStatus, val notes: List<LauncherNotification>, val access: Boolean, val playing: NowPlaying?)

  private val ui = MutableStateFlow(Ui())
  private val eventChannel = Channel<LauncherEvent>()
  val events = eventChannel.receiveAsFlow()

  private val live = combine(system.status, notifications.notifications, notifications.connected, media.nowPlaying, ::Live)

  val state: StateFlow<LauncherState> = combine(apps.apps, settings.settings, ui, clock.ticks, live) { all, s, u, now, alert ->
    val (status, notes, access, playing) = alert
    val shown = filterApps(all, u.query)
    LauncherState(
      time = clock.time(now),
      date = clock.date(now),
      now = System.currentTimeMillis(),
      status = status,
      dock = resolveDock(s.dock, all),
      apps = shown,
      appCount = shown.size,
      query = u.query,
      overlay = u.overlay,
      notifications = listedNotifications(notes, playing),
      notificationAccess = access,
      iconStyle = s.iconStyle,
      wallpaper = if (s.wallpaperVersion == 0L) null
      else Wallpaper(settings.wallpaperFile.path, s.wallpaperVersion, s.dotFilter),
      coverBrightness = u.brightness,
      nowPlaying = playing,
      appRotation = s.appRotation,
      appDensity = s.appDensity,
      restricted = s.restricted,
      banking = s.banking,
      replying = u.replyKey?.let { key -> notes.firstOrNull { it.key == key } },
      replyText = u.replyText,
      listening = u.listening,
      tiles = tileOrder(s.tiles).filterNot { it.name in s.hiddenTiles },
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState())


  fun onAction(action: LauncherAction) {
    when (action) {
      LauncherAction.OpenDrawer -> ui.update { it.copy(overlay = Overlay.Drawer) }
      LauncherAction.OpenPanel -> {
        ui.update { it.copy(overlay = Overlay.Panel) }
        viewModelScope.launch(Dispatchers.IO) { system.coverBrightness()?.let { b -> ui.update { it.copy(brightness = b) } } }
      }
      LauncherAction.Close -> {
        voice.stop()
        ui.value = Ui()
      }
      is LauncherAction.StartReply -> ui.update { it.copy(replyKey = action.key, replyText = "") }
      is LauncherAction.ReplyText -> ui.update { it.copy(replyText = action.text) }
      LauncherAction.SendReply -> sendReply()
      LauncherAction.Dictate -> dictate()
      LauncherAction.CloseReply -> closeReply()
      LauncherAction.OpenSettings -> send(LauncherEvent.NavigateSettings)
      is LauncherAction.Search -> ui.update { it.copy(query = action.query) }
      LauncherAction.LaunchFirst -> state.value.apps.firstOrNull()?.let(::launch)
      is LauncherAction.Launch -> launch(action.app)
      is LauncherAction.TogglePin -> togglePin(action.app)
      is LauncherAction.CycleRotation -> {
        val pkg = action.app.packageName
        viewModelScope.launch { settings.setAppRotation(pkg, APP_ROTATIONS.after(state.value.appRotation[pkg])) }
      }
      is LauncherAction.ToggleRestricted -> {
        val pkg = action.app.packageName
        val on = pkg !in state.value.restricted
        system.setBackgroundRestricted(pkg, on)
        viewModelScope.launch { settings.setRestricted(pkg, on) }
      }
      is LauncherAction.ToggleBanking -> {
        val pkg = action.app.packageName
        viewModelScope.launch { settings.setBanking(pkg, pkg !in state.value.banking) }
      }
      is LauncherAction.CycleDensity -> {
        val pkg = action.app.packageName
        viewModelScope.launch { settings.setAppDensity(pkg, APP_DENSITIES.after(state.value.appDensity[pkg])) }
      }
      is LauncherAction.AppInfo -> send(LauncherEvent.Open(OpenTarget.Info(action.app.packageName)))
      is LauncherAction.Uninstall -> send(LauncherEvent.Open(OpenTarget.Uninstall(action.app.packageName)))
      is LauncherAction.OpenNotification -> send(LauncherEvent.Open(OpenTarget.Notification(action.key)))
      is LauncherAction.NotificationAction ->
        if (state.value.notifications.any { it.key == action.key && it.reply == action.index }) onAction(LauncherAction.StartReply(action.key))
        else send(LauncherEvent.Open(OpenTarget.NotificationAction(action.key, action.index)))
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
      is LauncherAction.Media -> media.command(action.command)
      // the app's launcher entry, opened like any app (on the cover through the shell)
      LauncherAction.OpenNowPlaying -> with(state.value) {
        nowPlaying?.let { p -> apps.firstOrNull { it.packageName == p.packageName } }?.let(::launch)
      }
      LauncherAction.CoverBrightnessAuto -> {
        system.setCoverBrightnessAuto()
        ui.update { it.copy(brightness = it.brightness?.copy(auto = true)) }
      }
    }
  }

  private fun sendReply() {
    val note = state.value.replying ?: return
    val text = ui.value.replyText.trim().ifEmpty { return }
    send(LauncherEvent.Open(OpenTarget.Reply(note.key, note.reply, text)))
    closeReply()
  }

  /** Again while listening: stop. Dictated words go after what is typed already. */
  private fun dictate() {
    if (ui.value.listening) return closeMic()
    val typed = ui.value.replyText.trimEnd()
    ui.update { it.copy(listening = true) }
    voice.listen(
      onText = { heard -> ui.update { it.copy(replyText = listOf(typed, heard).filter(String::isNotEmpty).joinToString(" ")) } },
      onEnd = { ui.update { it.copy(listening = false) } },
    )
  }

  private fun closeMic() {
    voice.stop()
    ui.update { it.copy(listening = false) }
  }

  private fun closeReply() {
    closeMic()
    ui.update { it.copy(replyKey = null, replyText = "") }
  }

  override fun onCleared() = voice.stop()

  private fun launch(app: LaunchableApp) =
    send(LauncherEvent.Open(OpenTarget.App(app.packageName, app.className, banking = app.packageName in state.value.banking)))

  /** Pinning to a full dock drops its first app. */
  private fun togglePin(app: LaunchableApp) {
    val dock = state.value.dock.map { it.key }
    val next = if (app.key in dock) dock - app.key else (dock + app.key).takeLast(DOCK_SIZE)
    viewModelScope.launch { settings.setDock(next) }
  }

  private fun send(event: LauncherEvent) {
    viewModelScope.launch { eventChannel.send(event) }
  }
}
