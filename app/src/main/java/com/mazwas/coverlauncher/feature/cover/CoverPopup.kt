package com.mazwas.coverlauncher.feature.cover

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager.LayoutParams
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.core.designsystem.cutoutSides
import com.mazwas.coverlauncher.core.system.coverOptions
import com.mazwas.coverlauncher.feature.launcher.data.CoverOpener
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherAction
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherActivity
import com.mazwas.coverlauncher.feature.launcher.presentation.NotificationRow
import com.mazwas.coverlauncher.feature.launcher.presentation.verticalSwipe

/**
 * The launcher's heads-up: the newest alerting notification as the panel's card at the top of the cover, over any app
 * and the lock screen, for [SHOW_MS]. Tap opens it, its buttons work, sideways dismisses it, up only hides the pop-up.
 * An accessibility overlay of CoverService, like the keep-awake window.
 */
class CoverPopup(
  private val service: Context,
  private val icons: IconLoader,
  private val notifications: NotificationStore,
  private val opener: CoverOpener,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val note = mutableStateOf<LauncherNotification?>(null)
  private var view: View? = null
  private val autoHide = Runnable { hide() }

  /** A newer notification replaces the one showing and restarts the timer. */
  fun show(next: LauncherNotification) {
    note.value = next
    handler.removeCallbacks(autoHide)
    handler.postDelayed(autoHide, SHOW_MS)
    val v = view ?: attach() ?: return
    v.animate().cancel() // fading out: come back instead
    v.animate().alpha(1f).setDuration(200).setInterpolator(EASE_OUT)
  }

  fun hide() {
    handler.removeCallbacks(autoHide)
    val v = view ?: return
    v.animate().cancel()
    // runs only if the fade isn't cancelled by a show() meanwhile
    v.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT).withEndAction {
      v.removeCoverOverlay()
      if (view === v) view = null
    }
  }

  private fun attach(): View? {
    // not focusable: touches outside the card go to the app below
    val params = LayoutParams(
      LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP }
    return addCoverOverlay(service, icons, params) {
      // keyed: a new notification starts unswiped
      note.value?.let { n ->
        key(n) {
          NotificationRow(
            n, System.currentTimeMillis(), ::onAction,
            Modifier.cutoutSides().padding(start = 12.dp, end = 12.dp, top = 8.dp).verticalSwipe(onUp = ::hide),
          )
        }
      }
    }?.also {
      it.alpha = 0f // faded in by show()
      view = it
    }
  }

  private fun onAction(action: LauncherAction) {
    hide()
    when (action) {
      is LauncherAction.OpenNotification -> open(action.key, -1)
      // the pop-up can't take typing (not focusable): Reply opens the launcher's reply box
      is LauncherAction.NotificationAction -> if (note.value?.let { it.key == action.key && it.reply == action.index } == true) {
        service.startActivity(LauncherActivity.reply(service, action.key), coverOptions())
      } else open(action.key, action.index)
      is LauncherAction.Dismiss -> notifications.dismiss(action.key)
      else -> Unit
    }
  }

  /** Locked cover: the launcher unlocks first (that needs an activity). Otherwise straight away, over the app in front. */
  private fun open(key: String, action: Int) {
    if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
      service.startActivity(LauncherActivity.openNotification(service, key, action), coverOptions())
    } else {
      opener.open(if (action < 0) OpenTarget.Notification(key) else OpenTarget.NotificationAction(key, action))
    }
  }

  private companion object {
    const val SHOW_MS = 5_000L
    val EASE_OUT = DecelerateInterpolator()
  }
}
