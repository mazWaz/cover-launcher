package com.mazwas.covercam.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import com.mazwas.covercam.CameraEngine

/**
 * Everything the camera screen renders.
 *
 * The first block mirrors [CameraEngine.State] (hardware/domain truth); the second block exposes
 * the engine's persisted settings so the UI never reads preferences itself; the third block is
 * UI-only state that used to live in `remember` inside the activity.
 */
data class CameraUiState(
  // ---- engine state ----
  val ready: Boolean = false,
  val mode: CameraEngine.Mode = CameraEngine.Mode.PHOTO,
  val facingBack: Boolean = true,
  val lensLabel: String = "-",
  val lenses: List<String> = emptyList(),
  val lensIndex: Int = 0,
  val torch: Boolean = false,
  val timerSeconds: Int = 0,
  val recording: Boolean = false,
  val recordingPaused: Boolean = false,
  val busy: Boolean = false,
  val status: String = "",
  val iso: Int = 200,
  val isoRange: IntRange = 100..3200,
  val exposureNs: Long = 8_000_000L,
  val exposureRange: LongRange = 1_000_000L..1_000_000_000L,
  val focusDiopters: Float = 0f,
  val minFocus: Float = 0f,
  val ev: Int = 0,
  val evRange: IntRange = 0..0,
  val wbMode: Int = 1, // CaptureRequest.CONTROL_AWB_MODE_AUTO
  val manual: Boolean = false,
  val lastThumb: Uri? = null,
  val zoom: Float = 1f,
  val slowPreviewWidth: Int = 1280,
  val slowPreviewHeight: Int = 720,
  val slowPreviewFps: Int = 0,
  val previewRotationDegrees: Int = 0,
  val previewMirrored: Boolean = false,

  // ---- engine settings (preference-backed) ----
  val ratioLabel: String = "4:3",
  val quality: Int = 95,
  val soundOn: Boolean = true,
  val geotagOn: Boolean = true,
  val levelOn: Boolean = true,
  val dngOn: Boolean = false,
  val dngRotation: Int = 90,
  val imuRotationOn: Boolean = false,
  val video4k: Boolean = false,
  val video60: Boolean = false,
  val timelapseIntervalSec: Int = 2,
  val timelapseActive: Boolean = false,
  val capsInfo: String = "",
  val kelvin: Int = 5000,

  // ---- UI only ----
  val grid: Boolean = false,
  val histogram: Boolean = false,
  val peaking: Boolean = false,
  val settingsOpen: Boolean = false,
  val galleryOpen: Boolean = false,
  val viewerUri: Uri? = null,
  val galleryItems: List<Uri> = emptyList(),
  val countdown: Int = 0,
  val pitch: Float = 0f,
  val roll: Float = 0f,
  val histogramData: IntArray = EMPTY_HISTOGRAM,
  val peakMask: Bitmap? = null,
) {
  /** `abs(pitch) < 1.5f` is what makes the level line turn green. */
  val levelActive: Boolean get() = kotlin.math.abs(pitch) < 1.5f

  private companion object {
    val EMPTY_HISTOGRAM = IntArray(64)
  }
}
