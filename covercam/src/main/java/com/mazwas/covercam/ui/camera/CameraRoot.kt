package com.mazwas.covercam.ui.camera

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel

@Composable
fun CameraRoot(viewModel: CameraViewModel = koinViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ObserveAsEvents(viewModel.events) { event ->
    when (event) {
      // INFO (not DEBUG): the event sink must be visible in a default logcat buffer, otherwise a
      // silently dropped event looks identical to a working one.
      is CameraEvent.PhotoSaved -> Log.i("CoverCamRoot", "saved ${event.uri}")
      is CameraEvent.Error -> Log.w("CoverCamRoot", event.message)
    }
  }
  CameraScreen(state = state, onAction = viewModel::onAction)
}

/** Collects one-shot events only while the screen is at least STARTED. */
@Composable
private fun <T> ObserveAsEvents(events: Flow<T>, onEvent: (T) -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(events, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      events.collect { onEvent(it) }
    }
  }
}
