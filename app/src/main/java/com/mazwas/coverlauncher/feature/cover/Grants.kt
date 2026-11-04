package com.mazwas.coverlauncher.feature.cover

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.feature.launcher.data.NotificationListener

/** Everything the launcher needs from the shell user. Idempotent; re-run whenever Shizuku comes up (e.g. after a reboot). */
class Grants(private val context: Context) {
  fun apply() {
    if (!Shell.ready()) return
    val pkg = context.packageName
    // main screen is broken: force the folded state so only the cover is used. Undo: `cmd device_state state reset`
    Shell.exec("cmd", "device_state", "state", "0")
    // lets CoverService start the launcher from the background
    Shell.exec("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
    Shell.exec("cmd", "notification", "allow_listener", ComponentName(context, NotificationListener::class.java).flattenToString())
    // the sound tile's silent mode
    Shell.exec("cmd", "notification", "allow_dnd", pkg)

    val service = ComponentName(context, CoverService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      .orEmpty().split(':').filter { it.isNotEmpty() }
    if (service !in enabled) {
      // keep other apps' services, drop our own old component names
      val next = enabled.filterNot { it.startsWith("$pkg/") } + service
      Shell.exec("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
      Shell.exec("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }
  }
}
