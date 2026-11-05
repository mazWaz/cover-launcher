package com.mazwas.covercam.ui.camera

import android.net.Uri

/** One-shot signals the screen may react to (no persistent state). */
sealed interface CameraEvent {
  data class PhotoSaved(val uri: Uri) : CameraEvent
  data class Error(val message: String) : CameraEvent
}
