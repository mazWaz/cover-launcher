package com.mazwas.coverlauncher.feature.cover

import android.app.AlarmManager
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mazwas.coverlauncher.feature.cover.ShizukuAutoStart.Step
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.KEEP_AWAKE
import com.mazwas.coverlauncher.core.system.ROTATE_AUTO
import com.mazwas.coverlauncher.core.system.ROTATE_LOCK
import com.mazwas.coverlauncher.core.system.BANKING
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.coverPrefs
import com.mazwas.coverlauncher.core.system.MAIN_DISPLAY
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.core.system.coverOptions
import com.mazwas.coverlauncher.core.system.pullToCover
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
import com.mazwas.coverlauncher.feature.launcher.domain.Heat
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.heat
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherActivity
import kotlinx.coroutines.MainScope
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
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
  private val notifications: NotificationStore by inject()
  private val settings: SettingsStore by inject()
  private val popup by lazy { CoverPopup(this, get(), notifications, get()) }
  private val display by lazy { CoverDisplay(this) { rootInActiveWindow?.packageName } }
  private val aod by lazy { CoverAod(this, get(), get(), get(), notifications, get()) }
  private val system: SystemStatusSource by inject()
  private var heat = Heat.Normal
  private val grants: Grants by inject()
  private var banking = emptySet<String>()
  private var bankingStarted = false
  private val scope = MainScope()

  // Shizuku hands out its binder only when our uid starts; its "send to every client" at server start misses us on
  // Android 17. We start before Shizuku at boot and never stop (bound a11y service), so without the binder:
  // restart while the cover is off, the system brings us right back and Shizuku sends it.
  // ponytail: also restarts on every screen-off while Shizuku isn't running at all; cheap, cover is off.
  private val screenOff = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      scope.launch {
        val s = settings.settings.first()
        if (s.aod) aod.show()
        if (s.deepSleep && !getSystemService(BatteryManager::class.java).isCharging) scheduleDeepSleep()
      }
      if (Shizuku.pingBinder()) return
      Log.i(TAG, "no Shizuku binder -> restarting")
      Process.killProcess(Process.myPid())
    }
  }

  private val screenOn = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      aod.hide()
      wakeFromDeepSleep()
    }
  }

  override fun onServiceConnected() {
    connected = true
    registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    coverPrefs().registerOnSharedPreferenceChangeListener(tilesChanged)
    holdCoverOn()
    // boot / reinstall left no launcher task: create it now so the cover wakes to it (it shows over the lock screen).
    // Back from banking mode the user is in an app: leave them there.
    if (!coverPrefs().getBoolean(BANKING, false)) showLauncher()
    coverPrefs().edit().remove(BANKING).apply()
    // Not over the AOD: a dozing cover takes no swipes, and the AOD already shows the new alert (icon and count) without
    // its text (seen 2026-11-08: name and message stuck on the dozing cover)
    scope.launch {
      notifications.popups.collect {
        if (settings.settings.first().popups && getSystemService(PowerManager::class.java).isInteractive) popup.show(it)
      }
    }
    scope.launch {
      settings.settings.collect {
        display.update(it)
        banking = it.banking
      }
    }
    scope.launch { system.batteryTenthsC.collect(::onTemperature) }
  }

  override fun onDestroy() {
    connected = false
    scope.cancel()
    popup.hide()
    aod.hide()
    unregisterReceiver(screenOff)
    unregisterReceiver(screenOn)
    display.stop()
    coverPrefs().unregisterOnSharedPreferenceChangeListener(tilesChanged)
    releaseCover()
    super.onDestroy()
  }

  // Deep sleep (opt-in): Doze's deep idle only comes after ~30+ min without motion, and this phone rarely lies still
  // that long. Forced DEEP_SLEEP_AFTER the cover went off on battery, undone when it comes on (a forced idle would
  // otherwise outlast the screen). An alarm, not a Handler: uptime stops while the CPU sleeps. Inexact: no permission.
  private var deepForced = false
  private val deepSleep = AlarmManager.OnAlarmListener {
    deepForced = true
    Log.i(TAG, "cover off a while -> deep idle")
    Shell.exec("dumpsys", "deviceidle", "force-idle", "deep")
  }

  private fun scheduleDeepSleep() = getSystemService(AlarmManager::class.java)
    .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DEEP_SLEEP_AFTER, "coverlauncher:deep", deepSleep, null)

  private fun wakeFromDeepSleep() {
    getSystemService(AlarmManager::class.java).cancel(deepSleep)
    if (!deepForced) return
    deepForced = false
    // unforce alone left it idle until the PIN unlock (Doze judges "screen on" by the dead main display): motion wakes it
    Shell.exec("sh", "-c", "dumpsys deviceidle unforce; dumpsys deviceidle motion")
  }

  /** Warm: a warning card. Hot: also keep awake and torch off and a dimmer cover; nothing else here makes heat. */
  private fun onTemperature(tenthsC: Int) {
    val next = heat(tenthsC, heat)
    val rising = next > heat
    heat = next
    if (!rising) return
    if (next == Heat.Hot) {
      coverPrefs().edit().putBoolean(KEEP_AWAKE, false).apply()
      system.toggle(QuickToggle.Torch, false)
      system.setCoverBrightness(HOT_BRIGHTNESS)
    }
    val text = if (next == Heat.Hot) "keep awake and torch off, cover dimmed" else "battery getting warm"
    // a card of its own, not a real notification: no channel or permission needed; tapping or swiping it does nothing
    popup.show(
      LauncherNotification(HEAT_KEY, packageName, "Cover Launcher", "Battery %.1f °C".format(tenthsC / 10f), text, System.currentTimeMillis(), clearable = false),
    )
  }

  // Keep-awake tile: an invisible 1×1 window on the cover with FLAG_KEEP_SCREEN_ON stops the cover timing out, whatever
  // app is in front. The power key still turns it off.
  private var awake: View? = null
  private val tilesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    when (key) {
      KEEP_AWAKE -> holdCoverOn()
      ROTATE_AUTO, ROTATE_LOCK -> display.apply()
    }
  }

  private fun holdCoverOn() {
    if (!coverPrefs().getBoolean(KEEP_AWAKE, false)) return releaseCover()
    if (awake != null) return
    val cover = displays.getDisplay(COVER_DISPLAY) ?: return
    val window = createDisplayContext(cover).createWindowContext(LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    val view = View(window)
    runCatching {
      window.getSystemService(WindowManager::class.java).addView(view, LayoutParams(
        1, 1, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSPARENT,
      ))
      awake = view
    }.onFailure { Log.w(TAG, "keep awake window failed", it) }
  }

  private fun releaseCover() {
    awake?.let { runCatching { it.context.getSystemService(WindowManager::class.java).removeView(it) } }
    awake = null
  }

  override fun onAccessibilityEvent(e: AccessibilityEvent) {
    if (e.displayId == COVER_DISPLAY) display.onWindow(e.packageName, e.className)
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
      // opened some other way than the launcher (a notification, recents): late, but the app may check again
      e.displayId == COVER_DISPLAY && e.packageName?.toString() in banking && !bankingStarted -> {
        bankingStarted = true
        val pkg = e.packageName.toString()
        Log.i(TAG, "$pkg in front -> banking mode")
        thread { grants.bankingMode(pkg) }
      }
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

  companion object {
    /** True while the system has this service bound (same process as the app); Grants rebinds it when it isn't. */
    @Volatile
    var connected = false
      private set

    private const val HEAT_KEY = "coverlauncher:heat"
    private const val HOT_BRIGHTNESS = 40 // of 255
    private const val DEEP_SLEEP_AFTER = 5 * 60_000L
    private const val SUB_HOME = "com.android.systemui.subscreen.SubHomeActivity"
    private const val SUB_FLASHLIGHT = "com.android.systemui.qp.flashlight.SubroomFlashLightSettingsActivity"
    // what normally lives on the main screen; never moved
    private val MAIN_SYSTEM = setOf(null, "com.sec.android.app.launcher", "com.android.systemui")
    // "Allow USB/wireless debugging?" open only on the main screen: moved to the cover so the user answers them there
    // (never answered for them: that prompt is what keeps an unknown computer from getting a shell).
    private val DEBUG_PROMPTS = setOf("com.android.systemui.usb.UsbDebuggingActivity", "com.android.systemui.wifi.WifiDebuggingActivity")
  }
}
