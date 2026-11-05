package com.mazwas.covercam.ui.camera

import android.net.Uri
import android.view.Surface
import android.view.TextureView
import com.mazwas.covercam.CameraEngine

/** Every intent the camera screen can raise. All engine calls live in the ViewModel. */
sealed interface CameraAction {

  // modes and capture
  data class SetMode(val mode: CameraEngine.Mode) : CameraAction
  data object NextMode : CameraAction
  data object Capture : CameraAction
  data object ToggleRecording : CameraAction
  data object ToggleTimelapse : CameraAction
  data object ToggleSlowMotion : CameraAction
  data object ToggleHdrVideo : CameraAction
  data object StopRecording : CameraAction

  // optics
  data class SelectLens(val delta: Int) : CameraAction
  data class SetZoom(val value: Float) : CameraAction
  data class TapFocus(val x: Float, val y: Float) : CameraAction
  data object ToggleLock : CameraAction
  data object ToggleTorch : CameraAction
  data object SwitchCamera : CameraAction
  data object BumpRotation : CameraAction
  data object ToggleMirror : CameraAction

  // top bar / quick toggles
  data object CycleTimer : CameraAction
  data object CycleRatio : CameraAction
  data class SetGrid(val enabled: Boolean) : CameraAction
  data class SetHistogram(val enabled: Boolean) : CameraAction
  data class SetPeaking(val enabled: Boolean) : CameraAction

  // pro panel
  data class SetIso(val iso: Int) : CameraAction
  data class SetShutter(val nanos: Long) : CameraAction
  data class SetFocus(val diopters: Float) : CameraAction
  data class SetEv(val ev: Int) : CameraAction
  data class SetWb(val mode: Int) : CameraAction
  data class SetKelvin(val kelvin: Int) : CameraAction

  // settings panel
  data object OpenSettings : CameraAction
  data object CloseSettings : CameraAction
  data class SetQuality(val quality: Int) : CameraAction
  data object ToggleShutterSound : CameraAction
  data object ToggleGeotag : CameraAction
  data object ToggleLevel : CameraAction
  data object ToggleDng : CameraAction
  data object CycleDngRotation : CameraAction
  data object ToggleImuRotation : CameraAction
  data object CycleVideoRes : CameraAction
  data object CycleVideoFps : CameraAction
  data object CycleTimelapseInterval : CameraAction
  data object ResetCalibration : CameraAction

  // gallery / viewer
  data object OpenGallery : CameraAction
  data object CloseGallery : CameraAction
  data class OpenViewer(val uri: Uri) : CameraAction
  data object CloseViewer : CameraAction
  data class DeletePhoto(val uri: Uri) : CameraAction

  // wiring that the platform delivers to us (not user intents, but the only seam Compose has)
  data class AttachPreview(val view: TextureView) : CameraAction
  data class AttachSlowPreview(val surface: Surface?) : CameraAction
  data object ProbeLenses : CameraAction
  data object ReopenCamera : CameraAction
  data class SetDeviceRotation(val degrees: Int) : CameraAction
  data object ClearDeviceRotation : CameraAction
}
