package com.mazwas.covercam.ui.camera

import android.net.Uri

/** One-shot signals: logs, and everything that needs the Activity (permission dialog, other apps). */
sealed interface CameraEvent {
  data class PhotoSaved(val uri: Uri) : CameraEvent
  data class Error(val message: String) : CameraEvent
  data object RequestPermission : CameraEvent
  data object OpenAppSettings : CameraEvent
  data class Share(val uri: Uri, val mime: String) : CameraEvent
  data class Play(val uri: Uri) : CameraEvent
}
