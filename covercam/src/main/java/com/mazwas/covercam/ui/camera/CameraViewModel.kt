package com.mazwas.covercam.ui.camera

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.covercam.CameraEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Owns [CameraEngine] and turns user intents into engine calls.
 *
 * The engine keeps its camera logic untouched; this class only reads `engine.state` (a Compose
 * snapshot state) through `snapshotFlow`, and mirrors it into an immutable [CameraUiState]
 * together with the UI-only flags that used to live in `remember` inside the activity.
 */
class CameraViewModel(private val engine: CameraEngine) : ViewModel() {

  private val ctx: Context = engine.appContext

  private val _state = MutableStateFlow(snapshot())

  val state: StateFlow<CameraUiState> =
    _state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _state.value)

  private val _events = Channel<CameraEvent>(Channel.BUFFERED)
  val events: Flow<CameraEvent> = _events.receiveAsFlow()

  private val shutterSound = MediaActionSound()
  private var countdownJob: Job? = null
  private var analysisJob: Job? = null
  private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  /** Last thumbnail we reported, so "PhotoSaved" fires once per shot. */
  private var reportedThumb: Uri? = null

  init {
    viewModelScope.launch {
      snapshotFlow { engine.state }.collect { refresh() }
    }
    startSensors()
  }

  fun onAction(action: CameraAction) {
    when (action) {
      is CameraAction.SetMode -> engine.setMode(action.mode)
      CameraAction.NextMode -> engine.cycleMode()
      CameraAction.Capture -> requestCapture()
      CameraAction.ToggleRecording -> toggleRecording()
      CameraAction.ToggleTimelapse -> engine.toggleTimelapse()
      CameraAction.ToggleSlowMotion -> toggleSlowMotion()
      CameraAction.ToggleHdrVideo -> toggleHdrVideo()
      CameraAction.StopRecording -> stopRecording()

      is CameraAction.SelectLens -> engine.switchLens(action.delta)
      is CameraAction.SetZoom -> engine.setZoom(action.value)
      is CameraAction.TapFocus -> engine.tapFocus(action.x, action.y)
      CameraAction.ToggleLock -> engine.toggleLock()
      CameraAction.ToggleTorch -> engine.toggleTorch()
      CameraAction.SwitchCamera -> engine.switchCamera()
      CameraAction.BumpRotation -> engine.bumpRotation()
      CameraAction.ToggleMirror -> engine.toggleMirror()

      CameraAction.CycleTimer -> engine.cycleTimer()
      CameraAction.CycleRatio -> engine.cycleRatio()
      is CameraAction.SetGrid -> _state.update { it.copy(grid = action.enabled) }
      is CameraAction.SetHistogram -> {
        _state.update { it.copy(histogram = action.enabled) }
        restartAnalysis()
      }
      is CameraAction.SetPeaking -> {
        _state.update { it.copy(peaking = action.enabled) }
        restartAnalysis()
      }

      is CameraAction.SetIso -> engine.setIso(action.iso)
      is CameraAction.SetShutter -> engine.setExposure(action.nanos)
      is CameraAction.SetFocus -> engine.setFocus(action.diopters)
      is CameraAction.SetEv -> engine.setEv(action.ev)
      is CameraAction.SetWb -> engine.setWb(action.mode)
      is CameraAction.SetKelvin -> engine.applyKelvin(action.kelvin)

      CameraAction.OpenSettings -> _state.update { it.copy(settingsOpen = true) }
      CameraAction.CloseSettings -> _state.update { it.copy(settingsOpen = false) }
      is CameraAction.SetQuality -> engine.quality = action.quality
      CameraAction.ToggleShutterSound -> engine.soundOn = !engine.soundOn
      CameraAction.ToggleGeotag -> engine.geotagOn = !engine.geotagOn
      CameraAction.ToggleLevel -> engine.levelOn = !engine.levelOn
      CameraAction.ToggleDng -> engine.dngOn = !engine.dngOn
      CameraAction.CycleDngRotation -> engine.cycleDngRotation()
      CameraAction.ToggleImuRotation -> engine.imuRotationOn = !engine.imuRotationOn
      CameraAction.CycleVideoRes -> engine.cycleVideoRes()
      CameraAction.CycleVideoFps -> engine.cycleVideoFps()
      CameraAction.CycleTimelapseInterval -> engine.cycleTimelapseInterval()
      CameraAction.ResetCalibration -> engine.resetCalibration()

      CameraAction.OpenGallery -> {
        _state.update { it.copy(galleryOpen = true, galleryItems = queryMedia()) }
      }
      CameraAction.CloseGallery -> _state.update { it.copy(galleryOpen = false) }
      is CameraAction.OpenViewer -> _state.update { it.copy(viewerUri = action.uri) }
      CameraAction.CloseViewer -> _state.update { it.copy(viewerUri = null) }
      is CameraAction.DeletePhoto -> deletePhoto(action.uri)

      is CameraAction.AttachPreview -> engine.attach(action.view)
      is CameraAction.AttachSlowPreview -> engine.setSlowPreviewSurface(action.surface)
      CameraAction.ProbeLenses -> engine.probeLenses()
      CameraAction.ReopenCamera -> engine.reopenIfNeeded()
      is CameraAction.SetDeviceRotation -> engine.setDeviceRotation(action.degrees)
      CameraAction.ClearDeviceRotation -> engine.clearDeviceRotation()
    }
    refresh()
  }

  // ---------------------------------------------------------------- capture and recording

  /** Volume-down / shutter: honours the self-timer exactly like the activity used to. */
  private fun requestCapture() {
    val timer = engine.state.timerSeconds
    if (timer > 0) {
      countdownJob?.cancel()
      _state.update { it.copy(countdown = timer) }
      countdownJob = viewModelScope.launch {
        delay(timer * 1000L)
        _state.update { it.copy(countdown = 0) }
        doCapture()
      }
    } else {
      doCapture()
    }
  }

  private fun doCapture() {
    runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
    if (engine.dngOn) engine.stashPreview()
    engine.capture()
  }

  private fun toggleRecording() {
    when (engine.state.mode) {
      CameraEngine.Mode.TIMELAPSE -> engine.toggleTimelapse()
      CameraEngine.Mode.SLOWMO -> if (engine.state.recording) engine.stopRecording() else engine.startSlowMotion()
      CameraEngine.Mode.HDRVID -> if (engine.state.recording) engine.stopHdrVideo() else engine.startHdrVideo()
      CameraEngine.Mode.VIDEO -> if (engine.state.recording) {
        engine.pauseOrResumeRecording()
      } else {
        engine.startRecording()
      }
      else -> Unit
    }
  }

  /** Long-press on the record button: stop whatever the current mode is recording. */
  private fun stopRecording() {
    if (!engine.state.recording) return
    when (engine.state.mode) {
      CameraEngine.Mode.HDRVID -> engine.stopHdrVideo()
      else -> engine.stopRecording()
    }
  }

  private fun toggleSlowMotion() {
    if (engine.state.recording) engine.stopRecording() else engine.startSlowMotion()
  }

  private fun toggleHdrVideo() {
    if (engine.state.recording) engine.stopHdrVideo() else engine.startHdrVideo()
  }

  // ---------------------------------------------------------------- gallery

  private fun deletePhoto(uri: Uri) {
    runCatching { ctx.contentResolver.delete(uri, null, null) }
      .onFailure { Log.w(TAG, "delete failed", it) }
    _state.update { it.copy(viewerUri = null, galleryItems = queryMedia()) }
  }

  private fun queryMedia(): List<Uri> {
    val out = ArrayList<Uri>()
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("DCIM/CoverCam%")
    for (collection in listOf(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )) {
      runCatching {
        ctx.contentResolver.query(
          collection,
          projection,
          selection,
          args,
          "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
          while (c.moveToNext() && out.size < 60) {
            out.add(ContentUris.withAppendedId(collection, c.getLong(0)))
          }
        }
      }.onFailure { Log.w(TAG, "gallery query failed", it) }
    }
    return out
  }

  // ---------------------------------------------------------------- focus peaking / histogram

  private fun restartAnalysis() {
    analysisJob?.cancel()
    val s = _state.value
    if (!s.peaking && !s.histogram) {
      _state.update { if (it.peaking) it else it.copy(peakMask = null) }
      return
    }
    analysisJob = viewModelScope.launch {
      while (isActive) {
        val frame = engine.previewBitmap(128, 96)
        if (frame != null) {
          val (hist, mask) = analyzeFrame(frame, s.histogram, s.peaking)
          frame.recycle()
          _state.update { it.copy(histogramData = hist, peakMask = mask) }
        }
        delay(450)
      }
    }
  }

  // ---------------------------------------------------------------- sensors (level + rotation)

  private var pendingRotation = -1
  private var pendingSince = 0L
  private var committedRotation = -1

  private val gravityListener = object : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {
      val x = e.values[0]
      val y = e.values[1]
      val z = e.values[2]
      if (abs(x) > 0.01f || abs(y) > 0.01f) {
        val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(-z.toDouble(), sqrt((x * x + y * y).toDouble()))).toFloat()
        _state.update { it.copy(roll = roll, pitch = pitch) }
      }
      if (abs(z) > 6f) {
        // Lying flat: no reliable "up" edge, hand control back to the display rotation.
        pendingRotation = -1
        committedRotation = -1
        engine.clearDeviceRotation()
        return
      }
      val quadrant = when {
        y < -6f -> 0
        x < -6f -> 90
        y > 6f -> 180
        x > 6f -> 270
        else -> -1
      }
      if (quadrant < 0) return
      val now = System.currentTimeMillis()
      if (quadrant != pendingRotation) {
        pendingRotation = quadrant
        pendingSince = now
        return
      }
      if (pendingRotation != committedRotation && now - pendingSince > 300) {
        committedRotation = pendingRotation
        engine.setDeviceRotation(committedRotation)
      }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  private val orientationListener = object : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {
      val quadrant = e.values.firstOrNull()?.toInt() ?: return
      engine.setDeviceRotation(((quadrant % 4) + 4) % 4 * 90)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  private fun startSensors() {
    val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val orientation = sensorManager.getDefaultSensor(27) // android.sensor.device_orientation
    if (accel != null) sensorManager.registerListener(gravityListener, accel, SensorManager.SENSOR_DELAY_UI)
    if (orientation != null) {
      sensorManager.registerListener(orientationListener, orientation, SensorManager.SENSOR_DELAY_UI)
    }
  }
  // ---------------------------------------------------------------- state plumbing

  /**
   * First snapshot. Starts from [CameraUiState]'s defaults: reading `_state` here would touch the
   * field while it is still being initialised.
   */
  private fun snapshot(): CameraUiState = CameraUiState().merge(engine)

  private fun refresh() {
    val engineState = engine.state
    _state.update { it.merge(engine) }
    val thumb = engineState.lastThumb
    if (thumb != null && thumb != reportedThumb) {
      reportedThumb = thumb
      _events.trySend(CameraEvent.PhotoSaved(thumb))
    }
  }

  override fun onCleared() {
    countdownJob?.cancel()
    analysisJob?.cancel()
    runCatching { sensorManager.unregisterListener(gravityListener) }
    runCatching { sensorManager.unregisterListener(orientationListener) }
    runCatching { shutterSound.release() }
    engine.close()
    super.onCleared()
  }

  companion object {
    private const val TAG = "CoverCamVm"
  }
}

/** Copies the engine's truth onto the immutable UI state, leaving UI-only fields untouched. */
private fun CameraUiState.merge(engine: CameraEngine): CameraUiState {
  val e = engine.state
  return copy(
    ready = e.ready,
    mode = e.mode,
    facingBack = e.facingBack,
    lensLabel = e.lensLabel,
    lenses = e.lenses,
    lensIndex = e.lensIndex,
    torch = e.torch,
    timerSeconds = e.timerSeconds,
    recording = e.recording,
    recordingPaused = e.recordingPaused,
    busy = e.busy,
    status = e.status,
    iso = e.iso,
    isoRange = e.isoRange,
    exposureNs = e.exposureNs,
    exposureRange = e.exposureRange,
    focusDiopters = e.focusDiopters,
    minFocus = e.minFocus,
    ev = e.ev,
    evRange = e.evRange,
    wbMode = e.wbMode,
    manual = e.manual,
    lastThumb = e.lastThumb,
    zoom = engine.currentZoom(),
    previewRotationDegrees = engine.previewRotationDegrees(),
    previewMirrored = engine.previewMirrored(),
    ratioLabel = engine.ratio.label,
    quality = engine.quality,
    soundOn = engine.soundOn,
    geotagOn = engine.geotagOn,
    levelOn = engine.levelOn,
    dngOn = engine.dngOn,
    dngRotation = engine.dngRotation,
    imuRotationOn = engine.imuRotationOn,
    video4k = engine.video4k,
    video60 = engine.video60,
    timelapseIntervalSec = engine.timelapseIntervalSec,
    timelapseActive = engine.timelapseActive,
    capsInfo = engine.capsInfo(),
    kelvin = engine.kelvin,
  )
}

/** Luminance histogram + Sobel edge mask for focus peaking. */
internal fun analyzeFrame(bmp: Bitmap, wantHist: Boolean, wantPeak: Boolean): Pair<IntArray, Bitmap?> {
  val w = bmp.width
  val h = bmp.height
  val px = IntArray(w * h)
  bmp.getPixels(px, 0, w, 0, 0, w, h)
  val gray = IntArray(w * h)
  val hist = IntArray(64)
  for (i in px.indices) {
    val c = px[i]
    val g = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    gray[i] = g
    if (wantHist) hist[(g * 63) / 255]++
  }
  var mask: Bitmap? = null
  if (wantPeak) {
    val out = IntArray(w * h)
    for (y in 1 until h - 1) {
      for (x in 1 until w - 1) {
        val i = y * w + x
        val gx = gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1] -
          gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1]
        val gy = gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1] -
          gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1]
        if (abs(gx) + abs(gy) > 140) out[i] = 0xFF39FF14.toInt()
      }
    }
    mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    mask.setPixels(out, 0, w, 0, 0, w, h)
  }
  return hist to mask
}
