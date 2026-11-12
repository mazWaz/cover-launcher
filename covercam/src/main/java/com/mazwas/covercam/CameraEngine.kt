package com.mazwas.covercam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera2 engine for the Flip5 cover screen: photo, video and manual (Pro) modes.
 *
 * The package is com.samsung.android.ruler on purpose - Samsung's cameraserver filters the lens
 * list per package, and this is the name Samsung GCam mods use (see covercam/README.md).
 */
class CameraEngine(private val ctx: Context) {

  enum class Mode { PHOTO, VIDEO, PRO, NIGHT, PORTRAIT, HDR, TIMELAPSE, SLOWMO, PANORAMA, HDRVID }

  data class State(
    val ready: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val facingBack: Boolean = true,
    val lensLabel: String = "-",
    val lenses: List<String> = emptyList(),
    val lensIndex: Int = 0,
    val torch: Boolean = false,
    val timerSeconds: Int = 0,
    val recording: Boolean = false,
    val recordingPaused: Boolean = false,
    val status: String = "",
    val lastSaved: String? = null,
    val iso: Int = 200,
    val isoRange: IntRange = 100..3200,
    val exposureNs: Long = 8_000_000L,
    val exposureRange: LongRange = 1_000_000L..1_000_000_000L,
    val focusDiopters: Float = 0f,
    val minFocus: Float = 0f,
    val ev: Int = 0,
    val evRange: IntRange = 0..0,
    val wbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    /** Exposure is hand-set (ISO/shutter touched in Pro); false = auto exposure, EV applies. */
    val manual: Boolean = false,
    /** Focus is hand-set (focus slider touched in Pro); false = continuous autofocus. */
    val manualFocus: Boolean = false,
    val lastThumb: android.net.Uri? = null,
    val busy: Boolean = false,
    /** True from the moment a slow-motion take is asked for: the UI mounts its preview surface. */
    val slowPreview: Boolean = false,
    /** Scale that undoes TextureView's stretch; overlays drawn over the preview apply the same one. */
    val previewScaleX: Float = 1f,
    val previewScaleY: Float = 1f,
    /** Width / height of the upright picture; the UI gives the preview a box of this shape. */
    val pictureAspect: Float = 4f / 3f,
  )

  /** Exposed so the ViewModel can reach the application context without a second Koin binding. */
  val appContext: Context get() = ctx

  var state by mutableStateOf(State())
    private set

  private val manager = ctx.getSystemService(CameraManager::class.java)

  /**
   * Camera callbacks run on the main looper.
   *
   * Every callback writes Compose state and touches the TextureView, so marshalling them here
   * removes a whole class of cross-thread state writes (and makes setTransform/getBitmap legal).
   * The heavy work — decoding, merging, stitching — is still pushed to worker threads.
   */
  private val handler = Handler(android.os.Looper.getMainLooper())

  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var rawReader: ImageReader? = null
  private var blackLevel = 64
  private var whiteLevel = 1023
  private var cfaPattern = 1
  private var rawSize: Size = Size(4000, 3000)
  private var lastGains: android.hardware.camera2.params.RggbChannelVector? = null
  private var lastBlack: FloatArray? = null
  private var lastWhite: Int? = null
  private var lastPreviewJpeg: ByteArray? = null
  private var colorMatrix1: DoubleArray? = null
  private var colorMatrix2: DoubleArray? = null
  private var calibTransform1: DoubleArray? = null
  private var calibTransform2: DoubleArray? = null
  private var forwardMatrix1: DoubleArray? = null
  private var forwardMatrix2: DoubleArray? = null
  private var illuminant1 = 21
  private var illuminant2 = 17
  private var previewSurface: Surface? = null
  private var slowPreviewSurface: Surface? = null
  private var textureView: TextureView? = null
  private var recorder: MediaRecorder? = null
  private var recorderSurface: Surface? = null

  private var sensorOrientation = 90
  private var lensFront = false
  private var activeArray: Rect? = null
  private var previewSize = Size(1920, 1440)
  private var zoomLimits = 1f..8f
  // The upright picture inside the view, set by applyTransform; taps are mapped through it.
  private var pictureW = 0f
  private var pictureH = 0f

  // One worker for saves and merges: two 12 MP merges at once would run out of memory.
  private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

  private var timelapseRunning = false
  private var capHighSpeed = false
  private var capDepth = false
  private var capVideoHdr = false

  var timelapseIntervalSec: Int
    get() = prefs.getInt("tlint", 2)
    set(v) = prefs.edit().putInt("tlint", v).apply()

  fun cycleTimelapseInterval() {
    timelapseIntervalSec = when (timelapseIntervalSec) {
      1 -> 2
      2 -> 5
      else -> 1
    }
    state = state.copy(status = "timelapse ${timelapseIntervalSec}s")
  }

  fun capsInfo(): String =
    "slowmo=" + (if (capHighSpeed) "yes" else "no") +
      " depth=" + (if (capDepth) "yes" else "no") +
      " hdrvid=" + (if (capVideoHdr) "yes" else "no")

  private val timelapseTick = object : Runnable {
    override fun run() {
      if (!timelapseRunning) return
      capture()
      handler.postDelayed(this, timelapseIntervalSec * 1000L)
    }
  }

  val timelapseActive: Boolean get() = timelapseRunning

  fun toggleTimelapse() {
    if (timelapseRunning) {
      timelapseRunning = false
      handler.removeCallbacks(timelapseTick)
      state = state.copy(status = "timelapse berhenti")
    } else {
      timelapseRunning = true
      state = state.copy(status = "timelapse " + timelapseIntervalSec + "s")
      handler.post(timelapseTick)
    }
  }

  var video4k: Boolean
    get() = prefs.getBoolean("v4k", false)
    set(v) = prefs.edit().putBoolean("v4k", v).apply()

  var video60: Boolean
    get() = prefs.getBoolean("v60", false)
    set(v) = prefs.edit().putBoolean("v60", v).apply()

  /**
   * Whether the open lens runs the sensor at 60 for a recording. The public frame-rate list ends at 30 on every lens;
   * only the main lens obeys Samsung's recording keys (measured: ultrawide accepts them and stays at 33.3 ms).
   */
  var video60Available = false
    private set

  fun cycleVideoRes() {
    video4k = !video4k
    state = state.copy(status = if (video4k) "video 4K" else "video 1080p")
  }

  fun cycleVideoFps() {
    video60 = !video60
    state = state.copy(status = if (video60) "video 60fps" else "video 30fps")
  }

  /** The pause button of a running video take. */
  fun pauseOrResumeRecording() {
    val rec = recorder ?: return
    try {
      if (state.recordingPaused) {
        rec.resume()
        state = state.copy(recordingPaused = false, status = "merekam")
      } else {
        rec.pause()
        state = state.copy(recordingPaused = true, status = "jeda")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "pause/resume failed", t)
    }
  }

  private var backIds: List<String> = emptyList()
  private var frontIds: List<String> = emptyList()
  private var activeIds: List<String> = emptyList()

  private var jpegSize: Size = Size(4000, 3000)
  private var zoomRatio = 1.0f
  private var focusPoint: android.graphics.PointF? = null
  private val nightFrames = ArrayList<ByteArray>()
  private val hdrFrames = ArrayList<ByteArray>()
  private val panoFrames = ArrayList<ByteArray>()
  private var lastJpegRotation = 0
  private var lastFlip = false
  private var lastAeIso: Int? = null
  private var lastAeExposureNs: Long? = null
  private val aeCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(
      session: CameraCaptureSession,
      request: CaptureRequest,
      result: TotalCaptureResult,
    ) {
      result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
      result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNs = it }
      // Keep the newest white-balance / level metadata from the preview stream so a
      // still capture always has valid values even if its own result arrives later.
      result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
      result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { lastBlack = it }
      result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { lastWhite = it }
    }
  }

  // Per-lens calibration lives in prefs ("extra_<id>", "mirror_<id>") so a label tap survives restarts.
  private fun extraFor(id: String): Int = prefs.getInt("extra_$id", DEFAULT_EXTRA[id] ?: 90)
  private fun mirrorFor(id: String): Boolean = prefs.getBoolean("mirror_$id", id in DEFAULT_MIRROR)
  private var currentId: String = "0"
  private val prefs = ctx.getSharedPreferences("covercam", Context.MODE_PRIVATE)
  private var aeLock = false

  enum class Ratio(val label: String, val aspect: Double) {
    R4_3("4:3", 4.0 / 3), R16_9("16:9", 16.0 / 9), R1_1("1:1", 1.0)
  }

  /** The ratio chosen for photos and for VIDEO and HDRV. A JPEG is stored in it; a DNG keeps the whole sensor and carries it as its crop. */
  private var ratio = Ratio.R4_3

  /**
   * The shape of what the next shot shows. The preview is opened in it, so the screen shows what is saved.
   * Slow motion has no 4:3 or 1:1: its high-speed list holds 16:9 and one wide shape, picked by [cycleRatio].
   */
  val shownRatio: Ratio get() = if (state.mode == Mode.SLOWMO) Ratio.R16_9 else ratio

  val ratioLabel: String
    get() = if (state.mode == Mode.SLOWMO) (if (slowIsWide()) "21:9" else "16:9") else ratio.label

  private fun slowIsWide() = (hsSize?.let { it.width.toDouble() / it.height } ?: 0.0) > 2.0

  /** The shape the open camera's streams were picked for. */
  private var streamRatio = Ratio.R4_3

  private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) = applyTransform()
  }

  init {
    // The launcher turns the cover display. A half turn changes no view size, so nothing else would tell us.
    ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
      .registerDisplayListener(displayListener, handler)
    val savedMode = runCatching { Mode.valueOf(prefs.getString("mode", Mode.PHOTO.name)!!) }
      .getOrDefault(Mode.PHOTO)
    ratio = runCatching { Ratio.valueOf(prefs.getString("ratio", Ratio.R4_3.name)!!) }
      .getOrDefault(Ratio.R4_3)
    aeLock = prefs.getBoolean("aelock", false)
    zoomRatio = prefs.getFloat("zoom", 1f)
    state = state.copy(
      mode = savedMode,
      timerSeconds = prefs.getInt("timer", 0),
      wbMode = prefs.getInt("wb", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    )
  }

  fun currentZoom(): Float = zoomRatio

  /**
   * Surface used only while recording slow motion: a constrained high-speed session
   * rejects the 1920x1440 TextureView, but a SurfaceView with a fixed buffer size fits the
   * published list, so the preview stays live. Its arrival is what starts an armed take.
   */
  fun setSlowPreviewSurface(surface: Surface?) {
    slowPreviewSurface = surface
    if (surface != null && state.slowPreview && recorder == null) beginSlowMotion()
  }

  fun previewRotationDegrees(): Int = previewRotation()

  fun previewMirrored(): Boolean = mirrorFor(currentId)

  /** Grabs a small JPEG preview so the DNG can embed it (galleries then show real colours). */
  private fun canRecordAudio(): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED

  fun stashPreview() {
    val bmp = runCatching { textureView?.getBitmap(480, 360) }
      .onFailure { Log.w(TAG, "preview grab failed", it) }
      .getOrNull() ?: return
    val bos = ByteArrayOutputStream()
    runCatching { bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos) }
      .onFailure { Log.w(TAG, "preview compress failed", it) }
    bmp.recycle()
    lastPreviewJpeg = bos.toByteArray()
  }

  /** Called when the activity resumes: the cover panel may have disabled the camera meanwhile. */
  fun reopenIfNeeded() {
    if (device == null && !opening) {
      openRetries = 0
      openCurrent()
    }
  }

  /** AE/AF lock (long-press on the preview). It is persisted, so the screen has to show it. */
  val locked: Boolean get() = aeLock

  /** The next shot is a DNG. */
  val rawActive: Boolean get() = rawWanted()

  /** Current preview frame, used by the histogram and focus-peaking analysers. */
  fun previewBitmap(w: Int, h: Int): android.graphics.Bitmap? =
    runCatching { textureView?.getBitmap(w, h) }.getOrNull()

  // ---- persisted preferences exposed to the settings screen ----

  var quality: Int
    get() = prefs.getInt("quality", 95)
    set(v) = prefs.edit().putInt("quality", v.coerceIn(50, 100)).apply()

  var soundOn: Boolean
    get() = prefs.getBoolean("sound", true)
    set(v) = prefs.edit().putBoolean("sound", v).apply()

  var geotagOn: Boolean
    get() = prefs.getBoolean("geotag", true)
    set(v) = prefs.edit().putBoolean("geotag", v).apply()

  var kelvin: Int
    get() = prefs.getInt("kelvin", 5500)
    set(v) {
      prefs.edit().putInt("kelvin", v.coerceIn(2500, 8500)).apply()
    }

  var dngOn: Boolean
    get() = prefs.getBoolean("dng", false)
    set(v) {
      prefs.edit().putBoolean("dng", v).apply()
      if ((rawReader != null) != rawWanted()) openCurrent()
    }

  /** RAW replaces the JPEG stream, and the merging modes need JPEG frames: DNG only where one shot is one file. */
  private fun rawWanted(): Boolean = dngOn && state.mode in RAW_MODES

  /** 10-bit video as log at 60 fps instead of HLG at 30. */
  var logVideo: Boolean
    get() = prefs.getBoolean("vlog", false)
    set(v) {
      if (state.recording) return // a take keeps the curve and the frame rate it began with
      prefs.edit().putBoolean("vlog", v).apply()
      refreshPreview()
    }

  val logActive: Boolean get() = logVideo && state.mode == Mode.HDRVID

  /** 10-bit video at 60 fps. Until it had its own switch LOG meant 60, so an existing LOG choice carries over. */
  var hdr60: Boolean
    get() = prefs.getBoolean("hdr60", prefs.getBoolean("vlog", false))
    set(v) = prefs.edit().putBoolean("hdr60", v).apply()

  fun cycleHdrFps() {
    if (state.recording) return // a take keeps the frame rate it began with
    hdr60 = !hdr60
    state = state.copy(status = if (hdr60 && video60Available) "10-bit 60fps" else "10-bit 30fps")
  }

  var imuRotationOn: Boolean
    get() = prefs.getBoolean("imurot", false)
    set(v) {
      prefs.edit().putBoolean("imurot", v).apply()
      if (!v) clearDeviceRotation()
    }

  var levelOn: Boolean
    get() = prefs.getBoolean("level", true)
    set(v) = prefs.edit().putBoolean("level", v).apply()

  /** Restore the per-lens calibration table to its verified defaults. */
  fun resetCalibration() {
    val edit = prefs.edit()
    prefs.all.keys.filter { it.startsWith("extra_") || it.startsWith("mirror_") }.forEach(edit::remove)
    edit.remove("imuref").apply()
    imuRotation = -1 // display rotation until the IMU commits a new reference quadrant
    openCurrent()
  }

  fun cycleRatio() {
    if (state.mode == Mode.SLOWMO) {
      prefs.edit().putBoolean("slow21", !slowIsWide()).apply()
    } else {
      ratio = when (ratio) {
        Ratio.R4_3 -> Ratio.R16_9
        Ratio.R16_9 -> Ratio.R1_1
        Ratio.R1_1 -> Ratio.R4_3
      }
      prefs.edit().putString("ratio", ratio.name).apply()
    }
    openCurrent()
  }

  fun toggleLock() {
    aeLock = !aeLock
    prefs.edit().putBoolean("aelock", aeLock).apply()
    refreshPreview()
    state = state.copy(status = if (aeLock) "AE/AF LOCK" else "AE/AF AUTO")
  }
  private var opening = false
  private var openRetries = 0
  // A lens/ratio change that arrives while the camera is still opening is replayed once it is open.
  private var reopenQueued = false
  // Bumped by every open and close: a callback holding an older number belongs to a camera nobody wants any more.
  private var openToken = 0

  // ---------------------------------------------------------------- probing

  fun probeLenses() {
    val ids = try { manager.cameraIdList.toList() } catch (t: Throwable) { emptyList() }
    val backs = ArrayList<String>()
    val fronts = ArrayList<String>()
    for (id in ids) {
      val ch = try { manager.getCameraCharacteristics(id) } catch (t: Throwable) { null } ?: continue
      when (ch.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> backs.add(id)
        CameraCharacteristics.LENS_FACING_FRONT -> fronts.add(id)
      }
    }
    backIds = backs.sorted().ifEmpty { listOf("0") }
    frontIds = fronts.sorted()
    Log.i(TAG, "ids=${ids.joinToString(",")} back=${backIds.joinToString(",")} front=${frontIds.joinToString(",")}")
    useFacing(true)
  }

  private fun useFacing(back: Boolean) {
    activeIds = if (back) backIds else frontIds
    if (activeIds.isEmpty()) {
      state = state.copy(status = "no front camera")
      return
    }
    state = state.copy(
      facingBack = back,
      lenses = activeIds.map(::labelFor),
      lensIndex = 0,
      lensLabel = labelFor(activeIds[0]),
    )
    openCurrent()
  }

  fun switchCamera() = useFacing(!state.facingBack)

  fun switchLens(delta: Int) {
    if (activeIds.size <= 1) return
    val next = ((state.lensIndex + delta) % activeIds.size + activeIds.size) % activeIds.size
    state = state.copy(lensIndex = next, lensLabel = labelFor(activeIds[next]))
    openCurrent()
  }

  private fun labelFor(id: String): String = when (id) {
    "2", "58" -> "0.5x"
    "0", "56" -> "1.0x"
    else -> id
  }

  // ---------------------------------------------------------------- lifecycle

  fun attach(view: TextureView) {
    textureView = view
    view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
        textureView = view
        openCurrent()
      }
      override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = applyTransform()
      override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        close()
        // the engine outlives the activity: don't keep a dead window's view
        if (textureView === view) textureView = null
        return true
      }
      override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
    }
    if (view.isAvailable) openCurrent()
  }

  /**
   * The activity stopped or its view went away: finish the take first (a recorder that is only dropped leaves a
   * file no player opens), then let the camera go.
   */
  fun close() {
    timelapseRunning = false
    handler.removeCallbacks(timelapseTick)
    opening = false
    reopenQueued = false
    openToken++
    val saved = finishTake()
    closeDevice()
    state = state.copy(
      recording = false,
      recordingPaused = false,
      slowPreview = false,
      busy = false,
      lastThumb = saved ?: state.lastThumb,
    )
  }

  /** [keepPreview]: video hands the preview Surface to its own session. */
  private fun closeDevice(keepPreview: Boolean = false) {
    try { session?.close() } catch (_: Throwable) {}
    session = null
    try { device?.close() } catch (_: Throwable) {}
    device = null
    try { reader?.close() } catch (_: Throwable) {}
    reader = null
    try { rawReader?.close() } catch (_: Throwable) {}
    rawReader = null
    if (!keepPreview) {
      previewSurface?.release()
      previewSurface = null
    }
    state = state.copy(ready = false)
  }

  /** The camera went away under us (another app took it): finish the take and wait for the next resume. */
  private fun cameraLost(status: String) {
    val saved = finishTake()
    closeDevice()
    state = state.copy(
      recording = false,
      recordingPaused = false,
      slowPreview = false,
      busy = false,
      lastThumb = saved ?: state.lastThumb,
      status = status,
    )
  }

  private fun openCurrent() {
    val tv = textureView ?: return
    if (!tv.isAvailable) return
    val id = activeIds.getOrNull(state.lensIndex) ?: return
    if (opening) {
      reopenQueued = true
      return
    }
    opening = true
    currentId = id
    focusPoint = null
    if (recorder != null || hdrRecorder != null || state.slowPreview) {
      // a lens or ratio change in the middle of a take ends the take
      handler.removeCallbacks(slowArmTimeout)
      val saved = finishTake()
      state = state.copy(
        recording = false,
        recordingPaused = false,
        slowPreview = false,
        lastThumb = saved ?: state.lastThumb,
      )
    }
    closeDevice()
    try {
      val ch = manager.getCameraCharacteristics(id)
      sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
      lensFront = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
      activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      streamRatio = shownRatio
      jpegSize = pickJpegSize(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(), streamRatio)
      // the preview takes the still's shape: the picture on screen is the picture that gets saved
      previewSize = pickPreviewSize(map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray(), jpegSize)

      previewSurface = Surface(tv.surfaceTexture)
      if (rawWanted()) {
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
        rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height } ?: jpegSize
        ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { whiteLevel = it }
        ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let { cfaPattern = it }
        rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).also { r ->
          r.setOnImageAvailableListener({ ir -> onRaw(ir) }, handler)
        }
      } else {
        reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 8).also { r ->
          r.setOnImageAvailableListener({ ir -> onJpeg(ir) }, handler)
        }
      }

      video60Available = id == "0"
      readColorCalibration(ch)
      probeHighSpeed(ch)
      applyCharacteristicRanges(ch)
      Log.i(TAG, "open id=$id sensor=$sensorOrientation extra=${extraFor(id)} mirror=${mirrorFor(id)}")

      val token = ++openToken
      manager.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
          opening = false
          if (token != openToken || reopenQueued) {
            // closed meanwhile, or another lens/ratio was asked for while this one was opening
            cam.close()
            if (reopenQueued) {
              reopenQueued = false
              openCurrent()
            }
            return
          }
          openRetries = 0
          device = cam
          createPreviewSession(cam)
        }
        override fun onDisconnected(cam: CameraDevice) {
          opening = false
          cam.close()
          if (token == openToken) cameraLost("kamera dipakai aplikasi lain")
        }
        override fun onError(cam: CameraDevice, error: Int) {
          opening = false
          cam.close()
          if (token != openToken) return
          cameraLost("camera error $error")
          if (openRetries < 3) {
            openRetries++
            handler.postDelayed({ openCurrent() }, 900L * openRetries)
            state = state.copy(status = "retrying camera (error $error)")
          } else {
            state = state.copy(status = "camera error $error (id $id)")
          }
        }
      }, handler)
    } catch (t: Throwable) {
      opening = false
      Log.e(TAG, "open failed id=$id", t)
      state = state.copy(status = "open failed: ${t.message}")
    }
  }

  private fun readColorCalibration(ch: CameraCharacteristics) {
    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    capHighSpeed = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
    capDepth = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
    val hsSizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoSizes
    capHighSpeed = capHighSpeed || (hsSizes != null && hsSizes.isNotEmpty())
    capVideoHdr = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
      ?.supportedProfiles?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true
    colorMatrix1 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { toDoubles(it) }
    colorMatrix2 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { toDoubles(it) }
    calibTransform1 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let { toDoubles(it) }
    calibTransform2 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { toDoubles(it) }
    forwardMatrix1 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { toDoubles(it) }
    forwardMatrix2 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { toDoubles(it) }
    ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { illuminant1 = it }
    ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { illuminant2 = it.toInt() }
    Log.i(TAG, "colour: m1=${colorMatrix1 != null} m2=${colorMatrix2 != null} fwd=${forwardMatrix1 != null} illum=$illuminant1")
  }

  /** The open camera's colour calibration: what a DNG carries, and what manual white balance is worked out with. */
  private fun rawColor() = DngWriter.RawColor(
    color1 = colorMatrix1,
    color2 = colorMatrix2,
    calib1 = calibTransform1,
    calib2 = calibTransform2,
    fwd1 = forwardMatrix1,
    fwd2 = forwardMatrix2,
    illum1 = illuminant1,
    illum2 = illuminant2,
  )

  private fun toDoubles(t: android.hardware.camera2.params.ColorSpaceTransform): DoubleArray {
    val out = DoubleArray(9)
    for (row in 0..2) for (col in 0..2) {
      out[row * 3 + col] = t.getElement(col, row).toDouble()
    }
    return out
  }

  private fun applyCharacteristicRanges(ch: CameraCharacteristics) {
    val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val zoomRange: Range<Float>? = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    state = state.copy(
      isoRange = isoRange?.let { it.lower..it.upper } ?: 100..3200,
      exposureRange = expRange?.let { it.lower..maxOf(it.upper, LONGEST_EXPOSURE_NS[currentId] ?: 0L) } ?: 1_000_000L..1_000_000_000L,
      evRange = evRange?.let { it.lower..it.upper } ?: 0..0,
      minFocus = minFocus,
    )
    zoomLimits = (zoomRange?.lower ?: 1f)..(zoomRange?.upper ?: 8f).coerceIn(1f, 8f)
    zoomRatio = zoomRatio.coerceIn(zoomLimits.start, zoomLimits.endInclusive)
  }

  // ---------------------------------------------------------------- requests

  private fun buildPreviewRequest(cam: CameraDevice): CaptureRequest {
    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    previewSurface?.let { b.addTarget(it) }
    applyCommon(b)
    return b.build()
  }

  /** [still]: this request makes the file, so a hand-set shutter runs its full time; a preview frame follows with gain. */
  private fun applyCommon(b: CaptureRequest.Builder, still: Boolean = false) {
    // CONTROL_MODE stays AUTO: OFF switches off every android.control.* setting, white balance presets included.
    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    if (state.manual) {
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
      val (iso, exposure) =
        if (still) state.iso to state.exposureNs else LongExposure.preview(state.iso, state.exposureNs, state.isoRange.last)
      b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
      b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
      // a frame is at least as long as its exposure; the lens's public longest frame is only 0.15 s
      if (exposure > LongExposure.PREVIEW_MAX_NS) b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure + FRAME_SLACK_NS)
    } else {
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      if (state.evRange.last > state.evRange.first) {
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.ev)
      }
    }
    if (state.manualFocus && state.minFocus > 0f) {
      b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      b.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDiopters)
    } else {
      b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }
    val calibration = rawColor()
    val white = WhiteBalance.neutral(kelvin, calibration)
    // a 10-bit take is tagged BT.2020 and the HAL converts nothing after this matrix (measured): sRGB values
    // in that file are the wrong colours
    val matrix =
      if (hdrRecorder != null) WhiteBalance.toRec2020(kelvin, calibration) else WhiteBalance.toSrgb(kelvin, calibration)
    if (state.wbMode != CaptureRequest.CONTROL_AWB_MODE_OFF) {
      b.set(CaptureRequest.CONTROL_AWB_MODE, state.wbMode)
    } else if (white == null || matrix == null) {
      // no calibration to work a colour temperature out with: automatic is the honest picture
      b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    } else {
      b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
      // the template's own gains are 1 and its matrix the identity: both have to be given, or the picture is green
      b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
      b.set(
        CaptureRequest.COLOR_CORRECTION_GAINS,
        android.hardware.camera2.params.RggbChannelVector((1 / white[0]).toFloat(), 1f, 1f, (1 / white[2]).toFloat()),
      )
      b.set(
        CaptureRequest.COLOR_CORRECTION_TRANSFORM,
        android.hardware.camera2.params.ColorSpaceTransform(
          Array(9) { android.util.Rational(Math.round(matrix[it] * 10000).toInt(), 10000) },
        ),
      )
    }
    if (logActive) {
      // takes the place of the HAL's own transfer curve, in the 8-bit preview as in the 10-bit recording:
      // the screen shows the flat picture the file holds
      val curve = LogCurve.points()
      b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
      b.set(CaptureRequest.TONEMAP_CURVE, android.hardware.camera2.params.TonemapCurve(curve, curve, curve))
    }
    b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
    b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLock)
    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
    b.set(CaptureRequest.FLASH_MODE,
      if (state.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    focusPoint?.let { p ->
      meteringRectFor(p)?.let { area ->
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(area))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(area))
      }
    }
  }

  private fun meteringRectFor(p: android.graphics.PointF): MeteringRectangle? {
    val tv = textureView ?: return null
    val sensor = activeArray ?: return null
    // The picture on screen is the sensor image turned upright and fitted, so the tap goes back the same way.
    val (px, py) = PreviewGeometry.tapToSensor(
      p.x, p.y, tv.width.toFloat(), tv.height.toFloat(), pictureW, pictureH, jpegOrientation(), flipped(),
    ) ?: return null
    // Regions count from the active array's own top-left corner.
    val sw = sensor.width()
    val sh = sensor.height()
    // a 16:9 or 1:1 preview is only the middle of the sensor
    val (nx, ny) = PreviewGeometry.streamToSensor(px, py, previewSize.width.toFloat() / previewSize.height, sw.toFloat() / sh)
    val halfW = sw / 10
    val halfH = sh / 10
    val cx = (nx * sw).toInt()
    val cy = (ny * sh).toInt()
    val rect = Rect(
      (cx - halfW).coerceIn(0, sw - 1),
      (cy - halfH).coerceIn(0, sh - 1),
      (cx + halfW).coerceIn(0, sw - 1),
      (cy + halfH).coerceIn(0, sh - 1),
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
  }

  /**
   * Call before a session is built on the TextureView's surface: the camera sizes the preview stream from that
   * surface's buffer, and TextureView puts the buffer back to the view's own size whenever it rebuilds its layer
   * (after Home) or is resized. Measured after Home: the stream came back as 1088x1088 and the picture was stretched.
   */
  private fun sizePreviewBuffer() {
    textureView?.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
  }

  private fun createPreviewSession(cam: CameraDevice) {
    val preview = previewSurface
    val still = reader?.surface ?: rawReader?.surface
    if (preview == null || still == null) return
    try {
      sizePreviewBuffer()
      val config = android.hardware.camera2.params.SessionConfiguration(
        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
        listOf(
          android.hardware.camera2.params.OutputConfiguration(preview),
          android.hardware.camera2.params.OutputConfiguration(still),
        ),
        java.util.concurrent.Executor { r -> handler.post(r) },
        object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
          if (device !== cam) return // closed while the session was being set up
          session = s
          try { s.setRepeatingRequest(buildPreviewRequest(cam), aeCallback, handler) } catch (t: Throwable) {
            Log.w(TAG, "repeating failed", t)
          }
          applyTransform()
          state = state.copy(ready = true)
        }
        override fun onConfigureFailed(s: CameraCaptureSession) {
          state = state.copy(status = "session config failed")
        }
      },
      )
      cam.createCaptureSession(config)
    } catch (t: Throwable) {
      Log.e(TAG, "session failed", t)
      state = state.copy(status = "session failed: ${t.message}")
    }
  }

  /** Surfaces of the running take (preview + encoder), so a zoom or focus change keeps feeding the encoder. */
  private var recordTargets: List<Surface> = emptyList()
  private var videoFps: Range<Int>? = null

  /** Frame rate the running or starting take was asked for (30 or 60). */
  private var recordFps = 30

  private fun buildRecordRequest(cam: CameraDevice): CaptureRequest {
    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
    recordTargets.forEach { b.addTarget(it) }
    applyCommon(b)
    // HDR (HLG10) keeps the request it was verified with: no stabilisation, the template's own frame rate at 30.
    if (hdrRecorder == null) {
      b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
      if (recordFps != 60) videoFps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }
    if (recordFps == 60) {
      // the public list of frame rates ends at 30 on every lens: Samsung's own recording keys are what runs the
      // sensor at 60 (measured: 16.6 ms between frames with them, 33.3 ms with the range alone)
      b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
      for (name in SAMSUNG_RECORDING_FPS) {
        runCatching { b.set(CaptureRequest.Key(name, Int::class.javaObjectType), 60) }
          .onFailure { Log.w(TAG, "$name not taken", it) }
      }
    }
    return b.build()
  }

  fun refreshPreview() {
    val cam = device ?: return
    val s = session ?: return
    try {
      when {
        // a high-speed burst can't be swapped mid-take; zoom and focus wait for the next session
        s is android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession -> Unit
        state.recording -> s.setRepeatingRequest(buildRecordRequest(cam), null, handler)
        // aeCallback again: without it the last metered ISO/shutter/white balance go stale after the first refresh
        else -> s.setRepeatingRequest(buildPreviewRequest(cam), aeCallback, handler)
      }
      applyTransform()
    } catch (t: Throwable) {
      Log.w(TAG, "refresh failed", t)
    }
  }

  // ---------------------------------------------------------------- controls

  fun toggleTorch() { state = state.copy(torch = !state.torch); refreshPreview() }

  fun cycleTimer() {
    val next = when (state.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
    prefs.edit().putInt("timer", next).apply()
    state = state.copy(timerSeconds = next)
  }

  /** One mode forward (a swipe to the left, volume-up) or back. */
  fun cycleMode(step: Int = 1) {
    val all = Mode.entries
    setMode(all[((state.mode.ordinal + step) % all.size + all.size) % all.size])
  }

  fun setMode(mode: Mode) {
    if (mode == state.mode) return
    val taking = state.recording || state.slowPreview
    if (timelapseRunning) toggleTimelapse()
    // frames of a half-done burst or panorama belong to the mode they were shot in
    nightFrames.clear()
    hdrFrames.clear()
    panoFrames.clear()
    prefs.edit().putString("mode", mode.name).apply()
    Log.i(TAG, "mode=" + mode.name)
    // Every mode starts on auto; Pro goes manual when its ISO, shutter or focus is first touched.
    state = state.copy(
      mode = mode,
      manual = false,
      manualFocus = false,
      ev = if (mode == Mode.PRO) state.ev else 0,
      busy = false,
    )
    when {
      taking -> stopRecording() // reopens the camera for the new mode
      // the new mode stores another kind of file or another shape: its streams have to be opened
      (rawReader != null) != rawWanted() || streamRatio != shownRatio -> openCurrent()
      else -> refreshPreview()
    }
  }

  fun setZoom(value: Float) {
    zoomRatio = value.coerceIn(zoomLimits.start, zoomLimits.endInclusive)
    prefs.edit().putFloat("zoom", zoomRatio).apply()
    refreshPreview()
  }

  /** Tap on the preview: meter and focus there; a hand-set focus goes back to autofocus. */
  fun tapFocus(x: Float, y: Float) {
    focusPoint = android.graphics.PointF(x, y)
    state = state.copy(manualFocus = false)
    refreshPreview()
  }

  /** First touch of ISO or shutter: start from what auto exposure had settled on, so the picture doesn't jump. */
  private fun enterManual() {
    if (state.manual) return
    state = state.copy(
      manual = true,
      iso = (lastAeIso ?: state.iso).coerceIn(state.isoRange.first, state.isoRange.last),
      exposureNs = (lastAeExposureNs ?: state.exposureNs).coerceIn(state.exposureRange.first, state.exposureRange.last),
    )
  }

  fun setIso(v: Int) {
    enterManual()
    state = state.copy(iso = v.coerceIn(state.isoRange.first, state.isoRange.last))
    refreshPreview()
  }

  fun setExposure(ns: Long) {
    enterManual()
    state = state.copy(exposureNs = ns.coerceIn(state.exposureRange.first, state.exposureRange.last))
    refreshPreview()
  }

  fun setFocus(d: Float) {
    state = state.copy(manualFocus = true, focusDiopters = d.coerceIn(0f, state.minFocus))
    refreshPreview()
  }

  /** Compensation only means something to auto exposure, so touching it hands exposure back to the camera. */
  fun setEv(v: Int) {
    state = state.copy(manual = false, ev = v.coerceIn(state.evRange.first, state.evRange.last))
    refreshPreview()
  }

  /** Hands exposure and focus back to the camera. */
  fun setAuto() {
    state = state.copy(manual = false, manualFocus = false)
    refreshPreview()
  }

  /** Manual white balance: the gains and matrix for light of [k] Kelvin, see [WhiteBalance]. */
  fun applyKelvin(k: Int) {
    kelvin = k
    setWb(CaptureRequest.CONTROL_AWB_MODE_OFF)
  }

  fun setWb(mode: Int) { prefs.edit().putInt("wb", mode).apply(); state = state.copy(wbMode = mode); refreshPreview() }

  // ---------------------------------------------------------------- photo

  fun capture() {
    // a second burst on top of a running one would mix their frames
    if (state.busy || state.recording) return
    // what the last long exposure left behind is not this shot's: its count would run on over it, the shutter
    // would cancel it, and a failure would be reported as "cancelled"
    handler.removeCallbacks(exposureTick)
    exposureStartedAt = 0L
    exposureCancelled = false
    when (state.mode) {
      Mode.NIGHT -> captureBurst()
      Mode.HDR -> captureHdr()
      else -> captureSingle()
    }
  }

  /** A frame the HAL dropped never reaches the reader: without this the shutter would stay "busy" for good. */
  private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      // what the sensor really did, not what was asked: the proof a long shutter was taken
      Log.i(TAG, "still exposure=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} frame=${result.get(CaptureResult.SENSOR_FRAME_DURATION)}")
    }

    override fun onCaptureFailed(
      session: CameraCaptureSession,
      request: CaptureRequest,
      failure: android.hardware.camera2.CaptureFailure,
    ) {
      Log.w(TAG, "still failed reason=${failure.reason}")
      nightFrames.clear()
      hdrFrames.clear()
      state = state.copy(busy = false, status = if (exposureCancelled) "eksposur dibatalkan" else "pengambilan gagal")
    }
  }

  // ---- a hand-set shutter over a second: the shot runs on its own, the screen counts and the shutter cancels

  private var exposureStartedAt = 0L // elapsedRealtime of the running long exposure, 0 = none
  private var exposureTotalSec = 0L
  private var exposureCancelled = false // an abort reaches the callback as a plain error, not as FLUSHED

  private val exposureTick = object : Runnable {
    override fun run() {
      if (exposureStartedAt == 0L || !state.busy) {
        exposureStartedAt = 0L
        return
      }
      val done = (android.os.SystemClock.elapsedRealtime() - exposureStartedAt) / 1000
      state = state.copy(status = "eksposur $done / $exposureTotalSec dtk")
      handler.postDelayed(this, 1000)
    }
  }

  /** Called right after a still is sent: marks the shot busy and starts the count when its shutter is long. */
  private fun beginLongExposure() {
    if (!state.manual || !LongExposure.isLong(state.exposureNs)) return
    exposureStartedAt = android.os.SystemClock.elapsedRealtime()
    exposureCancelled = false
    exposureTotalSec = state.exposureNs / 1_000_000_000L
    state = state.copy(busy = true)
    handler.post(exposureTick)
  }

  /** The shutter button while a long exposure runs. False when none does. */
  fun cancelExposure(): Boolean {
    if (exposureStartedAt == 0L) return false
    exposureStartedAt = 0L
    exposureCancelled = true
    // aborting also stops the repeating preview request: it is started again at once
    runCatching { session?.abortCaptures() }.onFailure { Log.w(TAG, "abort failed", it) }
    refreshPreview()
    return true
  }

  private fun applyStillExtras(b: CaptureRequest.Builder) {
    b.set(CaptureRequest.JPEG_QUALITY, quality.coerceIn(50, 100).toByte())
    if (geotagOn) lastLocation()?.let { b.set(CaptureRequest.JPEG_GPS_LOCATION, it) }
  }

  private fun lastLocation(): android.location.Location? {
    if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
    return runCatching {
      lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
    }.getOrNull()
  }

  private fun captureSingle() {
    val cam = device ?: return
    val s = session ?: return
    val rr = rawReader
    if (rr != null) {
      try {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rr.surface)
        applyCommon(b, still = true)
        lastJpegRotation = jpegOrientation()
        lastFlip = flipped()
        s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
          override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
          ) {
            lastGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            lastBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            lastWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
          }

          override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: android.hardware.camera2.CaptureFailure,
          ) {
            state = state.copy(busy = false, status = if (exposureCancelled) "eksposur dibatalkan" else "dng gagal")
          }
        }, handler)
        state = state.copy(busy = true, status = "dng…")
        beginLongExposure()
      } catch (t: Throwable) {
        Log.e(TAG, "dng capture failed", t)
        state = state.copy(status = "dng failed: ${t.message}")
      }
      return
    }
    val r = reader ?: return
    try {
      val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
      b.addTarget(r.surface)
      applyCommon(b, still = true)
      lastJpegRotation = jpegOrientation()
      lastFlip = flipped()
      b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
      applyStillExtras(b)
      s.capture(b.build(), stillCallback, handler)
      beginLongExposure()
    } catch (t: Throwable) {
      Log.e(TAG, "capture failed", t)
      state = state.copy(status = "capture failed: ${t.message}")
    }
  }

  /** Night: fire a short burst and average the frames to cut noise. */
  private fun captureBurst() {
    val cam = device ?: return
    val s = session ?: return
    val r = reader ?: return
    nightFrames.clear()
    try {
      repeat(NIGHT_FRAMES) {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        applyCommon(b)
        lastJpegRotation = jpegOrientation()
        lastFlip = flipped()
        b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
        applyStillExtras(b)
        s.capture(b.build(), stillCallback, handler)
      }
      state = state.copy(busy = true, status = "night: $NIGHT_FRAMES frame")
    } catch (t: Throwable) {
      Log.e(TAG, "burst failed", t)
      state = state.copy(status = "burst failed: ${t.message}")
    }
  }

  private fun onJpeg(ir: ImageReader) {
    val image = ir.acquireNextImage() ?: return
    try {
      val buf = image.planes[0].buffer
      val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
      when (state.mode) {
        Mode.NIGHT -> {
          nightFrames.add(bytes)
          if (nightFrames.size >= NIGHT_FRAMES) {
            val frames = ArrayList(nightFrames)
            nightFrames.clear()
            val exif = lastExif()
            worker.execute { saveBytes(processFullRes(frames, night = true), "night", exif) }
          }
        }
        Mode.HDR -> {
          hdrFrames.add(bytes)
          if (hdrFrames.size >= HDR_FRAMES) {
            val fr = ArrayList(hdrFrames)
            hdrFrames.clear()
            val exif = lastExif()
            worker.execute { saveBytes(processFullRes(fr, night = false), "hdr", exif) }
          }
        }
        Mode.PORTRAIT -> {
          val exif = lastExif()
          state = state.copy(busy = true)
          worker.execute { saveBytes(blurBackground(bytes), "bokeh", exif) }
        }
        Mode.PANORAMA -> {
          panoFrames.add(bytes)
          val count = panoFrames.size
          state = state.copy(status = "pano $count/$PANO_TARGET")
          if (count >= PANO_TARGET) {
            val frames = ArrayList(panoFrames)
            panoFrames.clear()
            val exif = lastExif()
            state = state.copy(busy = true)
            worker.execute { saveBytes(stitchPanorama(frames), "panorama", exif) }
          }
        }
        else -> {
          // the HAL already wrote the turn; only a flip needs the tag rewritten
          val exif = if (lastFlip) lastExif() else null
          worker.execute { saveBytes(bytes, "foto", exif) }
        }
      }
    } catch (t: Throwable) {
      Log.e(TAG, "jpeg failed", t)
      handler.post { state = state.copy(status = "jpeg failed: ${t.message}") }
    } finally {
      image.close()
    }
  }

  private fun onRaw(ir: ImageReader) {
    val image = ir.acquireNextImage() ?: return
    // The shot's metadata is read here, on the camera thread; the 24 MB file is written on the worker.
    val make = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL
    val gains = lastGains
    val neutral = if (gains != null && gains.greenEven > 0f && gains.red > 0f && gains.blue > 0f) {
      doubleArrayOf(
        (gains.greenEven / gains.red).toDouble(),
        1.0,
        (gains.greenEven / gains.blue).toDouble(),
      )
    } else {
      doubleArrayOf(1.0, 1.0, 1.0)
    }
    val black = lastBlack?.let { b -> (b.sum() / b.size).toInt() } ?: blackLevel
    val white = lastWhite ?: whiteLevel
    val orientation = lastExif()
    val preview = lastPreviewJpeg
    val color = rawColor()
    val cfa = cfaPattern
    val cropAspect = previewSize.width.toFloat() / previewSize.height
    worker.execute {
      try {
        val uri = DngWriter.write(
          ctx = ctx,
          image = image,
          make = make,
          model = model,
          blackLevel = black,
          whiteLevel = white,
          cfa = cfa,
          orientation = orientation,
          asShotNeutral = neutral,
          previewJpeg = preview,
          color = color,
          cropAspect = cropAspect,
        )
        handler.post {
          state = state.copy(
            busy = false,
            lastThumb = uri ?: state.lastThumb,
            status = if (uri != null) "dng tersimpan" else "dng gagal",
          )
        }
      } catch (t: Throwable) {
        Log.e(TAG, "dng write failed", t)
        handler.post { state = state.copy(busy = false, status = "dng gagal: ${t.message}") }
      } finally {
        image.close()
      }
    }
  }

  /** Three guided shots stitched by estimating the horizontal overlap. */
  private fun stitchPanorama(frames: List<ByteArray>): ByteArray {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val bmps = frames.mapNotNull {
      runCatching { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }.getOrNull()
    }
    if (bmps.size < 2) return frames.first()

    val w = bmps[0].width
    val h = bmps[0].height
    val grays = bmps.map { grayOf(it) }
    val offsets = IntArray(bmps.size)
    var totalW = w
    for (i in 1 until bmps.size) {
      val overlap = bestOverlap(grays[i - 1], grays[i], w, h)
      offsets[i] = offsets[i - 1] + (w - overlap)
      totalW = maxOf(totalW, offsets[i] + w)
    }

    val out = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    bmps.forEachIndexed { i, bmp -> canvas.drawBitmap(bmp, offsets[i].toFloat(), 0f, null) }

    // Feather the seams: average the two sources across each overlap band.
    for (i in 1 until bmps.size) {
      val overlap = w - (offsets[i] - offsets[i - 1])
      if (overlap <= 0) continue
      val prev = IntArray(overlap * h)
      val cur = IntArray(overlap * h)
      try {
        bmps[i - 1].getPixels(prev, 0, overlap, w - overlap, 0, overlap, h)
        bmps[i].getPixels(cur, 0, overlap, 0, 0, overlap, h)
      } catch (t: Throwable) {
        continue
      }
      for (k in prev.indices) {
        val a = prev[k]
        val b = cur[k]
        val r = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2
        val g = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        prev[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
      }
      out.setPixels(prev, 0, overlap, offsets[i], 0, overlap, h)
    }

    bmps.forEach { it.recycle() }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    out.recycle()
    return bos.toByteArray()
  }

  /** Searches for the overlap width (in pixels) that best matches two frames. */
  private fun bestOverlap(a: IntArray, b: IntArray, w: Int, h: Int): Int =
    Align.bestOverlap(a, b, w, h)

  /** [exif]: EXIF orientation to stamp on the file; null keeps what the HAL wrote. */
  private fun saveBytes(bytes: ByteArray, kind: String, exif: Int?) {
    try {
      val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CoverCam")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      uri?.let { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
      if (uri != null && exif != null) writeExifOrientation(uri, exif)
      handler.post {
        state = state.copy(lastSaved = name, lastThumb = uri ?: state.lastThumb, busy = false, status = "$kind tersimpan")
      }
    } catch (t: Throwable) {
      Log.e(TAG, "save failed", t)
      handler.post { state = state.copy(busy = false, status = "gagal menyimpan: ${t.message}") }
    }
  }

  /**
   * Full-resolution merge without loading every frame at once: the image is decoded in
   * horizontal strips with BitmapRegionDecoder, so a 12 MP x 5-frame merge stays inside
   * a few tens of MB instead of hundreds.
   *
   * A night burst is hand-held, so each frame is first lined up with the first one. An HDR bracket is not:
   * its frames differ in brightness, which a difference search can't match.
   *
   * The byte[] overload is deprecated in favour of the ByteBuffer one, but our frames are plain
   * in-memory JPEG bytes (not direct buffers), so the simple overload stays; suppress on the
   * function because an annotation inside the lambda body is not a valid expression position.
   */
  @Suppress("DEPRECATION")
  private fun processFullRes(frames: List<ByteArray>, night: Boolean): ByteArray {
    val decoders = frames.map {
      runCatching { android.graphics.BitmapRegionDecoder.newInstance(it, 0, it.size, false) }.getOrNull()
    }
    val first = decoders.firstOrNull { it != null } ?: return frames.first()
    val w = first.width
    val h = first.height
    if (w <= 0 || h <= 0) return frames.first()
    val moves = if (night) nightMoves(frames) else List(frames.size) { 0 to 0 }
    if (night) Log.i(TAG, "night moves=$moves")

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val tileH = 256
    val rect = Rect()
    val px = IntArray(w * tileH)
    val accR = DoubleArray(w * tileH)
    val accG = DoubleArray(w * tileH)
    val accB = DoubleArray(w * tileH)
    val accW = DoubleArray(w * tileH)
    val strip = IntArray(w * tileH)
    var y0 = 0
    while (y0 < h) {
      val th = minOf(tileH, h - y0)
      val n = w * th
      accR.fill(0.0)
      accG.fill(0.0)
      accB.fill(0.0)
      accW.fill(0.0)
      var used = 0
      decoders.forEachIndexed { k, d ->
        if (d == null || d.width != w || d.height != h) return@forEachIndexed
        val (moveX, moveY) = moves[k]
        // the rows of this frame that land on the strip once the frame is moved into place
        val top = (y0 - moveY).coerceIn(0, h)
        val bottom = (y0 + th - moveY).coerceIn(0, h)
        if (bottom <= top) return@forEachIndexed
        rect.set(0, top, w, bottom)
        val bmp = runCatching { d.decodeRegion(rect, BitmapFactory.Options()) }.getOrNull() ?: return@forEachIndexed
        val rows = bottom - top
        if (bmp.width == w && bmp.height == rows) {
          bmp.getPixels(px, 0, w, 0, 0, w, rows)
          used++
          Align.accumulate(px, w, rows, top + moveY - y0, moveX, night, accR, accG, accB, accW)
        }
        bmp.recycle()
      }
      if (used == 0) break
      for (i in 0 until n) {
        val wt = if (accW[i] > 0.0) accW[i] else 1.0
        val r = (accR[i] / wt).toInt().coerceIn(0, 255)
        val g = (accG[i] / wt).toInt().coerceIn(0, 255)
        val b = (accB[i] / wt).toInt().coerceIn(0, 255)
        strip[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
      out.setPixels(strip, 0, w, 0, y0, w, th)
      y0 += tileH
    }
    decoders.forEach { runCatching { it?.recycle() } }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    out.recycle()
    return bos.toByteArray()
  }

  /**
   * How far each frame of a burst has to move to sit on the first one, in full-resolution pixels.
   * Measured on half-size copies: up to 24 px of hand shake, to the nearest 2 px.
   */
  private fun nightMoves(frames: List<ByteArray>): List<Pair<Int, Int>> {
    val opts = BitmapFactory.Options().apply { inSampleSize = ALIGN_SAMPLE }
    var ref: IntArray? = null
    var refW = 0
    var refH = 0
    return frames.map { bytes ->
      val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
        ?: return@map 0 to 0
      val w = bmp.width
      val h = bmp.height
      val gray = grayOf(bmp)
      bmp.recycle()
      val r = ref
      when {
        r == null -> {
          ref = gray
          refW = w
          refH = h
          0 to 0
        }
        w != refW || h != refH -> 0 to 0
        else -> {
          // estimateShift says where the first frame's content sits in this one; moving it back is the negative
          val (dx, dy) = Align.estimateShift(r, gray, w, h, maxShift = 12)
          -dx * ALIGN_SAMPLE to -dy * ALIGN_SAMPLE
        }
      }
    }
  }

  /** Portrait: keep the centre sharp, blur everything outside an oval. */
  private fun blurBackground(bytes: ByteArray): ByteArray {
    // Decode at half resolution: a full 12 MP ARGB bitmap is ~48 MB and two of them plus the
    // blurred copy is enough to OOM a phone that is already holding camera buffers.
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull() ?: return bytes
    val w = src.width
    val h = src.height
    // Blur by halving four times and doubling back up. Each halving averages 2x2 pixels and each doubling blends
    // neighbours, so the result is smooth; one big jump (the old w/42) samples a few pixels and comes back as blocks.
    var blurred = src
    repeat(BLUR_STEPS) {
      val next = Bitmap.createScaledBitmap(blurred, (blurred.width / 2).coerceAtLeast(1), (blurred.height / 2).coerceAtLeast(1), true)
      if (blurred !== src) blurred.recycle()
      blurred = next
    }
    repeat(BLUR_STEPS) { i ->
      val last = i == BLUR_STEPS - 1
      val next = Bitmap.createScaledBitmap(blurred, if (last) w else blurred.width * 2, if (last) h else blurred.height * 2, true)
      blurred.recycle()
      blurred = next
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(blurred, 0f, 0f, null)
    // The subject on top: an oval that is fully sharp in its middle and fades into the blur at its rim.
    val oval = RectF(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.92f)
    val layer = canvas.saveLayer(null, null)
    canvas.drawBitmap(src, 0f, 0f, null)
    val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
      shader = android.graphics.RadialGradient(
        0.5f, 0.5f, 0.5f,
        intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
        floatArrayOf(0f, 0.65f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
      ).apply {
        // a unit circle stretched over the oval
        setLocalMatrix(Matrix().apply { setScale(oval.width(), oval.height()); postTranslate(oval.left, oval.top) })
      }
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fade)
    canvas.restoreToCount(layer)

    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    src.recycle()
    blurred.recycle()
    out.recycle()
    return bos.toByteArray()
  }

  /** HDR: bracket three exposures around the metered one and fuse by mid-tone weight. */
  private fun captureHdr() {
    val cam = device ?: return
    val s = session ?: return
    val r = reader ?: return
    hdrFrames.clear()
    val base = lastAeExposureNs ?: state.exposureNs
    val iso = lastAeIso ?: state.iso
    val exps = listOf(base / 4, base, base * 4).map {
      it.coerceIn(state.exposureRange.first, state.exposureRange.last)
    }
    try {
      for (e in exps) {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        applyCommon(b)
        // exposure by hand; white balance frozen so the three frames share one colour
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
        lastJpegRotation = jpegOrientation()
        lastFlip = flipped()
        b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
        applyStillExtras(b)
        s.capture(b.build(), stillCallback, handler)
      }
      state = state.copy(busy = true, status = "hdr: ${exps.size} eksposur")
    } catch (t: Throwable) {
      Log.e(TAG, "hdr failed", t)
      state = state.copy(status = "hdr gagal: ${t.message}")
    }
  }
  // ---------------------------------------------------------------- video

  private var takeUri: android.net.Uri? = null
  private var takePfd: android.os.ParcelFileDescriptor? = null
  private var videoNote = ""

  /** MediaStore row and descriptor for a new take. */
  private fun newTake(suffix: String): android.os.ParcelFileDescriptor {
    val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + suffix + ".mp4"
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, name)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CoverCam")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
      ?: throw java.io.IOException("penyimpanan menolak berkas baru")
    takeUri = uri
    val pfd = ctx.contentResolver.openFileDescriptor(uri, "w")
      ?: throw java.io.IOException("berkas video tidak bisa dibuka")
    takePfd = pfd
    return pfd
  }

  /** A prepared MediaRecorder: H.264, plus AAC when the microphone is allowed. */
  private fun newRecorder(pfd: android.os.ParcelFileDescriptor, size: Size, fps: Int, bitRate: Int): MediaRecorder {
    val audio = canRecordAudio()
    val rec = MediaRecorder(ctx)
    recorder = rec // before prepare(): a recorder that fails to prepare is still released by finishTake()
    if (audio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
    rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    rec.setOutputFile(pfd.fileDescriptor)
    rec.setVideoEncodingBitRate(bitRate)
    rec.setVideoFrameRate(fps)
    rec.setVideoSize(size.width, size.height)
    rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    if (audio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    // the encoder gets the sensor's own landscape frames; without this hint every clip plays on its side
    rec.setOrientationHint(jpegOrientation())
    rec.prepare()
    recorderSurface = rec.surface
    return rec
  }

  /** The fixed [fps, fps] range when the HAL lists one; null leaves the template's own range in place. */
  private fun fixedFpsRange(ch: CameraCharacteristics, fps: Int): Range<Int>? =
    ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
      ?.firstOrNull { it.lower == fps && it.upper == fps }

  /** The biggest recorder size of the chosen ratio inside [within]; the take is stored in the shape the screen shows. */
  private fun pickVideoSize(recorderSizes: Array<Size>, within: Size): Size =
    PreviewGeometry.fitSize(recorderSizes.map { it.width to it.height }, shownRatio.aspect, within.width, within.height)
      ?.let { Size(it.first, it.second) } ?: within

  fun startRecording(): Boolean {
    val cam = device ?: return false
    if (state.recording || recorder != null) return true
    if (previewSurface == null) return false
    try {
      val pfd = newTake("")
      val ch = manager.getCameraCharacteristics(cam.id)
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val supported = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
      // 1088 tall, not 1080: the square size this HAL lists is 1088x1088
      val target = if (video4k) Size(3840, 2160) else Size(1920, 1088)
      val size = pickVideoSize(supported, target)
      // 60 only where the sensor really runs it: otherwise the file would claim 60 and hold 30
      val fps = if (video60 && video60Available) 60 else 30
      recordFps = fps
      videoFps = fixedFpsRange(ch, fps)
      videoNote = (if (video60 && fps != 60) " (60 fps hanya lensa 1.0x)" else "") +
        (if (video4k && size.width < 3840) " (4K hanya 16:9)" else "")
      Log.i(TAG, "video " + size.width + "x" + size.height + "@" + fps + " range=" + videoFps + " choices=" + supported.size)
      newRecorder(pfd, size, fps, (if (size.width >= 3840) 48_000_000 else 20_000_000) * (fps / 30))

      val camId = cam.id
      closeDevice(keepPreview = true)
      openForVideo(camId)
      return true
    } catch (t: Throwable) {
      Log.e(TAG, "record start failed", t)
      finishTake()
      state = state.copy(status = "rekaman gagal: ${t.message}")
      return false
    }
  }

  private fun openForVideo(id: String) {
    val preview = previewSurface ?: return endTake("rekaman gagal: preview hilang")
    try {
      val token = ++openToken
      manager.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
          if (token != openToken) {
            cam.close()
            return
          }
          device = cam
          sizePreviewBuffer()
          recordTargets = listOfNotNull(preview, recorderSurface)
          val config = android.hardware.camera2.params.SessionConfiguration(
            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
            recordTargets.map { android.hardware.camera2.params.OutputConfiguration(it) },
            java.util.concurrent.Executor { r -> handler.post(r) },
            object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(s: CameraCaptureSession) {
                if (device !== cam) return
                session = s
                try {
                  reportedFps = 0
                  s.setRepeatingRequest(buildRecordRequest(cam), rateReport.takeIf { recordFps == 60 }, handler)
                  recorder?.start()
                  // "recording" is set here, once frames really flow, not when the button was pressed
                  state = state.copy(
                    recording = true,
                    recordingPaused = false,
                    status = (if (canRecordAudio()) "merekam" else "merekam (tanpa audio)") + videoNote,
                  )
                } catch (t: Throwable) {
                  Log.e(TAG, "recorder start failed", t)
                  endTake("rekaman gagal: ${t.message}")
                }
              }

              override fun onConfigureFailed(s: CameraCaptureSession) = endTake("sesi video gagal")
            },
          )
          try {
            // the recording keys only count when the session is built with them
            if (recordFps == 60) config.sessionParameters = buildRecordRequest(cam)
            cam.createCaptureSession(config)
          } catch (t: Throwable) {
            Log.e(TAG, "video session failed", t)
            endTake("sesi video gagal: ${t.message}")
          }
        }

        override fun onDisconnected(cam: CameraDevice) {
          cam.close()
          if (token == openToken) cameraLost("kamera dipakai aplikasi lain")
        }

        override fun onError(cam: CameraDevice, error: Int) {
          cam.close()
          if (token == openToken) endTake("video camera error $error")
        }
      }, handler)
    } catch (t: Throwable) {
      Log.e(TAG, "openForVideo failed", t)
      endTake("rekaman gagal: ${t.message}")
    }
  }

  /** Stops any take: video, slow motion or HDR video. */
  fun stopRecording() = endTake(null)

  /** Ends the take, says how it went ([failure]: it never got going) and goes back to the photo session. */
  private fun endTake(failure: String?) {
    handler.removeCallbacks(slowArmTimeout)
    val hadTake = takeUri != null
    val saved = finishTake()
    state = state.copy(
      recording = false,
      recordingPaused = false,
      slowPreview = false,
      lastThumb = saved ?: state.lastThumb,
      status = failure ?: when {
        saved != null -> "video tersimpan"
        hadTake -> "rekaman kosong: kamera tidak mengirim frame"
        else -> state.status
      },
    )
    closeDevice()
    openCurrent()
  }

  /**
   * Stops whichever recorder is running and releases it. Returns the saved file; null when there was no take, or
   * it held no frames (such a take still leaves a few KB of container behind: that file is deleted).
   */
  private fun finishTake(): android.net.Uri? {
    hdrRecorder?.let { rec ->
      hdrDraining = false
      handler.removeCallbacks(hdrDrainTick)
      // stop feeding the encoder before asking it to finish
      runCatching { session?.stopRepeating() }
      runCatching { session?.close() }
      session = null
      runCatching { rec.stop() }.onFailure { Log.w(TAG, "hdr recorder stop", it) }
      hdrRecorder = null
    }
    recorder?.let { rec -> runCatching { rec.stop() }.onFailure { Log.w(TAG, "recorder stop", it) } }
    stopRecordingSafe()
    recordTargets = emptyList()
    val uri = takeUri ?: return null
    takeUri = null
    val size = runCatching {
      ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: return uri
    if (size >= MIN_VALID_VIDEO_BYTES) return uri
    runCatching { ctx.contentResolver.delete(uri, null, null) }
      .onFailure { Log.w(TAG, "empty take cleanup failed", it) }
    Log.w(TAG, "take produced $size bytes - deleted")
    return null
  }

  private fun stopRecordingSafe() {
    try { recorder?.reset() } catch (_: Throwable) {}
    try { recorder?.release() } catch (_: Throwable) {}
    recorder = null
    try { recorderSurface?.release() } catch (_: Throwable) {}
    recorderSurface = null
    try { takePfd?.close() } catch (_: Throwable) {}
    takePfd = null
  }

  // ---------------------------------------------------------------- slow motion

  private var hsSize: Size? = null
  private var hsFps = 0
  private var hsFpsChoices: List<Int> = emptyList()

  /** High-speed configuration the UI must mirror for its preview surface. */
  val slowMotionSize: Size? get() = hsSize
  val slowMotionFps: Int get() = hsFps
  val slowMotionFpsChoices: Int get() = hsFpsChoices.size

  /** Picks the best high-speed configuration the HAL advertises for this camera. */
  private fun probeHighSpeed(ch: CameraCharacteristics) {
    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
    val sizes = map.highSpeedVideoSizes ?: emptyArray()
    val upTo1080 = sizes.filter { it.width <= 1920 && it.height <= 1080 }
    // measured list of the main lens: 1920x1080 and 1280x720 (16:9), 1920x824 (the wide shape)
    val wide = upTo1080.filter { it.width.toDouble() / it.height > 2.0 }
    hsSize = (if (prefs.getBoolean("slow21", false)) wide else upTo1080 - wide.toSet())
      .maxByOrNull { it.width.toLong() * it.height }
      ?: sizes.firstOrNull()
    // each shape has its own rates (1080p: 120 and 240, the wide one: 120); the chosen one is kept as far as it exists
    hsFpsChoices = hsSize?.let { s ->
      map.getHighSpeedVideoFpsRangesFor(s)?.map { it.upper }?.distinct()?.sorted()
    } ?: emptyList()
    hsFps = hsFpsChoices.lastOrNull { it <= prefs.getInt("slowfps", Int.MAX_VALUE) } ?: hsFpsChoices.firstOrNull() ?: 0

    Log.i(TAG, "highSpeed size=" + hsSize + " fps=" + hsFps + " choices=" + hsFpsChoices)
  }

  /** The other rate of the open slow-motion size; one lens-size pair with a single rate has nothing to switch to. */
  fun cycleSlowFps() {
    if (state.recording || state.slowPreview) return
    val next = hsFpsChoices.firstOrNull { it > hsFps } ?: hsFpsChoices.firstOrNull() ?: return
    hsFps = next
    prefs.edit().putInt("slowfps", next).apply()
    state = state.copy(status = "slowmo ${next}fps")
  }

  /**
   * Arms a slow-motion take. The high-speed session needs its preview surface the moment it is created, and that
   * surface exists only once the UI has mounted it, so the take itself starts in [setSlowPreviewSurface].
   */
  fun startSlowMotion(): Boolean {
    // The front sensor advertises 1080p@120 (and 720p@120) yet its HAL never delivers frames to
    // the recorder - every take ends as a 0x0 video track. Refuse instead of writing a broken file.
    if (!state.facingBack) {
      state = state.copy(status = "slowmo tidak didukung kamera depan")
      return false
    }
    if (device == null || hsSize == null || hsFps <= 0) return false
    if (state.recording || state.slowPreview) return true
    state = state.copy(slowPreview = true, status = "menyiapkan slowmo")
    handler.postDelayed(slowArmTimeout, 2_000)
    return true
  }

  /** The preview surface never came: give up instead of leaving the record button dead. */
  private val slowArmTimeout = Runnable {
    if (state.slowPreview && recorder == null) endTake("slowmo gagal: preview tidak siap")
  }

  private fun beginSlowMotion() {
    handler.removeCallbacks(slowArmTimeout)
    val cam = device
    val hs = hsSize
    if (cam == null || hs == null) return endTake("slowmo gagal: kamera belum siap")
    try {
      val rec = newRecorder(newTake("_slow"), hs, hsFps, 60_000_000)
      Log.i(TAG, "slowmo recording " + hs.width + "x" + hs.height + "@" + hsFps)

      // A constrained high-speed session only accepts surfaces whose size is in the published
      // high-speed list (here 1280x720 / 1920x1080): the recorder, and the SurfaceView the UI sized to match.
      val surfaces = listOfNotNull(rec.surface, slowPreviewSurface)
      val outputConfigs = surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) }
      val highSpeedCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
          if (device !== cam) return
          session = s
          try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            surfaces.forEach { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(hsFps, hsFps))
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            val list = (s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession)
              .createHighSpeedRequestList(b.build())
            s.setRepeatingBurst(list, null, handler)
            recorder?.start()
            state = state.copy(
              recording = true,
              recordingPaused = false,
              status = "slowmo " + hsFps + "fps",
            )
          } catch (t: Throwable) {
            Log.e(TAG, "slowmo start failed", t)
            endTake("slowmo gagal: " + t.message)
          }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) = endTake("sesi slowmo gagal")
      }
      cam.createCaptureSession(
        android.hardware.camera2.params.SessionConfiguration(
          android.hardware.camera2.params.SessionConfiguration.SESSION_HIGH_SPEED,
          outputConfigs,
          java.util.concurrent.Executor { r -> handler.post(r) },
          highSpeedCallback,
        ),
      )
    } catch (t: Throwable) {
      Log.e(TAG, "slowmo failed", t)
      endTake("slowmo gagal: " + t.message)
    }
  }

  // ---------------------------------------------------------------- HDR video (experimental)

  private var hdrRecorder: HdrVideoRecorder? = null
  private var hdrDraining = false
  private val hdrDrainTick = object : Runnable {
    override fun run() {
      if (!hdrDraining) return
      runCatching { hdrRecorder?.drain() }
      handler.postDelayed(this, 16) // one frame at 60 fps
    }
  }

  private var reportedFps = 0

  /** Says the rate the sensor really runs at, asked for at 60: a lens that ignores the keys stays at 30. */
  private val rateReport = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
      val ns = result.get(CaptureResult.SENSOR_FRAME_DURATION)?.takeIf { it > 0 } ?: return
      val fps = Math.round(1e9 / ns).toInt()
      if (fps == reportedFps || !state.recording) return
      reportedFps = fps
      state = state.copy(status = "merekam ${if (logActive) "log " else ""}$fps fps")
    }
  }

  fun startHdrVideo(): Boolean {
    val cam = device ?: return false
    if (state.recording || hdrRecorder != null) return true
    val preview = previewSurface ?: return false
    try {
      val pfd = newTake("_hdr")
      val muxer = android.media.MediaMuxer(pfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      muxer.setOrientationHint(jpegOrientation())
      val log = logActive
      val map = manager.getCameraCharacteristics(cam.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val size = pickVideoSize(map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray(), Size(1920, 1088))
      val fps = if (hdr60 && video60Available) 60 else 30
      recordFps = fps
      val rec = HdrVideoRecorder(size.width, size.height, fps, muxer, sdrTag = log)
      hdrRecorder = rec // before prepare(): an encoder that fails to start is still released by finishTake()
      val surface = rec.prepare()
      recordTargets = listOf(preview, surface)

      sizePreviewBuffer()
      val previewCfg = android.hardware.camera2.params.OutputConfiguration(preview)
      val videoCfg = android.hardware.camera2.params.OutputConfiguration(surface)
      runCatching {
        videoCfg.setDynamicRangeProfile(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)
      }.onFailure { Log.w(TAG, "HLG10 profile rejected, falling back to SDR surface", it) }

      val config = android.hardware.camera2.params.SessionConfiguration(
        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
        listOf(previewCfg, videoCfg),
        java.util.concurrent.Executor { r -> handler.post(r) },
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(s: CameraCaptureSession) {
            if (device !== cam) return
            session = s
            try {
              reportedFps = 0
              s.setRepeatingRequest(buildRecordRequest(cam), rateReport.takeIf { fps == 60 }, handler)
            } catch (t: Throwable) {
              Log.e(TAG, "hdr repeating failed", t)
              return endTake("hdr gagal: " + t.message)
            }
            hdrDraining = true
            handler.post(hdrDrainTick)
            state = state.copy(recording = true, recordingPaused = false, status = if (log) "merekam log" else "merekam hdr")
          }

          override fun onConfigureFailed(s: CameraCaptureSession) = endTake("sesi hdr gagal")
        },
      )
      // the recording keys only count when the session is built with them
      if (fps == 60) config.sessionParameters = buildRecordRequest(cam)
      cam.createCaptureSession(config)
      return true
    } catch (t: Throwable) {
      Log.e(TAG, "hdr video failed", t)
      endTake("hdr gagal: " + t.message)
      return false
    }
  }

  // ---------------------------------------------------------------- transform

  /** -1 until the IMU reports a stable quadrant; falls back to the display rotation. */
  private var imuRotation = -1

  /** Reference quadrant captured the first time IMU rotation is used, so the phone
   *  position at that moment becomes "normal" and needs no manual calibration. */
  var imuReference: Int
    get() = prefs.getInt("imuref", Int.MIN_VALUE)
    private set(v) = prefs.edit().putInt("imuref", v).apply()

  fun setDeviceRotation(degrees: Int) {
    if (!imuRotationOn) return
    if (degrees < 0) { clearDeviceRotation(); return }
    val raw = ((degrees % 360) + 360) % 360
    if (imuReference == Int.MIN_VALUE) imuReference = raw
    val norm = ((raw - imuReference) % 360 + 360) % 360
    if (norm == imuRotation) return
    imuRotation = norm
    applyTransform()
    state = state.copy(status = "imu=$imuRotation rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  /** Flat on a table: gravity cannot tell which edge is up, so fall back to the display. */
  fun clearDeviceRotation() {
    if (imuRotation == -1) return
    imuRotation = -1
    applyTransform()
    state = state.copy(status = "imu=none rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  private fun displayRotationDegrees(): Int {
    val rotation = textureView?.display?.rotation ?: Surface.ROTATION_0
    return when (rotation) {
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> 0
    }
  }

  private fun deviceRotationDegrees(): Int =
    if (imuRotation >= 0) imuRotation else displayRotationDegrees()

  private fun baseRotation(): Int {
    val deviceDeg = deviceRotationDegrees()
    return (sensorOrientation - deviceDeg + 360) % 360
  }

  fun previewRotation(): Int = (baseRotation() + extraFor(currentId) + 360) % 360

  /**
   * Clockwise turn that stands the sensor image upright the way the preview shows it (its mirror aside): photos,
   * DNGs and videos all carry this one value.
   *
   * The TextureView content already carries the sensor orientation and previewRotation() turns it further, so a
   * back-facing sensor ends up turned by their sum; a front-facing one is mirrored first, which reverses the turn.
   * With the calibrated defaults this is Camera2's own JPEG_ORIENTATION formula (sensor -/+ display rotation).
   *
   * Measured on the Flip5: id 0 reports sensor=90. A fixed "+270" stored its photos upside-down against the preview
   * and against the DNGs (whose separate setting had been tuned to 90); it only fits a sensor mounted at 270.
   */
  fun jpegOrientation(): Int =
    if (lensFront) {
      (sensorOrientation - previewRotation() + 360) % 360
    } else {
      (previewRotation() + sensorOrientation) % 360
    }

  /**
   * The preview is the sensor picture flipped left-right, and stills are stored the same way: what you see is what
   * is saved.
   *
   * Measured on the Flip5: the HAL hands out the main lens (id 0) already mirrored (in a photo of a whiteboard the
   * writing ran backwards and the clock counted anticlockwise), the ultrawide (id 2) is true. That is why id 0 has
   * its mirror on by default. A front-facing lens is mirrored by the framework in the preview only, so there the
   * mirror setting undoes it and nothing is flipped in the file.
   */
  private fun flipped(): Boolean = mirrorFor(currentId) != lensFront

  /** EXIF orientation of the shot being taken: [lastJpegRotation] plus the flip, both fixed at shutter time. */
  private fun lastExif(): Int = PreviewGeometry.exifOrientation(lastJpegRotation, lastFlip)

  fun bumpRotation() {
    prefs.edit().putInt("extra_$currentId", (extraFor(currentId) + 90) % 360).apply()
    applyTransform()
    state = state.copy(status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  fun toggleMirror() {
    prefs.edit().putBoolean("mirror_$currentId", !mirrorFor(currentId)).apply()
    applyTransform()
    state = state.copy(status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  private fun applyTransform() {
    val tv = textureView ?: return
    val w = tv.width.toFloat()
    val h = tv.height.toFloat()
    if (w == 0f || h == 0f) return
    val rotation = previewRotation()
    // TextureView stretches the 4:3 buffer over the whole (nearly square) view. Scale it back to the picture's
    // real shape first, then turn and mirror it as calibrated: the picture is fitted, never squeezed.
    val sensorAspect = previewSize.width.toFloat() / previewSize.height
    val (pw, ph) = PreviewGeometry.pictureSize(w, h, sensorAspect, jpegOrientation())
    val (sx, sy) = PreviewGeometry.contentScale(w, h, pw, ph, rotation)
    val aspect = PreviewGeometry.pictureAspect(sensorAspect, jpegOrientation())
    pictureW = pw
    pictureH = ph
    val m = Matrix()
    m.postScale(sx, sy, w / 2f, h / 2f)
    m.postRotate(rotation.toFloat(), w / 2f, h / 2f)
    if (mirrorFor(currentId)) m.postScale(-1f, 1f, w / 2f, h / 2f)
    tv.setTransform(m)
    if (sx != state.previewScaleX || sy != state.previewScaleY || aspect != state.pictureAspect) {
      state = state.copy(previewScaleX = sx, previewScaleY = sy, pictureAspect = aspect)
    }
  }

  private fun writeExifOrientation(uri: android.net.Uri, tag: Int) {
    runCatching {
      ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val exif = android.media.ExifInterface(pfd.fileDescriptor)
        exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, tag.toString())
        exif.saveAttributes()
      }
    }.onFailure { Log.w(TAG, "exif write failed", it) }
  }

  private fun grayOf(bmp: Bitmap): IntArray {
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return Align.toGray(px)
  }

  /** The largest stream up to 1920 px in the shape of [like]; a lens without that shape gives its largest one. */
  private fun pickPreviewSize(sizes: Array<Size>, like: Size): Size {
    if (sizes.isEmpty()) return Size(1920, 1080)
    val fits = sizes.filter { it.width <= 1920 && it.height <= 1920 }
    val shape = like.width.toDouble() / like.height
    return fits.filter { abs(it.width.toDouble() / it.height - shape) < 0.06 }.ifEmpty { fits }
      .maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height }!!
  }

  private fun pickJpegSize(sizes: Array<Size>, ratio: Ratio): Size {
    if (sizes.isEmpty()) return Size(4000, 3000)
    val matching = sizes.filter {
      val r = it.width.toDouble() / it.height
      abs(r - ratio.aspect) < 0.06
    }
    return (matching.ifEmpty { sizes.toList() }).maxByOrNull { it.width.toLong() * it.height }!!
  }

  companion object {
    private const val TAG = "CoverCam"
    private const val FRAME_SLACK_NS = 10_000_000L

    /**
     * The longest shutter of each lens as Samsung lists it (`samsung.android.sensor.info.exposureTimeRange` in
     * `dumpsys media.camera`, Flip5, 2026-11-10). The public range stops at 0.1 s (main) and 0.2 s (front); the vendor
     * key itself is filtered out for apps, so the numbers are kept here. The ultrawide's 0.6 s is below its public 0.67 s.
     */
    private val LONGEST_EXPOSURE_NS = mapOf("0" to 30_000_000_000L, "1" to 30_000_000_000L)
    private const val NIGHT_FRAMES = 5
    private const val HDR_FRAMES = 3
    private val DEFAULT_EXTRA = mapOf("2" to 90, "0" to 270)
    private val DEFAULT_MIRROR = setOf("0", "1", "3")
    private const val PANO_TARGET = 3
    private const val ALIGN_SAMPLE = 2
    private const val BLUR_STEPS = 4
    private const val MIN_VALID_VIDEO_BYTES = 100_000L
    private val RAW_MODES = setOf(Mode.PHOTO, Mode.PRO)

    /** Vendor keys (int32) of Samsung's HAL, in the session parameters and in every request of the take. */
    private val SAMSUNG_RECORDING_FPS =
      listOf("samsung.android.control.recordingMaxFps", "samsung.android.control.recordingMinFps")
  }
}
