package com.mazwas.coverlauncher.feature.cover

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.mazwas.coverlauncher.feature.launcher.domain.withCoverApps
import kotlin.concurrent.thread
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.keepFolded
import com.mazwas.coverlauncher.feature.launcher.data.NotificationListener

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
