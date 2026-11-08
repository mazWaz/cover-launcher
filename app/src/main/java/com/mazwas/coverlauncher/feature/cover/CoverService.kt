package com.mazwas.coverlauncher.feature.cover

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mazwas.coverlauncher.feature.cover.ShizukuAutoStart.Step
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.MAIN_DISPLAY
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.core.system.coverOptions
import com.mazwas.coverlauncher.core.system.pullToCover
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherActivity
import rikka.shizuku.Shizuku

/**
 * Makes the launcher the cover's home: whenever Samsung's cover home (clock/widgets) comes up —
 * screen woke, app closed, Home pressed — open the launcher on top of it.
 * Also catches apps opening on the dead main screen and moves them to the cover.
 * Accessibility service = the system keeps us alive (also after boot) and reports window changes.
 */
class CoverService : AccessibilityService() {
  private val displays by lazy { getSystemService(DisplayManager::class.java) }
  private val handler = Handler(Looper.getMainLooper())
  private var torchBackAt = 0L

  // Shizuku hands out its binder only when our uid starts; its "send to every client" at server start misses us on
  // Android 17. We start before Shizuku at boot and never stop (bound a11y service), so without the binder:
  // restart while the cover is off, the system brings us right back and Shizuku sends it.
  // ponytail: also restarts on every screen-off while Shizuku isn't running at all; cheap, cover is off.
  private val screenOff = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (Shizuku.pingBinder()) return
      Log.i(TAG, "no Shizuku binder -> restarting")
      Process.killProcess(Process.myPid())
    }
  }

  override fun onServiceConnected() {
    registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    // boot / reinstall left no launcher task: create it now so the cover wakes to it (it shows over the lock screen)
    showLauncher()
  }

  override fun onDestroy() {
    unregisterReceiver(screenOff)
    super.onDestroy()
  }

  override fun onAccessibilityEvent(e: AccessibilityEvent) {
    when {
      e.displayId == COVER_DISPLAY && e.className == SUB_HOME -> {
        Log.i(TAG, "cover home shown -> launcher")
        showLauncher()
      }
      // Samsung opens its torch-level screen on the cover whenever the torch turns on: close it, stay in the launcher
      // its window events come in pairs: a second Back would land on the launcher and close the panel
      e.displayId == COVER_DISPLAY && e.className == SUB_FLASHLIGHT &&
        SystemClock.uptimeMillis() - torchBackAt > 1_000 -> {
        torchBackAt = SystemClock.uptimeMillis()
        Log.i(TAG, "Samsung torch screen -> back")
        performGlobalAction(GLOBAL_ACTION_BACK)
      }
      e.packageName == ShizukuAutoStart.PACKAGE && ShizukuAutoStart.step != Step.Idle -> onShizukuWindow(e.className?.toString())
      // only while folded: when the main screen is on (unfolded, or `device_state state 3`) things belong there
      e.displayId == MAIN_DISPLAY && displays.getDisplay(MAIN_DISPLAY)?.state == Display.STATE_OFF &&
        (e.packageName?.toString() !in MAIN_SYSTEM || e.className?.toString() in DEBUG_PROMPTS) -> {
        Log.i(TAG, "${e.packageName} opened on the main screen -> cover")
        pullToCover()
      }
    }
  }

  // background start allowed: SYSTEM_ALERT_WINDOW is granted (Grants)
  private fun showLauncher() =
    startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), coverOptions())

  // Main screen events come in pairs: only the starter screen closing means the server is up.
  private fun onShizukuWindow(window: String?) = when {
    window == ShizukuAutoStart.MAIN && ShizukuAutoStart.step == Step.Opened -> {
      ShizukuAutoStart.step = Step.Pressing
      pressShizukuStart(tries = 10)
    }
    window == ShizukuAutoStart.STARTER && ShizukuAutoStart.step == Step.Pressed -> ShizukuAutoStart.step = Step.Starting
    window == ShizukuAutoStart.MAIN && ShizukuAutoStart.step == Step.Starting -> {
      ShizukuAutoStart.step = Step.Idle
      Log.i(TAG, "Shizuku started -> launcher")
      showLauncher()
    }
    else -> Unit
  }

  /** Its list may still be loading: retry a few times. Not found (already running, or the UI changed): just go back. */
  private fun pressShizukuStart(tries: Int) {
    val start = shizukuStartButton()
    when {
      start != null -> {
        Log.i(TAG, "Shizuku not running -> pressing Start")
        ShizukuAutoStart.step = Step.Pressed
        start.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
      tries > 0 -> handler.postDelayed({ pressShizukuStart(tries - 1) }, 300)
      else -> {
        Log.w(TAG, "Shizuku Start button not found -> launcher")
        ShizukuAutoStart.step = Step.Idle
        showLauncher()
      }
    }
  }

  // Resource ids are obfuscated, so by text. Two "Start" buttons (wireless debugging, root): take the wireless card's.
  // shortcut: English texts only. Shizuku 13.6 ships translations (id: "Mulai melalui Debug Nirkabel" / "Mulai"), so a
  // non-English system language makes this miss and Start is manual; match the translated texts if that happens.
  private fun shizukuStartButton(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow?.takeIf { it.packageName == ShizukuAutoStart.PACKAGE } ?: return null
    var card = root.findAccessibilityNodeInfosByText("Wireless debugging").firstOrNull()
    while (card != null && card.className != "androidx.cardview.widget.CardView") card = card.parent
    return card?.findAccessibilityNodeInfosByText("Start")?.firstOrNull { it.text?.toString() == "Start" && it.isClickable }
  }

  override fun onInterrupt() {}

  private companion object {
    const val SUB_HOME = "com.android.systemui.subscreen.SubHomeActivity"
    const val SUB_FLASHLIGHT = "com.android.systemui.qp.flashlight.SubroomFlashLightSettingsActivity"
    // what normally lives on the main screen; never moved
    val MAIN_SYSTEM = setOf(null, "com.sec.android.app.launcher", "com.android.systemui")
    // "Allow USB/wireless debugging?" open only on the main screen: moved to the cover so the user answers them there
    // (never answered for them: that prompt is what keeps an unknown computer from getting a shell).
    val DEBUG_PROMPTS = setOf("com.android.systemui.usb.UsbDebuggingActivity", "com.android.systemui.wifi.WifiDebuggingActivity")
  }
}
