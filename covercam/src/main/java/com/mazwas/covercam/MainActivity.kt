package com.mazwas.covercam

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mazwas.covercam.ui.camera.CameraAction
import com.mazwas.covercam.ui.camera.CameraRoot
import com.mazwas.covercam.ui.camera.CameraViewModel
import com.mazwas.covercam.ui.theme.CoverCamTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Thin host: window flags, lifecycle and hardware keys. Everything else - camera, sensors,
 * state, actions, permissions - lives in [CameraViewModel] and the Compose screen.
 */
class MainActivity : ComponentActivity() {

  private val viewModel: CameraViewModel by viewModel()

  /** Volume-down = shutter, volume-up = next mode (hardware control, no pixel hunting). */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    val action = when (keyCode) {
      KeyEvent.KEYCODE_VOLUME_DOWN -> CameraAction.Capture
      KeyEvent.KEYCODE_VOLUME_UP -> CameraAction.NextMode
      else -> return super.onKeyDown(keyCode, event)
    }
    // a held key repeats: it is still one press, not a burst
    if (event?.repeatCount == 0) viewModel.onAction(action)
    return true
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // the UI is always dark, so the bars are too
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { CoverCamTheme { CameraRoot() } }
  }

  override fun onResume() {
    super.onResume()
    viewModel.onAction(CameraAction.ScreenResumed)
  }

  /** Stopped = not visible: the take is finished and the camera released, instead of dying with the screen. */
  override fun onStop() {
    viewModel.onAction(CameraAction.ScreenStopped)
    super.onStop()
  }
}
