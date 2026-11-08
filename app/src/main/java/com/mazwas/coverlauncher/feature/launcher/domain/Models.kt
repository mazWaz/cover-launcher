package com.mazwas.coverlauncher.feature.launcher.domain

data class LaunchableApp(val packageName: String, val className: String, val label: String) {
  val key: String get() = "$packageName/$className"
}

data class LauncherNotification(
  val key: String,
  val packageName: String,
  val appLabel: String,
  val title: String,
  val text: String,
  val postedAt: Long,
  val clearable: Boolean,
  val actions: List<String> = emptyList(), // button titles; the index is the app's own action index
)

/**
 * Up to three of a notification's buttons. None for calls: on this phone no WhatsApp call answered from another app's
 * listener (OpenCover) ever went active, so tapping the call opens the app's own call screen instead.
 */
fun notificationActions(category: String?, titles: List<String>): List<String> =
  if (category == "call") emptyList() else titles.take(3)

enum class SoundMode {
  Ring, Vibrate, Silent;

  /** Tapping the sound tile cycles ring → vibrate → silent, like One UI's. */
  val next: SoundMode get() = entries[(ordinal + 1) % entries.size]
}

data class SystemStatus(
  val batteryPercent: Int = 0,
  val charging: Boolean = false,
  val wifiOn: Boolean = false,
  val bluetoothOn: Boolean = false,
  val dataOn: Boolean = false,
  val torchOn: Boolean = false,
  val dndOn: Boolean = false,
  val airplaneOn: Boolean = false,
  val sound: SoundMode = SoundMode.Ring,
  val locationOn: Boolean = false,
  val saverOn: Boolean = false,
) {
  fun isOn(toggle: QuickToggle): Boolean = when (toggle) {
    QuickToggle.Wifi -> wifiOn
    QuickToggle.Bluetooth -> bluetoothOn
    QuickToggle.Data -> dataOn
    QuickToggle.Torch -> torchOn
    QuickToggle.Dnd -> dndOn
    QuickToggle.Airplane -> airplaneOn
    QuickToggle.Sound -> sound != SoundMode.Silent
    QuickToggle.Location -> locationOn
    QuickToggle.Saver -> saverOn
  }
}

/** Quick settings tiles in panel order; the panel pages them by five. */
enum class QuickToggle { Wifi, Bluetooth, Data, Torch, Dnd, Airplane, Sound, Location, Saver }

/** Something to open on the cover. */
sealed interface OpenTarget {
  data class App(val packageName: String, val className: String) : OpenTarget
  data class Info(val packageName: String) : OpenTarget
  data class Uninstall(val packageName: String) : OpenTarget
  data class Notification(val key: String) : OpenTarget
  data class NotificationAction(val key: String, val index: Int) : OpenTarget
  /** A quick tile's long-press: its system popup or settings page. */
  data class Settings(val toggle: QuickToggle) : OpenTarget
}

enum class IconStyle { Mono, Color }

data class LauncherSettings(
  val dock: List<String>? = null, // app keys; null = never edited, use defaults
  val iconStyle: IconStyle = IconStyle.Mono,
  val wallpaperVersion: Long = 0, // 0 = no custom wallpaper
  val dotFilter: Boolean = true,
)

const val DOCK_SIZE = 4

/** Label matches only; names that start with the query come first. */
fun filterApps(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
  val q = query.trim()
  if (q.isEmpty()) return apps
  return apps
    .filter { it.label.contains(q, ignoreCase = true) }
    .sortedBy { !it.label.startsWith(q, ignoreCase = true) } // stable: keeps A–Z inside each group
}

/** Saved dock, or the first installed of a few everyday apps. */
fun resolveDock(saved: List<String>?, apps: List<LaunchableApp>): List<LaunchableApp> {
  val byKey = apps.associateBy { it.key }
  if (saved != null) return saved.mapNotNull(byKey::get)
  val picks = DEFAULT_DOCK.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
  return (picks + apps).distinct().take(DOCK_SIZE)
}

private val DEFAULT_DOCK = listOf(
  "com.sec.android.app.camera",
  "com.android.chrome",
  "com.sec.android.gallery3d",
  "com.samsung.android.messaging",
)

/** "NOW", "5M", "3H", "2D": age of a notification for an instrument-style label. */
fun relativeAge(postedAt: Long, now: Long): String {
  val minutes = (now - postedAt) / 60_000
  return when {
    minutes < 1 -> "NOW"
    minutes < 60 -> "${minutes}M"
    minutes < 24 * 60 -> "${minutes / 60}H"
    else -> "${minutes / (24 * 60)}D"
  }
}
