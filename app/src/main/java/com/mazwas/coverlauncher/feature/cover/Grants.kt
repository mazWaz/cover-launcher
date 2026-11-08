package com.mazwas.coverlauncher.feature.cover

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherSettings
import com.mazwas.coverlauncher.feature.launcher.domain.withCoverApps
import kotlin.concurrent.thread
import com.mazwas.coverlauncher.core.system.BANKING
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.coverPrefs
import com.mazwas.coverlauncher.core.system.keepFolded
import com.mazwas.coverlauncher.feature.launcher.data.NotificationListener
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherActivity

/** Everything the launcher needs from the shell user. Idempotent; re-run whenever Shizuku comes up (e.g. after a reboot). */
class Grants(private val context: Context) {
  fun apply() {
    if (!Shell.ready()) return
    val pkg = context.packageName
    // main screen is broken: force the folded state so only the cover is used, and keep it across folds.
    // Undo: `pkill -f coverlauncher-fold; cmd device_state state reset`
    Shell.exec("cmd", "device_state", "state", "0")
    keepFolded()
    // lets CoverService start the launcher from the background
    Shell.exec("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
    Shell.exec("cmd", "notification", "allow_listener", ComponentName(context, NotificationListener::class.java).flattenToString())
    // the sound tile's silent mode
    Shell.exec("cmd", "notification", "allow_dnd", pkg)

    if (!coverServiceEnabled()) {
      // keep other apps' services, drop our own old component names
      val next = enabledServices().filterNot { it.startsWith("$pkg/") } + coverService
      Shell.exec("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
      Shell.exec("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }
    listAppsOnCover()
    // After an update (`adb install -r`) the system left CoverService enabled but stuck "binding" (seen 2026-11-08: no
    // ServiceRecord, nothing retried). Still not connected a moment later: switch it off and on, which rebinds it.
    main.postDelayed({ if (!CoverService.connected && coverServiceEnabled()) rebindCoverService() }, 5_000)
  }

  /**
   * Samsung lets apps on MultiStar's cover list open on the cover from a plain `startActivity`, so with every launchable
   * app listed, launches keep working while Shizuku is down. Only adds; the first change backs the old value up for adb.
   * shortcut: user 0 only (no work profile / Secure Folder apps); add their user ids if those should open on the cover.
   */
  private fun listAppsOnCover() = thread {
    val key = "multistar_setting_repository"
    val apps = context.getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
      .map { it.componentName.packageName }.filter { it != context.packageName }.sorted()
    val next = withCoverApps(Shell.output("settings", "get", "secure", key)?.trim(), apps) ?: return@thread
    Shell.output("sh", "-c", "[ -f $MULTISTAR_BACKUP ] || settings get secure $key > $MULTISTAR_BACKUP")
    Shell.exec("settings", "put", "secure", key, next)
  }

  /**
   * What the Settings screen lists, each fixed by [fix] (the last one only by the user: it's their developer option).
   * Blocking (shell): call off the main thread.
   */
  fun checks(listenerConnected: Boolean, settings: LauncherSettings): List<SetupCheck> {
    val shell = Shell.ready()
    fun sh(cmd: String) = if (shell) Shell.output("sh", "-c", cmd)?.trim() else null
    return listOfNotNull(
      SetupCheck("shizuku", shell),
      SetupCheck("notifications", listenerConnected),
      SetupCheck("cover service", coverServiceEnabled()),
      SetupCheck("overlay", Settings.canDrawOverlays(context)),
      SetupCheck("do not disturb", context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted),
      SetupCheck("fold keeper", !sh("pgrep -f '[c]overlauncher-fold'").isNullOrEmpty()),
      SetupCheck("samsung pop-ups off", sh("settings get system edge_lighting; settings get secure cover_screen_show_notification") == "0\n0")
        .takeIf { settings.popups },
      SetupCheck("samsung aod always", sh("settings get system aod_mode; settings get system aod_tap_to_show_mode") == "1\n0")
        .takeIf { settings.aod },
      SetupCheck("sleeps while charging", Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) == 0),
    )
  }

  /** [apply] plus Samsung's settings our pop-up and AOD rely on. */
  fun fix(settings: LauncherSettings) {
    apply()
    if (settings.popups) samsungPopups(false)
    if (settings.aod) Shell.exec("sh", "-c", "settings put system aod_mode 1; settings put system aod_tap_to_show_mode 0")
  }

  /**
   * Samsung's own cover pop-ups, off while ours is on so nothing pops up twice. Proven on the phone 2026-11-08 with
   * WhatsApp messages: `edge_lighting` 0 stops the brief pill, `cover_screen_show_notification` 0 the detailed card it
   * falls back to; `heads_up_notifications_enabled` stops neither. 1 is One UI's default for both (and this phone's).
   * Undo by hand (e.g. after uninstalling): `settings put system edge_lighting 1; settings put secure cover_screen_show_notification 1`
   */
  fun samsungPopups(on: Boolean) {
    val v = if (on) 1 else 0
    Shell.exec("sh", "-c", "settings put system edge_lighting $v; settings put secure cover_screen_show_notification $v")
  }

  /**
   * Banking mode: banking apps refuse to run beside an accessibility service, so CoverService steps aside while [pkg]
   * is in front. Switches it off before returning (call off the main thread, then open the app), and leaves a shell-user
   * watcher (process `coverlauncher-bank`) that gives [pkg] 10 s to come up, then switches CoverService back on once
   * anything else is in front (checked every 0.5 s, ~0.07 s of dumpsys each). If that is Samsung's cover home, the watcher opens the launcher, as CoverService would.
   */
  fun bankingMode(pkg: String) {
    if (!Shell.ready()) return
    val others = enabledServices().filterNot { it == coverService }
    context.coverPrefs().edit().putBoolean(BANKING, true).commit()
    // its own exec: in the same script the pattern would match that script's command line and kill it
    Shell.exec("sh", "-c", "pkill -f '[c]overlauncher-bank'").join()
    Shell.exec("sh", "-c", bankWatcher(pkg, others.joinToString(":"), (others + coverService).joinToString(":"))).join()
  }

  private fun bankWatcher(pkg: String, without: String, with: String): String {
    val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    val launcher = ComponentName(context, LauncherActivity::class.java).flattenToShortString()
    return """
      settings put secure $key '$without'
      ${if (without.isEmpty()) "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 0" else ""}
      nohup sh -c 'top() { dumpsys activity activities | grep -m1 topResumedActivity; }
        i=0; until top | grep -qF " $pkg/"; do i=$((i + 1)); [ ${'$'}i -gt 20 ] && break; sleep 0.5; done
        while top | grep -qF " $pkg/"; do sleep 0.5; done
        settings put secure $key "$with"; settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1
        top | grep -qF SubHomeActivity && am start --display $COVER_DISPLAY -n $launcher
      ' coverlauncher-bank >/dev/null 2>&1 &
    """.trimIndent()
  }

  private fun rebindCoverService() {
    val all = enabledServices()
    val without = all.filterNot { it == coverService }.joinToString(":")
    val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    Shell.exec("sh", "-c", "settings put secure $key '$without'; sleep 1; settings put secure $key '${all.joinToString(":")}'")
  }

  private val main = Handler(Looper.getMainLooper())

  private companion object {
    const val MULTISTAR_BACKUP = "/data/local/tmp/coverlauncher-multistar.bak"
  }

  /** Whether CoverService (the part that keeps the launcher the cover home) is switched on. */
  fun coverServiceEnabled(): Boolean = coverService in enabledServices()

  private val coverService = ComponentName(context, CoverService::class.java).flattenToString()

  private fun enabledServices() =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      .orEmpty().split(':').filter { it.isNotEmpty() }
}

/** One line of Settings → system. */
data class SetupCheck(val name: String, val ok: Boolean)
