package com.mazwas.coverlauncher.feature.launcher.presentation

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
import com.mazwas.coverlauncher.core.system.Shell
import com.mazwas.coverlauncher.core.system.unlockThen
import com.mazwas.coverlauncher.feature.cover.ShizukuAutoStart
import com.mazwas.coverlauncher.feature.launcher.data.CoverOpener
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku

/** The cover's home. CoverService brings it back whenever Samsung's cover home would show. */
class LauncherActivity : ComponentActivity() {
  private val opener: CoverOpener by inject()
  private val icons: IconLoader by inject()
  private val home = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true) // opened the moment the cover wakes, still locked
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars()) // our own status row replaces them
    }
    // Shizuku permission: ask once while its server is running
    if (Shizuku.pingBinder() && !Shell.ready() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(0)

    setContent {
      NothingTheme {
        CompositionLocalProvider(LocalIconLoader provides icons) {
          AppNavHost(home = home, onOpen = { target -> unlockThen { opener.open(target) } })
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (ShizukuAutoStart.claim(this)) {
      unlockThen { opener.open(OpenTarget.App(ShizukuAutoStart.PACKAGE, ShizukuAutoStart.MAIN)) }
    }
  }

  /** Home pressed / app closed: CoverService started us again. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    home.tryEmit(Unit)
  }
}
