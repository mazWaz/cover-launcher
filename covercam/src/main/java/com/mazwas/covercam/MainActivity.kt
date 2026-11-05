package com.mazwas.covercam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.mazwas.covercam.ui.camera.CameraAction
import com.mazwas.covercam.ui.camera.CameraRoot
import com.mazwas.covercam.ui.camera.CameraViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Thin host: window flags, permissions and hardware keys. Everything else - camera, sensors,
 * state, actions - lives in [CameraViewModel] and the Compose screen.
 */
class MainActivity : ComponentActivity() {

  private val viewModel: CameraViewModel by viewModel()

  /** Volume-down = shutter, volume-up = next mode (hardware control, no pixel hunting). */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.onAction(CameraAction.Capture)
        return true
      }
      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.onAction(CameraAction.NextMode)
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      if (granted[Manifest.permission.CAMERA] == true) viewModel.onAction(CameraAction.ProbeLenses)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { CameraRoot() }
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      viewModel.onAction(CameraAction.ProbeLenses)
    } else {
      requestPermissions.launch(
        arrayOf(
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.ACCESS_FINE_LOCATION,
        ),
      )
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.onAction(CameraAction.ReopenCamera)
  }
}
