package com.mazwas.coverlauncher.feature.launcher.data

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.coverOptions
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle

/** Opens apps, app pages and notifications on the cover. Call after the keyguard is gone. */
class CoverOpener(private val context: Context, private val notifications: NotificationStore) {

  fun open(target: OpenTarget) {
    Log.i(TAG, "open $target")
    when (target) {
      is OpenTarget.App -> app(target.packageName, target.className)
      is OpenTarget.Info -> start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(target.packageName)))
      is OpenTarget.Uninstall -> start(Intent(Intent.ACTION_DELETE, packageUri(target.packageName)))
      is OpenTarget.Notification -> notification(target.key)
      is OpenTarget.NotificationAction -> action(target.key, target.index)
      is OpenTarget.Settings -> settingsAction(target.toggle)?.let(::settings)
    }
  }

  /** The system's floating panel where Android has one (Wi-Fi, internet, volume), else the settings page. */
  private fun settingsAction(toggle: QuickToggle): String? = when (toggle) {
    QuickToggle.Wifi -> Settings.Panel.ACTION_WIFI
    QuickToggle.Data -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
    QuickToggle.Sound -> Settings.Panel.ACTION_VOLUME
    QuickToggle.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS
    QuickToggle.Dnd -> "android.settings.ZEN_MODE_SETTINGS" // hidden constant, public intent
    QuickToggle.Airplane -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
    QuickToggle.Location -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
    QuickToggle.Saver -> Settings.ACTION_BATTERY_SAVER_SETTINGS
    QuickToggle.Torch -> null
  }

  private fun settings(action: String) {
    // Settings isn't a cover app: started by shell, Samsung lets it on the cover (see app())
    if (Shell.ready()) Shell.exec("am", "start", "--display", "$COVER_DISPLAY", "-a", action, "-f", "0x10000000")
    else start(Intent(action))
  }

  private fun app(pkg: String, cls: String) {
    if (Shell.ready()) {
      // started by shell, Samsung skips its "only MultiStar-listed apps on the cover" check
      Shell.exec(
        "am", "start", "--display", "$COVER_DISPLAY", "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
        "-f", "0x10200000", "-n", "$pkg/$cls", // NEW_TASK | RESET_TASK_IF_NEEDED
      )
    } else {
      start(Intent.makeMainActivity(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }
  }

  private fun notification(key: String) {
    val sbn = notifications.get(key) ?: return
    val sent = sbn.notification.contentIntent?.let { pending ->
      runCatching { pending.send(context, 0, null, null, null, null, coverOptions()) }
        .onFailure { Log.w(TAG, "notification intent failed", it) }.isSuccess
    } ?: false
    // no (working) content intent: open the app instead
    if (!sent) context.packageManager.getLaunchIntentForPackage(sbn.packageName)?.component?.let { app(it.packageName, it.className) }
    if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) notifications.dismiss(key)
  }

  // shortcut: a Reply button opens the notification (the app's own text box) instead of an inline one; add RemoteInput
  // here once typing in the panel is wanted.
  private fun action(key: String, index: Int) {
    val action = notifications.get(key)?.notification?.actions?.getOrNull(index) ?: return
    if (!action.remoteInputs.isNullOrEmpty()) return notification(key)
    runCatching { action.actionIntent?.send(context, 0, null, null, null, null, coverOptions()) }
      .onFailure { Log.w(TAG, "notification action failed", it) }
  }

  private fun start(intent: Intent) = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), coverOptions())
  }.onFailure { Log.w(TAG, "start failed: $intent", it) }

  private fun packageUri(pkg: String) = Uri.fromParts("package", pkg, null)
}
