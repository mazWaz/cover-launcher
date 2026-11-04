package com.mazwas.coverlauncher.feature.cover

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * Shizuku's own start-on-boot fails on One UI 9 (finds wireless debugging, never connects); its Start button works.
 * Once per boot the launcher opens Shizuku, CoverService presses Start and brings the launcher back when Shizuku's
 * main screen returns (= server up). Coming back to the foreground is also what makes Shizuku send us its binder.
 */
object ShizukuAutoStart {
  const val PACKAGE = "moe.shizuku.privileged.api"
  const val MAIN = "moe.shizuku.manager.MainActivity"
  const val STARTER = "moe.shizuku.manager.starter.StarterActivity"

  // Shizuku's own boot attempt is over ~65 s after boot
  private const val AFTER_BOOT_MS = 90_000L

  /** Opened → (main shown) Pressing → (Start clicked) Pressed → (starter shown) Starting → (main back) Idle. */
  enum class Step { Idle, Opened, Pressing, Pressed, Starting }

  @Volatile
  var step = Step.Idle

  /** True once per boot when Shizuku had its chance and we still have no binder. Marks this boot as tried. */
  fun claim(context: Context): Boolean {
    if (Shizuku.pingBinder() || SystemClock.elapsedRealtime() < AFTER_BOOT_MS) return false
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    val prefs = context.getSharedPreferences("shizuku_autostart", Context.MODE_PRIVATE)
    if (prefs.getInt("boot", -1) == boot) return false
    prefs.edit().putInt("boot", boot).apply()
    step = Step.Opened
    return true
  }
}
