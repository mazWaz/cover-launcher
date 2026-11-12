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
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mazwas.covercam.CameraEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Owns [CameraEngine] and turns user intents into engine calls.
 *
 * The engine keeps its camera logic; this class reads `engine.state` (a Compose snapshot state) through
 * `snapshotFlow` and mirrors it into an immutable [CameraUiState] together with the UI-only flags.
 */
class CameraViewModel(private val engine: CameraEngine) : ViewModel() {

  private val ctx: Context = engine.appContext

  private val _state = MutableStateFlow(CameraUiState().merge(engine))
  val state: StateFlow<CameraUiState> = _state.asStateFlow()

  private val _events = Channel<CameraEvent>(Channel.BUFFERED)
  val events: Flow<CameraEvent> = _events.receiveAsFlow()

  private val shutterSound = MediaActionSound()
  private var countdownJob: Job? = null
  private var analysisJob: Job? = null
  private var recordClockJob: Job? = null
  private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private var sensorsOn = false
  private var askedPermission = false

  /** Last thumbnail we reported, so "PhotoSaved" fires once per shot. */
  private var reportedThumb: Uri? = null

  init {
    viewModelScope.launch {
      snapshotFlow { engine.state }.collect { refresh() }
    }
  }

  fun onAction(action: CameraAction) {
    when (action) {
      is CameraAction.SetMode -> {
        if (!taking()) engine.setMode(action.mode)
        _state.update { it.copy(modesOpen = false) }
      }
      // a stray swipe or key must not end a take: stop it first, then change mode
      CameraAction.NextMode -> if (!taking()) engine.cycleMode(1)
      CameraAction.PrevMode -> if (!taking()) engine.cycleMode(-1)
      CameraAction.OpenModes -> _state.update { it.copy(modesOpen = true) }
      CameraAction.CloseModes -> _state.update { it.copy(modesOpen = false) }
      CameraAction.Capture -> shutter()
      CameraAction.TogglePause -> engine.pauseOrResumeRecording()

      is CameraAction.SelectLens -> {
        if (action.delta != 0) engine.switchLens(action.delta)
        engine.setZoom(1f) // a lens chip is that lens at 1x
      }
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
      CameraAction.SetAuto -> engine.setAuto()

      CameraAction.OpenSettings -> _state.update { it.copy(settingsOpen = true) }
      CameraAction.CloseSettings -> _state.update { it.copy(settingsOpen = false) }
      is CameraAction.SetQuality -> engine.quality = action.quality
      CameraAction.ToggleShutterSound -> engine.soundOn = !engine.soundOn
      CameraAction.ToggleGeotag -> engine.geotagOn = !engine.geotagOn
      CameraAction.ToggleLevel -> engine.levelOn = !engine.levelOn
      CameraAction.ToggleDng -> engine.dngOn = !engine.dngOn
      CameraAction.ToggleLogVideo -> engine.logVideo = !engine.logVideo
      CameraAction.ToggleImuRotation -> engine.imuRotationOn = !engine.imuRotationOn
      CameraAction.CycleVideoRes -> engine.cycleVideoRes()
      CameraAction.CycleVideoFps -> engine.cycleVideoFps()
      CameraAction.CycleHdrFps -> engine.cycleHdrFps()
      CameraAction.CycleSlowFps -> engine.cycleSlowFps()
      CameraAction.CycleTimelapseInterval -> engine.cycleTimelapseInterval()
      CameraAction.ResetCalibration -> engine.resetCalibration()
      CameraAction.StartCalibration -> _state.update { it.copy(settingsOpen = false, calibrating = true) }
      CameraAction.StopCalibration -> _state.update { it.copy(calibrating = false) }

      CameraAction.OpenGallery -> {
        _state.update { it.copy(galleryOpen = true) }
        loadGallery()
      }
      CameraAction.CloseGallery -> _state.update { it.copy(galleryOpen = false) }
      is CameraAction.OpenViewer -> _state.update { it.copy(viewer = action.item) }
      CameraAction.CloseViewer -> _state.update { it.copy(viewer = null) }
      is CameraAction.DeleteMedia -> deleteMedia(action.item)
      is CameraAction.ShareMedia -> _events.trySend(
        CameraEvent.Share(
          action.item.uri,
          ctx.contentResolver.getType(action.item.uri) ?: if (action.item.isVideo) "video/mp4" else "image/jpeg",
        ),
      )
      is CameraAction.PlayMedia -> _events.trySend(CameraEvent.Play(action.item.uri))

      CameraAction.RequestPermission -> _events.trySend(CameraEvent.RequestPermission)
      CameraAction.OpenAppSettings -> _events.trySend(CameraEvent.OpenAppSettings)

      is CameraAction.AttachPreview -> engine.attach(action.view)
      is CameraAction.AttachSlowPreview -> engine.setSlowPreviewSurface(action.surface)
      CameraAction.ScreenResumed -> onResumed()
      CameraAction.ScreenStopped -> onStopped()
    }
    refresh()
  }

  // ---------------------------------------------------------------- lifecycle

  private fun onResumed() {
    val granted = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    _state.update { it.copy(cameraGranted = granted) }
    if (!granted) {
      // ask once by ourselves; after that the panel's buttons do it
      if (!askedPermission) {
        askedPermission = true
        _events.trySend(CameraEvent.RequestPermission)
      }
      return
    }
    // The engine outlives the activity: probe only the first time, so a recreated screen keeps its lens.
    if (engine.state.lenses.isEmpty()) engine.probeLenses() else engine.reopenIfNeeded()
    startSensors()
    restartAnalysis()
    if (_state.value.lastThumb == null) {
      viewModelScope.launch {
        val newest = withContext(Dispatchers.IO) { queryMedia(limit = 1) }.firstOrNull() ?: return@launch
        _state.update { if (it.lastThumb == null) it.copy(lastThumb = newest.uri) else it }
      }
    }
  }

  /** In the background nothing may keep running: not the camera, not the sensors, not a countdown. */
  private fun onStopped() {
    countdownJob?.cancel()
    _state.update { it.copy(countdown = 0) }
    analysisJob?.cancel()
    stopSensors()
    engine.close()
  }

  // ---------------------------------------------------------------- capture and recording

  private fun shutter() {
    val s = engine.state
    when (s.mode) {
      CameraEngine.Mode.TIMELAPSE -> engine.toggleTimelapse()
      CameraEngine.Mode.VIDEO ->
        if (s.recording) engine.stopRecording() else if (!engine.startRecording()) fail("rekaman gagal dimulai")
      CameraEngine.Mode.SLOWMO ->
        if (s.recording || s.slowPreview) {
          engine.stopRecording()
        } else if (!engine.startSlowMotion()) {
          fail("slow motion tidak didukung kamera ini")
        }
      CameraEngine.Mode.HDRVID ->
        if (s.recording) engine.stopRecording() else if (!engine.startHdrVideo()) fail("HDR video gagal dimulai")
      else -> photo()
    }
  }

  /** Something is being recorded (or a slow-motion take is being set up). */
  private fun taking(): Boolean = engine.state.recording || engine.state.slowPreview || engine.timelapseActive

  private fun fail(message: String) {
    _events.trySend(CameraEvent.Error(message))
  }

  /** Honours the self-timer; a second press while it counts cancels the shot. */
  private fun photo() {
    if (countdownJob?.isActive == true) {
      countdownJob?.cancel()
      _state.update { it.copy(countdown = 0) }
      return
    }
    val timer = engine.state.timerSeconds
    if (timer <= 0) {
      takePhoto()
      return
    }
    countdownJob = viewModelScope.launch {
      for (left in timer downTo 1) {
        _state.update { it.copy(countdown = left) }
        delay(1000)
      }
      _state.update { it.copy(countdown = 0) }
      takePhoto()
    }
  }

  private fun takePhoto() {
    if (engine.state.busy) {
      engine.cancelExposure() // a long exposure is the one shot the shutter can still stop
      return
    }
    if (engine.soundOn) runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
    if (engine.rawActive) engine.stashPreview()
    engine.capture()
  }

  /** Counts the seconds of a take; a paused take does not count. */
  private fun syncRecordClock(recording: Boolean) {
    if (recording && recordClockJob == null) {
      _state.update { it.copy(recordingSeconds = 0) }
      recordClockJob = viewModelScope.launch {
        while (isActive) {
          delay(1000)
          if (!engine.state.recordingPaused) _state.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
        }
      }
    } else if (!recording && recordClockJob != null) {
      recordClockJob?.cancel()
      recordClockJob = null
    }
  }

  // ---------------------------------------------------------------- gallery

  private fun loadGallery() {
    viewModelScope.launch {
      val items = withContext(Dispatchers.IO) { queryMedia() }
      _state.update { it.copy(galleryItems = items) }
    }
  }

  private fun deleteMedia(item: GalleryItem) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.delete(item.uri, null, null) }
          .onFailure { Log.w(TAG, "delete failed", it) }
      }
      _state.update { it.copy(viewer = null) }
      loadGallery()
    }
  }

  /** Photos and videos of DCIM/CoverCam in one list, newest first. */
  private fun queryMedia(limit: Int = GALLERY_LIMIT): List<GalleryItem> {
    val out = ArrayList<GalleryItem>()
    runCatching {
      ctx.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
        arrayOf("DCIM/CoverCam%"),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
      )?.use { c ->
        while (c.moveToNext() && out.size < limit) {
          val id = c.getLong(0)
          when (c.getInt(1)) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
              out += GalleryItem(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), false)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
              out += GalleryItem(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), true)
          }
        }
      }
    }.onFailure { Log.w(TAG, "gallery query failed", it) }
    return out
  }

  // ---------------------------------------------------------------- focus peaking / histogram

  private fun restartAnalysis() {
    analysisJob?.cancel()
    val s = _state.value
    if (!s.peaking) _state.update { it.copy(peakMask = null) }
    if (!s.peaking && !s.histogram) return
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
      if (engine.levelOn) {
        // Lying flat, gravity says nothing about tilt. Otherwise half-degree steps: sensor noise must not redraw
        // the screen sixteen times a second.
        val roll = if (abs(z) > 8.5f) null else (Math.toDegrees(atan2(x.toDouble(), y.toDouble())) * 2).roundToInt() / 2f
        if (roll != _state.value.roll) _state.update { it.copy(roll = roll) }
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
    if (sensorsOn) return
    sensorsOn = true
    val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val orientation = sensorManager.getDefaultSensor(27) // android.sensor.device_orientation
    if (accel != null) sensorManager.registerListener(gravityListener, accel, SensorManager.SENSOR_DELAY_UI)
    if (orientation != null) {
      sensorManager.registerListener(orientationListener, orientation, SensorManager.SENSOR_DELAY_UI)
    }
  }

  private fun stopSensors() {
    if (!sensorsOn) return
    sensorsOn = false
    runCatching { sensorManager.unregisterListener(gravityListener) }
    runCatching { sensorManager.unregisterListener(orientationListener) }
  }

  // ---------------------------------------------------------------- state plumbing

  private fun refresh() {
    val engineState = engine.state
    _state.update { it.merge(engine) }
    syncRecordClock(engineState.recording)
    val thumb = engineState.lastThumb
    if (thumb != null && thumb != reportedThumb) {
      reportedThumb = thumb
      _events.trySend(CameraEvent.PhotoSaved(thumb))
    }
  }

  override fun onCleared() {
    stopSensors()
    runCatching { shutterSound.release() }
    engine.close()
    super.onCleared()
  }

  companion object {
    private const val TAG = "CoverCamVm"
    private const val GALLERY_LIMIT = 60
  }
}

/** Copies the engine's truth onto the immutable UI state, leaving UI-only fields untouched. */
private fun CameraUiState.merge(engine: CameraEngine): CameraUiState {
  val e = engine.state
  return copy(
    ready = e.ready,
    mode = e.mode,
    facingBack = e.facingBack,
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
    manualFocus = e.manualFocus,
    locked = engine.locked,
    lastThumb = e.lastThumb ?: lastThumb,
    zoom = engine.currentZoom(),
    slowPreview = e.slowPreview,
    slowPreviewWidth = engine.slowMotionSize?.width ?: 1280,
    slowPreviewHeight = engine.slowMotionSize?.height ?: 720,
    previewRotationDegrees = engine.previewRotationDegrees(),
    previewMirrored = engine.previewMirrored(),
    previewScaleX = e.previewScaleX,
    previewScaleY = e.previewScaleY,
    pictureAspect = e.pictureAspect,
    ratioLabel = engine.ratioLabel,
    quality = engine.quality,
    soundOn = engine.soundOn,
    geotagOn = engine.geotagOn,
    levelOn = engine.levelOn,
    dngOn = engine.dngOn,
    logVideo = engine.logVideo,
    imuRotationOn = engine.imuRotationOn,
    video4k = engine.video4k,
    video60 = engine.video60,
    video60Available = engine.video60Available,
    hdr60 = engine.hdr60,
    slowFps = engine.slowMotionFps,
    slowFpsChoices = engine.slowMotionFpsChoices,
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
        if (abs(gx) + abs(gy) > 140) out[i] = PEAK_COLOR
      }
    }
    mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    mask.setPixels(out, 0, w, 0, 0, w, h)
  }
  return hist to mask
}

/** The design's one signal colour (#D71921), as the int a Bitmap wants. */
private const val PEAK_COLOR = 0xFFD71921.toInt()
