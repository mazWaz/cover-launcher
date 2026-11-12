package com.mazwas.covercam.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import com.mazwas.covercam.CameraEngine

/** One file of DCIM/CoverCam. */
data class GalleryItem(val uri: Uri, val isVideo: Boolean)

/**
 * Everything the camera screen renders.
 *
 * The first block mirrors [CameraEngine.State] (hardware/domain truth); the second block exposes
 * the engine's persisted settings so the UI never reads preferences itself; the third block is
 * UI-only state.
 */
data class CameraUiState(
  // ---- engine state ----
  val cameraGranted: Boolean = true,
  val ready: Boolean = false,
  val mode: CameraEngine.Mode = CameraEngine.Mode.PHOTO,
  val facingBack: Boolean = true,
  /** Lens chips of the current side, already labelled ("0.5x", "1.0x"). */
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
  /** ISO and shutter are hand-set; false = auto exposure. */
  val manual: Boolean = false,
  val manualFocus: Boolean = false,
  val locked: Boolean = false,
  val lastThumb: Uri? = null,
  val zoom: Float = 1f,
  val slowPreview: Boolean = false,
  val slowPreviewWidth: Int = 1280,
  val slowPreviewHeight: Int = 720,
  val previewRotationDegrees: Int = 0,
  val previewMirrored: Boolean = false,
  val previewScaleX: Float = 1f,
  val previewScaleY: Float = 1f,
  val pictureAspect: Float = 4f / 3f,

  // ---- engine settings (preference-backed) ----
  /** The shape of what the next shot stores, which is also the shape of the preview. */
  val ratioLabel: String = "4:3",
  val quality: Int = 95,
  val soundOn: Boolean = true,
  val geotagOn: Boolean = true,
  val levelOn: Boolean = true,
  val dngOn: Boolean = false,
  val logVideo: Boolean = false,
  val imuRotationOn: Boolean = false,
  val video4k: Boolean = false,
  val video60: Boolean = false,
  val video60Available: Boolean = false,
  val hdr60: Boolean = false,
  /** Slow motion's frame rate, and how many the open shape offers (1 = nothing to switch to). */
  val slowFps: Int = 240,
  val slowFpsChoices: Int = 2,
  val timelapseIntervalSec: Int = 2,
  val timelapseActive: Boolean = false,
  val capsInfo: String = "",
  val kelvin: Int = 5000,

  // ---- UI only ----
  val grid: Boolean = false,
  val histogram: Boolean = false,
  val peaking: Boolean = false,
  val settingsOpen: Boolean = false,
  val modesOpen: Boolean = false,
  /** Preview calibration is on: its turn / mirror buttons lie over the picture. */
  val calibrating: Boolean = false,
  val galleryOpen: Boolean = false,
  val viewer: GalleryItem? = null,
  val galleryItems: List<GalleryItem> = emptyList(),
  val countdown: Int = 0,
  val recordingSeconds: Int = 0,
  /** Tilt of the horizon in degrees, in half-degree steps; null while the phone lies flat and has no horizon. */
  val roll: Float? = null,
  val histogramData: IntArray = EMPTY_HISTOGRAM,
  val peakMask: Bitmap? = null,
) {
  private companion object {
    val EMPTY_HISTOGRAM = IntArray(64)
  }
}
