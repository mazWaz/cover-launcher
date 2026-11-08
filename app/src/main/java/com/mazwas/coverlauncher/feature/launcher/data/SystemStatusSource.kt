package com.mazwas.coverlauncher.feature.launcher.data

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.hardware.display.DisplayManager
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.KEEP_AWAKE
import com.mazwas.coverlauncher.core.system.ROTATE_AUTO
import com.mazwas.coverlauncher.core.system.ROTATE_LOCK
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.coverPrefs
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.feature.launcher.domain.CoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.QuickToggle
import com.mazwas.coverlauncher.feature.launcher.domain.parseCoverBrightness
import com.mazwas.coverlauncher.feature.launcher.domain.SoundMode
import com.mazwas.coverlauncher.feature.launcher.domain.SystemStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Battery and every quick tile's state, live. */
class SystemStatusSource(private val context: Context) {
  private val wifi = context.getSystemService(WifiManager::class.java)
  private val bluetooth: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
  private val camera = context.getSystemService(CameraManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)
  private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)
  private val audio = context.getSystemService(AudioManager::class.java)
  private val location = context.getSystemService(LocationManager::class.java)
  private val power = context.getSystemService(PowerManager::class.java)
  private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
  private val displays = context.getSystemService(DisplayManager::class.java)

  private val base = combine(
    broadcasts(Intent.ACTION_BATTERY_CHANGED),
    flag(WifiManager.WIFI_STATE_CHANGED_ACTION) { wifi.isWifiEnabled },
    flag(BluetoothAdapter.ACTION_STATE_CHANGED) { bluetooth?.isEnabled == true },
    torch(),
    flag(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
      notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    },
  ) { battery, wifiOn, btOn, torchOn, dndOn ->
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
    SystemStatus(
      batteryPercent = level * 100 / scale,
      batteryTenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
      charging = plugged == BatteryManager.BATTERY_STATUS_CHARGING || plugged == BatteryManager.BATTERY_STATUS_FULL,
      wifiOn = wifiOn, bluetoothOn = btOn, torchOn = torchOn, dndOn = dndOn,
    )
  }

  val status: Flow<SystemStatus> = combine(
    base,
    mobileData(),
    flag(Intent.ACTION_AIRPLANE_MODE_CHANGED) {
      Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    },
    broadcasts(AudioManager.RINGER_MODE_CHANGED_ACTION).map { soundMode() }.distinctUntilChanged(),
    combine(
      flag(LocationManager.MODE_CHANGED_ACTION) { location.isLocationEnabled },
      flag(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) { power.isPowerSaveMode },
      flag(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) { runCatching { nfc?.isEnabled == true }.getOrDefault(false) },
      // night mode has no broadcast of its own; the app's configuration follows it
      flag(Intent.ACTION_CONFIGURATION_CHANGED) { context.resources.configuration.isNightModeActive },
      coverTiles(),
      ::Extra,
    ),
  ) { status, dataOn, airplaneOn, sound, extra ->
    status.copy(
      dataOn = dataOn, airplaneOn = airplaneOn, sound = sound, locationOn = extra.location, saverOn = extra.saver,
      nfcOn = extra.nfc, darkOn = extra.dark, keepAwakeOn = extra.tiles.first, rotateAutoOn = extra.tiles.second,
    )
  }

  /** Battery temperature in tenths of °C, on every change; cheaper than [status] for something always listening. */
  val batteryTenthsC: Flow<Int> =
    broadcasts(Intent.ACTION_BATTERY_CHANGED).map { it?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0 }.distinctUntilChanged()

  private data class Extra(val location: Boolean, val saver: Boolean, val nfc: Boolean, val dark: Boolean, val tiles: Pair<Boolean, Boolean>)

  /** Keep awake and rotate auto, from the prefs CoverService follows. */
  private fun coverTiles(): Flow<Pair<Boolean, Boolean>> = callbackFlow {
    val prefs = context.coverPrefs()
    val read = { trySend(prefs.getBoolean(KEEP_AWAKE, false) to prefs.getBoolean(ROTATE_AUTO, false)) }
    val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> read() }
    read()
    prefs.registerOnSharedPreferenceChangeListener(changed)
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(changed) }
  }.distinctUntilChanged()

  /** Emits the sticky intent (or null) right away, then every broadcast. */
  private fun broadcasts(action: String): Flow<Intent?> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) { trySend(intent) }
    }
    // EXPORTED: some of these come from non-system uids (e.g. the Bluetooth stack); all are protected broadcasts
    trySend(context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED))
    awaitClose { context.unregisterReceiver(receiver) }
  }

  /** A state read now and again on every [action] broadcast. */
  private fun flag(action: String, read: () -> Boolean): Flow<Boolean> =
    broadcasts(action).map { read() }.distinctUntilChanged()

  private fun soundMode() = when (audio.ringerMode) {
    AudioManager.RINGER_MODE_SILENT -> SoundMode.Silent
    AudioManager.RINGER_MODE_VIBRATE -> SoundMode.Vibrate
    else -> SoundMode.Ring
  }

  // No broadcast for it: the data switch writes mobile_data and mobile_data<subId>
  private fun mobileData(): Flow<Boolean> = callbackFlow {
    val read = { trySend(runCatching { telephony?.isDataEnabled == true }.getOrDefault(false)) }
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) { read() }
    }
    val sub = SubscriptionManager.getDefaultDataSubscriptionId()
    listOf("mobile_data", "mobile_data$sub").forEach {
      context.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
    }
    read()
    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
  }.distinctUntilChanged()

  private fun torch(): Flow<Boolean> = callbackFlow {
    val id = torchCamera()
    trySend(false)
    val callback = object : CameraManager.TorchCallback() {
      override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) trySend(enabled) }
    }
    camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose { camera.unregisterTorchCallback(callback) }
  }

  private fun torchCamera(): String? = camera.cameraIdList.firstOrNull {
    camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
  }

  /** Flip a quick toggle. The new state comes back through [status]. */
  fun toggle(toggle: QuickToggle, on: Boolean) {
    when (toggle) {
      QuickToggle.Wifi -> Shell.exec("cmd", "wifi", "set-wifi-enabled", if (on) "enabled" else "disabled")
      QuickToggle.Bluetooth -> Shell.exec("cmd", "bluetooth_manager", if (on) "enable" else "disable")
      QuickToggle.Data -> Shell.exec("cmd", "phone", "data", if (on) "enable" else "disable")
      QuickToggle.Dnd -> Shell.exec("cmd", "notification", "set_dnd", if (on) "on" else "off")
      QuickToggle.Airplane -> Shell.exec("cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable")
      QuickToggle.Location -> Shell.exec("cmd", "location", "set-location-enabled", "$on")
      QuickToggle.Saver -> Shell.exec("cmd", "power", "set-mode", if (on) "1" else "0")
      QuickToggle.Torch -> torchCamera()?.let { runCatching { camera.setTorchMode(it, on) } }
      QuickToggle.Sound -> setSound(if (on) SoundMode.Ring else SoundMode.Silent)
      // not `svc nfc`: it gets killed on this phone (exit 137, also from adb). "persist" keeps it off across reboots.
      QuickToggle.Nfc -> if (on) Shell.exec("cmd", "nfc", "enable-nfc") else Shell.exec("cmd", "nfc", "disable-nfc", "persist")
      QuickToggle.Dark -> Shell.exec("cmd", "uimode", "night", if (on) "yes" else "no")
      QuickToggle.KeepAwake -> context.coverPrefs().edit().putBoolean(KEEP_AWAKE, on).apply()
      // locking keeps the cover the way it is turned now, like Android's auto-rotate tile
      QuickToggle.Rotate -> context.coverPrefs().edit().putBoolean(ROTATE_AUTO, on)
        .putInt(ROTATE_LOCK, displays.getDisplay(COVER_DISPLAY)?.rotation ?: 0).apply()
    }
  }

  /** Samsung's cover brightness: a hidden setting, so read through the shell. Blocking; null without Shizuku. */
  fun coverBrightness(): CoverBrightness? = if (!Shell.ready()) null else parseCoverBrightness(
    Shell.output("sh", "-c", "settings get system sub_screen_brightness; settings get system sub_screen_brightness_mode"),
  )

  /** A manual level turns auto off first: while auto is on, Samsung's adaptive brightness keeps rewriting the level. */
  fun setCoverBrightness(level: Int) =
    Shell.exec("sh", "-c", "settings put system sub_screen_brightness_mode 0; settings put system sub_screen_brightness $level")

  fun setCoverBrightnessAuto() = Shell.exec("settings", "put", "system", "sub_screen_brightness_mode", "1")

  /** Restricted: no running in the background and the "restricted" standby bucket; allowed undoes both. */
  fun setBackgroundRestricted(pkg: String, on: Boolean) = Shell.exec(
    "sh", "-c",
    "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ${if (on) "ignore" else "allow"}; " +
      "am set-standby-bucket $pkg ${if (on) "restricted" else "active"}",
  )

  fun fontScale(): Float = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)

  fun setFontScale(scale: Float) = Shell.exec("settings", "put", "system", Settings.System.FONT_SCALE, "$scale")

  /** Window, transition and animator scales move together, like the usual developer-options shortcut. */
  fun animationScale(): Float = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

  fun setAnimationScale(scale: Float) = Shell.exec(
    "sh", "-c",
    listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)
      .joinToString("; ") { "settings put global $it $scale" },
  )

  /** Silent needs Do Not Disturb access (Grants: allow_dnd). */
  fun setSound(mode: SoundMode) {
    runCatching {
      audio.ringerMode = when (mode) {
        SoundMode.Ring -> AudioManager.RINGER_MODE_NORMAL
        SoundMode.Vibrate -> AudioManager.RINGER_MODE_VIBRATE
        SoundMode.Silent -> AudioManager.RINGER_MODE_SILENT
      }
    }.onFailure { Log.w(TAG, "ringer mode $mode failed", it) }
  }
}
