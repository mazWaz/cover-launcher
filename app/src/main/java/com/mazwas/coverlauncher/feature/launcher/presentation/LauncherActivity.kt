package com.mazwas.coverlauncher.feature.launcher.presentation

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.navigation.AppNavHost
import com.mazwas.coverlauncher.core.system.unlockThen
import com.mazwas.coverlauncher.feature.cover.ShizukuAutoStart
import com.mazwas.coverlauncher.feature.launcher.data.CoverOpener
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

/** The cover's home. CoverService brings it back whenever Samsung's cover home would show. */
class LauncherActivity : ComponentActivity() {
  private val opener: CoverOpener by inject()
  private val icons: IconLoader by inject()
  private val home = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  private val reply = MutableStateFlow<String?>(null) // a notification key from the pop-up's Reply

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars()) // our own status row replaces them
    }
    setContent {
      NothingTheme {
        CompositionLocalProvider(LocalIconLoader provides icons) {
          AppNavHost(home = home, reply = reply, onOpen = { target -> unlockThen { opener.open(target) } })
        }
      }
    }
    if (savedInstanceState == null) openFromPopup(intent)
  }

  override fun onStart() {
    super.onStart()
    // Swipe-only lock: show over it the moment the cover wakes. With a PIN, pattern or password the launcher would put
    // alerts and toggles in front of it (seen 2026-11-08): leave the lock to Samsung, CoverService brings us back after.
    setShowWhenLocked(!getSystemService(KeyguardManager::class.java).isDeviceSecure)
  }

  override fun onResume() {
    super.onResume()
    // Shizuku's binder reaches a just-started process a moment later: decide only once it had time to arrive,
    // and only if we're still in front.
    window.decorView.postDelayed({
      if (hasWindowFocus() && ShizukuAutoStart.claim(this)) {
        unlockThen { opener.open(OpenTarget.App(ShizukuAutoStart.PACKAGE, ShizukuAutoStart.MAIN)) }
      }
    }, 5_000)
  }

  /** Home pressed / app closed: CoverService started us again. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    home.tryEmit(Unit)
    openFromPopup(intent)
  }

  /** The pop-up hands over its tap when the cover is locked: unlocking needs an activity. */
  private fun openFromPopup(intent: Intent) {
    val key = intent.getStringExtra(OPEN_KEY) ?: return
    if (intent.getBooleanExtra(REPLY, false)) return unlockThen { reply.value = key }
    val action = intent.getIntExtra(OPEN_ACTION, -1)
    unlockThen { opener.open(if (action < 0) OpenTarget.Notification(key) else OpenTarget.NotificationAction(key, action)) }
  }

  companion object {
    private const val OPEN_KEY = "open_notification"
    private const val OPEN_ACTION = "open_action"
    private const val REPLY = "reply"

    /** Opens notification [key] (or presses its button [action]) after unlocking. Start it with coverOptions(). */
    fun openNotification(context: Context, key: String, action: Int = -1): Intent =
      Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(OPEN_KEY, key).putExtra(OPEN_ACTION, action)

    /** The reply box for notification [key], after unlocking: typing needs our activity. Start it with coverOptions(). */
    fun reply(context: Context, key: String): Intent =
      Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(OPEN_KEY, key).putExtra(REPLY, true)
  }
}
