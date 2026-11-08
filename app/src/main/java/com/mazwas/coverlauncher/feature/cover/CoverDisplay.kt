package com.mazwas.coverlauncher.feature.cover

import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.util.Log
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.ROTATE_AUTO
import com.mazwas.coverlauncher.core.system.ROTATE_LOCK
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.core.system.coverPrefs
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherSettings
import com.mazwas.coverlauncher.feature.launcher.domain.coverRotation

/**
 * Sets the cover up for the app in front: its own rotation ([coverRotation]) and density if it has them, else the rotate
 * tile and the cover-wide density. Only activities count as "in front": keyboards, panels and pop-ups leave it as it
 * is. Needs Shizuku. A density change is a configuration change for every app on the cover.
 *
 * Rotation is always a fixed `lock N` (apps' own orientation requests ignored, they get letterboxed). "Auto" follows the
 * orientation sensor here: Android's own auto-rotation never turned the cover (2026-11-08: the sensor reported 0/1/2 to
 * system_server while "free", the cover stayed put; its listener only re-arms on screen-on and refuses 180°).
 */
class CoverDisplay(
  private val context: Context,
  /** The focused window's app, for when no activity came to the front since a restart. */
  private val focused: () -> CharSequence?,
) : SensorEventListener {
  private var settings = LauncherSettings()
  private var front: String? = null
  private var appliedRotation: Int? = null
  private var appliedDensity: Int? = UNKNOWN
  private val activities = HashMap<String, Boolean>()
  private val sensors = context.getSystemService(SensorManager::class.java)
  // Samsung's "auto_rotation Screen Orientation Sensor": TYPE_DEVICE_ORIENTATION (27, hidden), on-change, already
  // debounced, 0..3; reports its current value as soon as it's registered
  private val orientation: Sensor? = sensors.getDefaultSensor(27)
  private var listening = false
  private var sensed = 0

  fun update(next: LauncherSettings) {
    settings = next
    apply()
  }

  /** A window changed on the cover. */
  fun onWindow(pkg: CharSequence?, cls: CharSequence?) {
    if (pkg == null || cls == null) return
    val isActivity = activities.getOrPut("$pkg/$cls") {
      runCatching { context.packageManager.getActivityInfo(ComponentName(pkg.toString(), cls.toString()), 0) }.isSuccess
    }
    if (!isActivity) return
    front = pkg.toString()
    apply()
  }

  /** Also when the tile, the settings or the sensor change. */
  fun apply() {
    val prefs = context.coverPrefs()
    val auto = prefs.getBoolean(ROTATE_AUTO, false)
    listen(auto)
    // after an update only the launcher's own screens may have changed since: no activity event came yet
    val app = front ?: focused()?.toString() ?: return
    rotate(coverRotation(app, settings.appRotation, auto, prefs.getInt(ROTATE_LOCK, 0), sensed))
    density(settings.appDensity[app] ?: settings.density)
  }

  fun stop() = listen(false)

  // shortcut: sensor value = cover rotation as seen on the phone (0 held normally, 2 upside down); if turning it
  // sideways shows the screen upside down, map 1 <-> 3 here
  override fun onSensorChanged(event: SensorEvent) {
    val value = event.values[0].toInt()
    Log.i(TAG, "orientation sensor $value")
    if (value !in 0..3) return
    sensed = value
    apply()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  private fun listen(on: Boolean) {
    if (on == listening || orientation == null) return
    listening = on
    if (!on) return sensors.unregisterListener(this)
    // until its first value lands, stay as the cover is (no blink through 0)
    sensed = context.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY)?.rotation ?: 0
    sensors.registerListener(this, orientation, SensorManager.SENSOR_DELAY_NORMAL)
  }

  private fun rotate(rotation: Int) {
    if (rotation == appliedRotation || !Shell.ready()) return
    appliedRotation = rotation
    Shell.exec(
      "sh", "-c",
      "cmd window fixed-to-user-rotation -d $COVER_DISPLAY enabled; cmd window user-rotation -d $COVER_DISPLAY lock $rotation",
    )
  }

  /** [dpi] null: the cover's own density. */
  private fun density(dpi: Int?) {
    if (dpi == appliedDensity || !Shell.ready()) return
    appliedDensity = dpi
    Shell.exec("wm", "density", dpi?.toString() ?: "reset", "-d", "$COVER_DISPLAY")
  }

  private companion object {
    const val UNKNOWN = -1 // nothing set yet: the first app in front always applies
  }
}
