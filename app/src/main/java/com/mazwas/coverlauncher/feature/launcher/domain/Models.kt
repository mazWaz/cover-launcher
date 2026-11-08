package com.mazwas.coverlauncher.feature.launcher.domain

import android.app.Notification
import android.app.NotificationManager

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
  val media: Boolean = false, // carries a media session (the panel's player can stand in for it)
  val reply: Int = -1, // index in [actions] of the button that takes typed text (Reply); -1: none
)

/**
 * Up to three of a notification's buttons. None for calls: on this phone no WhatsApp call answered from another app's
 * listener (OpenCover) ever went active, so tapping the call opens the app's own call screen instead.
 */
fun notificationActions(category: String?, titles: List<String>): List<String> =
  if (category == "call") emptyList() else titles.take(3)

/**
 * Whether a just-posted notification gets the cover pop-up, by Android's heads-up rules: importance HIGH or more,
 * lets through Do Not Disturb, new or re-alerting (an update marked "alert only once" stays quiet), and neither a group
 * summary (its child pops up) nor ongoing (calls open their own screen; downloads and players would pop up forever).
 */
fun shouldPopUp(flags: Int, importance: Int, isUpdate: Boolean, passesDnd: Boolean): Boolean =
  importance >= NotificationManager.IMPORTANCE_HIGH && passesDnd &&
    flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) == 0 &&
    !(isUpdate && flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

enum class SoundMode {
  Ring, Vibrate, Silent;

  /** Tapping the sound tile cycles ring → vibrate → silent, like One UI's. */
  val next: SoundMode get() = entries[(ordinal + 1) % entries.size]
}

data class SystemStatus(
  val batteryPercent: Int = 0,
  val batteryTenthsC: Int = 0, // battery temperature, tenths of °C (371 = 37.1 °C)
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
  val nfcOn: Boolean = false,
  val darkOn: Boolean = false,
  val keepAwakeOn: Boolean = false,
  val rotateAutoOn: Boolean = false,
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
    QuickToggle.Nfc -> nfcOn
    QuickToggle.Dark -> darkOn
    QuickToggle.KeepAwake -> keepAwakeOn
    QuickToggle.Rotate -> rotateAutoOn
  }
}

/** The media session at the top of Android's list (most recently active), for the panel's player. */
data class NowPlaying(val packageName: String, val title: String, val artist: String, val playing: Boolean)

enum class MediaCommand { Previous, PlayPause, Next }

/** A session without a title (an app that registered one but plays nothing yet) shows no player. */
fun nowPlaying(packageName: String?, title: CharSequence?, artist: CharSequence?, playing: Boolean): NowPlaying? {
  val name = title?.toString()?.takeIf { it.isNotBlank() } ?: return null
  return NowPlaying(packageName ?: return null, name, artist?.toString().orEmpty(), playing)
}

/** The player stands in for its app's media notification: don't list that one twice. */
fun listedNotifications(notes: List<LauncherNotification>, playing: NowPlaying?): List<LauncherNotification> =
  if (playing == null) notes else notes.filterNot { it.media && it.packageName == playing.packageName }

/**
 * MultiStar's secure setting `multistar_setting_repository`: slots split by '/', slot 26 lists the apps Samsung lets open
 * on the cover as "pkg,userId" joined by ';' ("0" when empty). Returns it with [packages] added to that slot and every
 * other slot untouched, or null when nothing is missing or the value isn't shaped like that. Never removes an entry.
 */
fun withCoverApps(repository: String?, packages: Collection<String>, userId: Int = 0): String? {
  val slots = repository?.split('/')?.toMutableList()?.takeIf { it.size > COVER_SLOT } ?: return null
  val entries = slots[COVER_SLOT].takeUnless { it == "0" }.orEmpty().split(';').filter { it.isNotEmpty() }
  val missing = packages.distinct().map { "$it,$userId" }.filterNot { it in entries }
  if (missing.isEmpty()) return null
  slots[COVER_SLOT] = (entries + missing).joinToString(";")
  return slots.joinToString("/")
}

private const val COVER_SLOT = 25 // slot 26

/** Samsung's own cover brightness: [level] 1..255 (`system/sub_screen_brightness`) and whether auto brightness drives it. */
data class CoverBrightness(val level: Int, val auto: Boolean)

/** Output of `settings get system sub_screen_brightness; settings get system sub_screen_brightness_mode`. */
fun parseCoverBrightness(out: String?): CoverBrightness? {
  val lines = out?.lines()?.map { it.trim() } ?: return null
  val level = lines.getOrNull(0)?.toIntOrNull() ?: return null
  return CoverBrightness(level.coerceIn(1, 255), auto = lines.getOrNull(1) == "1")
}

/** How much of a notification the pop-up may show. */
enum class LockedView { Full, Public, Hidden }

/**
 * Android's lock-screen rules, for the pop-up over a PIN/pattern/password lock: nothing when notifications are off on the
 * lock screen or the notification is secret; its public version when it is private and the user hides private content.
 * [visibility] is the channel's lock-screen visibility if it sets one, else the notification's.
 */
fun lockedView(secureLocked: Boolean, showOnLock: Boolean, showPrivate: Boolean, visibility: Int): LockedView = when {
  !secureLocked -> LockedView.Full
  !showOnLock || visibility == Notification.VISIBILITY_SECRET -> LockedView.Hidden
  visibility == Notification.VISIBILITY_PRIVATE && !showPrivate -> LockedView.Public
  else -> LockedView.Full
}

/** Quick settings tiles in panel order; the collapsed panel shows the first five. */
enum class QuickToggle { Wifi, Bluetooth, Data, Torch, Dnd, Airplane, Sound, Location, Saver, Nfc, Dark, KeepAwake, Rotate }

/** Every tile in the saved order: unknown names dropped, tiles added since appended at the end. */
fun tileOrder(saved: List<String>?): List<QuickToggle> =
  (saved.orEmpty().mapNotNull { name -> QuickToggle.entries.firstOrNull { it.name == name } } + QuickToggle.entries).distinct()

/** [tile] moved [by] places, stopping at either end. */
fun List<QuickToggle>.moved(tile: QuickToggle, by: Int): List<QuickToggle> {
  val from = indexOf(tile)
  return toMutableList().apply { add((from + by).coerceIn(0, lastIndex), removeAt(from)) }
}

/**
 * The cover's rotation (Surface.ROTATION_*) for the app in front: its own if it has one (e.g. a game upside down so the
 * camera sits top left instead of over its buttons), otherwise the rotate tile: [sensed], or locked at [lockedAt].
 */
fun coverRotation(front: String, appRotation: Map<String, Int>, auto: Boolean, lockedAt: Int, sensed: Int): Int =
  appRotation[front] ?: if (auto) sensed else lockedAt

enum class Heat { Normal, Warm, Hot }

/**
 * Battery heat: warm from 42 °C (warn), hot from 45 °C (also cut what we can). Coming down needs 1 °C below a step,
 * so a temperature sitting on 42.0 doesn't warn again and again.
 */
fun heat(tenthsC: Int, before: Heat): Heat = when {
  tenthsC >= 450 -> Heat.Hot
  tenthsC >= 420 -> if (before == Heat.Hot && tenthsC >= 440) Heat.Hot else Heat.Warm
  tenthsC >= 410 && before != Heat.Normal -> Heat.Warm
  else -> Heat.Normal
}

/** The drawer menu's per-app choices, cycled by tapping; null = as the cover is set. Rotations are Surface.ROTATION_*. */
val APP_ROTATIONS: List<Int?> = listOf(null, 0, 1, 2, 3)
val APP_DENSITIES: List<Int?> = listOf(null, 240, 280, 360, 400) // the cover's own is 320 dpi

fun <T> List<T>.after(current: T): T = get((indexOf(current) + 1) % size)

/** Per-app values in DataStore: a string set of "package=value" (package names never hold '='). */
fun Set<String>.toIntMap(): Map<String, Int> = mapNotNull { entry ->
  val pkg = entry.substringBefore('=', "")
  entry.substringAfter('=').toIntOrNull()?.takeIf { pkg.isNotEmpty() }?.let { pkg to it }
}.toMap()

fun Map<String, Int>.toEntrySet(): Set<String> = map { (pkg, value) -> "$pkg=$value" }.toSet()

/** Something to open on the cover. */
sealed interface OpenTarget {
  /** [banking]: open it in banking mode (Grants.bankingMode). */
  data class App(val packageName: String, val className: String, val banking: Boolean = false) : OpenTarget
  data class Info(val packageName: String) : OpenTarget
  data class Uninstall(val packageName: String) : OpenTarget
  data class Notification(val key: String) : OpenTarget
  data class NotificationAction(val key: String, val index: Int) : OpenTarget
  /** [text] sent through the notification's Reply button [index], as if typed in the app. */
  data class Reply(val key: String, val index: Int, val text: String) : OpenTarget
  /** A quick tile's long-press: its system popup or settings page. */
  data class Settings(val toggle: QuickToggle) : OpenTarget
}

enum class IconStyle { Mono, Color }

data class LauncherSettings(
  val dock: List<String>? = null, // app keys; null = never edited, use defaults
  val iconStyle: IconStyle = IconStyle.Mono,
  val wallpaperVersion: Long = 0, // 0 = no custom wallpaper
  val dotFilter: Boolean = true,
  val popups: Boolean = false, // our cover pop-up instead of Samsung's
  val appRotation: Map<String, Int> = emptyMap(), // package → Surface.ROTATION_* while it's in front (coverRotation)
  val appDensity: Map<String, Int> = emptyMap(), // package → dpi while it's in front
  val density: Int? = null, // the cover's dpi otherwise; null = its own (320)
  val restricted: Set<String> = emptySet(), // packages kept from running in the background
  val tiles: List<String>? = null, // QuickToggle names in panel order; null = never arranged
  val hiddenTiles: Set<String> = emptySet(), // QuickToggle names left out of the panel
  val aod: Boolean = false, // our AOD over Samsung's while the cover dozes
  val banking: Set<String> = emptySet(), // packages CoverService steps aside for (banking mode)
  val deepSleep: Boolean = false, // force Doze's deep idle a while after the cover goes off
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
