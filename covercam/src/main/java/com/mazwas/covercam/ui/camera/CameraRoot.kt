package com.mazwas.covercam.ui.camera

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

private const val TAG = "CoverCamRoot"

/** Everything that needs the Activity happens here: permission dialog, back, other apps. */
@Composable
fun CameraRoot(viewModel: CameraViewModel = koinViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // The cover does not always show the dialog, and then there is no resume either: re-check from the result.
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    viewModel.onAction(CameraAction.ScreenResumed)
  }

  ObserveAsEvents(viewModel.events) { event ->
    when (event) {
      // INFO (not DEBUG): the event sink must be visible in a default logcat buffer, otherwise a
      // silently dropped event looks identical to a working one.
      is CameraEvent.PhotoSaved -> Log.i(TAG, "saved ${event.uri}")
      is CameraEvent.Error -> Log.w(TAG, event.message)
      CameraEvent.RequestPermission -> permissions.launch(
        arrayOf(
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO,
          // fine alone is ignored since Android 12: both go in one request
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
      CameraEvent.OpenAppSettings -> runCatching {
        context.startActivity(
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
      }.onFailure { Log.w(TAG, "app settings did not open", it) }
      is CameraEvent.Share -> runCatching {
        context.startActivity(
          Intent(Intent.ACTION_SEND).apply {
            type = event.mime
            putExtra(Intent.EXTRA_STREAM, event.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          },
        )
      }.onFailure { Log.w(TAG, "share did not open", it) }
      is CameraEvent.Play -> runCatching {
        context.startActivity(
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(event.uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          },
        )
      }.onFailure { Log.w(TAG, "no player opened", it) }
    }
  }

  // Back closes the panel on top first; only a bare camera lets it leave the app.
  BackHandler(
    enabled = state.viewer != null || state.galleryOpen || state.modesOpen || state.settingsOpen || state.calibrating,
  ) {
    viewModel.onAction(
      when {
        state.viewer != null -> CameraAction.CloseViewer
        state.galleryOpen -> CameraAction.CloseGallery
        state.modesOpen -> CameraAction.CloseModes
        state.settingsOpen -> CameraAction.CloseSettings
        else -> CameraAction.StopCalibration
      },
    )
  }

  CameraScreen(state = state, onAction = viewModel::onAction)
}

/** Collects one-shot events only while the screen is at least STARTED (same as the launcher's core/presentation). */
@Composable
private fun <T> ObserveAsEvents(flow: Flow<T>, onEvent: (T) -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner.lifecycle, flow) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      withContext(Dispatchers.Main.immediate) {
        flow.collect(onEvent)
      }
    }
  }
}
