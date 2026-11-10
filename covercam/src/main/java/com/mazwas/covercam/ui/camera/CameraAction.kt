package com.mazwas.covercam.ui.camera

import android.view.Surface
import android.view.TextureView
import com.mazwas.covercam.CameraEngine

/** Every intent the camera screen can raise. All engine calls live in the ViewModel. */
sealed interface CameraAction {

  // modes and capture
  data class SetMode(val mode: CameraEngine.Mode) : CameraAction
  data object NextMode : CameraAction
  data object PrevMode : CameraAction
  data object OpenModes : CameraAction
  data object CloseModes : CameraAction

  /** Shutter button, record button and volume-down: a photo in the photo modes, start/stop in the recording ones. */
  data object Capture : CameraAction
  data object TogglePause : CameraAction

  // optics
  data class SelectLens(val delta: Int) : CameraAction
  data class SetZoom(val value: Float) : CameraAction
  data class TapFocus(val x: Float, val y: Float) : CameraAction
  data object ToggleLock : CameraAction
  data object ToggleTorch : CameraAction
  data object SwitchCamera : CameraAction
  data object BumpRotation : CameraAction
  data object ToggleMirror : CameraAction

  // quick toggles
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

  /** Exposure and focus back to automatic. */
  data object SetAuto : CameraAction

  // settings panel
  data object OpenSettings : CameraAction
  data object CloseSettings : CameraAction
  data class SetQuality(val quality: Int) : CameraAction
  data object ToggleShutterSound : CameraAction
  data object ToggleGeotag : CameraAction
  data object ToggleLevel : CameraAction
  data object ToggleDng : CameraAction
  data object ToggleLogVideo : CameraAction
  data object ToggleImuRotation : CameraAction
  data object CycleVideoRes : CameraAction
  data object CycleVideoFps : CameraAction
  data object CycleTimelapseInterval : CameraAction
  data object ResetCalibration : CameraAction

  /** Leaves Settings and puts the turn / mirror buttons over the live preview. */
  data object StartCalibration : CameraAction
  data object StopCalibration : CameraAction

  // gallery / viewer
  data object OpenGallery : CameraAction
  data object CloseGallery : CameraAction
  data class OpenViewer(val item: GalleryItem) : CameraAction
  data object CloseViewer : CameraAction
  data class DeleteMedia(val item: GalleryItem) : CameraAction
  data class ShareMedia(val item: GalleryItem) : CameraAction
  data class PlayMedia(val item: GalleryItem) : CameraAction

  // permission
  data object RequestPermission : CameraAction
  data object OpenAppSettings : CameraAction

  // wiring that the platform delivers to us (not user intents, but the only seam Compose has)
  data class AttachPreview(val view: TextureView) : CameraAction
  data class AttachSlowPreview(val surface: Surface?) : CameraAction
  data object ScreenResumed : CameraAction
  data object ScreenStopped : CameraAction
}
