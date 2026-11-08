package com.mazwas.coverlauncher.feature.launcher.presentation

import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.MediaCommand
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus

enum class Overlay { None, Drawer, Panel }

/** A custom wallpaper file; [version] changes on every import so the image reloads. */
data class Wallpaper(val path: String, val version: Long, val dots: Boolean)

data class LauncherState(
  val time: String = "",
  val date: String = "",
  val now: Long = 0,
  val status: SystemStatus = SystemStatus(),
  val dock: List<LaunchableApp> = emptyList(),
  val apps: List<LaunchableApp> = emptyList(), // filtered by [query]
  val appCount: Int = 0,
  val query: String = "",
  val overlay: Overlay = Overlay.None,
  val notifications: List<LauncherNotification> = emptyList(),
  val notificationAccess: Boolean = true,
  val iconStyle: IconStyle = IconStyle.Mono,
  val wallpaper: Wallpaper? = null,
  val coverBrightness: CoverBrightness? = null, // read when the panel opens; null without Shizuku (no slider)
  val nowPlaying: NowPlaying? = null, // null: nothing playing, no player
)

sealed interface LauncherAction {
  data object OpenDrawer : LauncherAction
  data object OpenPanel : LauncherAction
  data object Close : LauncherAction
  data object OpenSettings : LauncherAction
  data class Search(val query: String) : LauncherAction
  data object LaunchFirst : LauncherAction
  data class Launch(val app: LaunchableApp) : LauncherAction
  data class TogglePin(val app: LaunchableApp) : LauncherAction
  data class AppInfo(val app: LaunchableApp) : LauncherAction
  data class Uninstall(val app: LaunchableApp) : LauncherAction
  data class OpenNotification(val key: String) : LauncherAction
  data class NotificationAction(val key: String, val index: Int) : LauncherAction
  data class Dismiss(val key: String) : LauncherAction
  data object ClearAll : LauncherAction
  data class Toggle(val toggle: QuickToggle) : LauncherAction
  data class ToggleSettings(val toggle: QuickToggle) : LauncherAction
  data class SetCoverBrightness(val level: Int) : LauncherAction
  data object CoverBrightnessAuto : LauncherAction
  data class Media(val command: MediaCommand) : LauncherAction
  data object OpenNowPlaying : LauncherAction
}

sealed interface LauncherEvent {
  data class Open(val target: OpenTarget) : LauncherEvent
  data object NavigateSettings : LauncherEvent
}
