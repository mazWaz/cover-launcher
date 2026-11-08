package com.mazwas.coverlauncher.feature.cover

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mazwas.coverlauncher.core.system.Clock
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
import com.mazwas.coverlauncher.feature.launcher.presentation.AodScreen
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherState
import kotlinx.coroutines.flow.combine

/**
 * Our AOD: while the cover dozes, a black full-screen overlay with [AodScreen] on top of Samsung's own AOD. Samsung's
 * AOD has to be on and always shown (`system aod_mode 1`, `aod_tap_to_show_mode 0`): without it the cover goes fully
 * dark instead of dozing and nothing shows. Not touchable: double-tap to wake stays Samsung's.
 */
class CoverAod(
  private val service: Context,
  private val icons: IconLoader,
  private val clock: Clock,
  system: SystemStatusSource,
  notifications: NotificationStore,
) {
  private var view: View? = null
  private val state = combine(clock.ticks, system.status, notifications.notifications) { now, status, notes ->
    LauncherState(time = clock.time(now), date = clock.date(now), status = status, notifications = notes)
  }

  fun show() {
    if (view != null) return
    val params = LayoutParams(
      LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.OPAQUE,
    ).apply { layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    view = addCoverOverlay(service, icons, params) {
      val now by state.collectAsState(null)
      now?.let { AodScreen(it) }
    }
  }

  fun hide() {
    view?.removeCoverOverlay()
    view = null
  }
}
