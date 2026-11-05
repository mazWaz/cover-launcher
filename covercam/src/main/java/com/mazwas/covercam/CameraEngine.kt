package com.mazwas.covercam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
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
 * The package is com.samsung.android.ruler on purpose - Samsung's cameraserver keeps hidden
 * physical devices behind a package allowlist, the same mechanism Samsung GCam mods use.
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
    val manual: Boolean = false,
    val lastThumb: android.net.Uri? = null,
    val busy: Boolean = false,
  )

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
  private var activeArray: Rect? = null

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
      state = state.copy(status = "timelapse stopped")
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

  fun cycleVideoRes() {
    video4k = !video4k
    state = state.copy(status = if (video4k) "video 4K" else "video 1080p")
  }

  fun cycleVideoFps() {
    video60 = !video60
    state = state.copy(status = if (video60) "video 60fps" else "video 30fps")
  }

  /** Tap the record button while recording to pause/resume. */
  fun pauseOrResumeRecording() {
    val rec = recorder ?: return
    try {
      if (state.recordingPaused) {
        rec.resume()
        state = state.copy(recordingPaused = false, status = "recording")
      } else {
        rec.pause()
        state = state.copy(recordingPaused = true, status = "rec paused")
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

  private val extraById = mutableMapOf("2" to 90, "0" to 270)
  private val mirrorById = mutableMapOf("0" to true, "1" to true, "3" to true)

  private fun extraFor(id: String): Int = extraById[id] ?: 90
  private fun mirrorFor(id: String): Boolean = mirrorById[id] ?: false
  private var currentId: String = "0"
  private val prefs = ctx.getSharedPreferences("covercam", Context.MODE_PRIVATE)
  private var aeLock = false

  enum class Ratio(val label: String) { R4_3("4:3"), R16_9("16:9"), R1_1("1:1") }

  var ratio: Ratio = Ratio.R4_3
    private set

  init {
    val savedMode = runCatching { Mode.valueOf(prefs.getString("mode", Mode.PHOTO.name)!!) }
      .getOrDefault(Mode.PHOTO)
    ratio = runCatching { Ratio.valueOf(prefs.getString("ratio", Ratio.R4_3.name)!!) }
      .getOrDefault(Ratio.R4_3)
    aeLock = prefs.getBoolean("aelock", false)
    zoomRatio = prefs.getFloat("zoom", 1f)
    state = state.copy(
      mode = savedMode,
      manual = savedMode == Mode.PRO,
      timerSeconds = prefs.getInt("timer", 0),
      wbMode = prefs.getInt("wb", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    )
  }

  fun currentZoom(): Float = zoomRatio

  /**
   * 720p surface used only while recording slow motion: a constrained high-speed session
   * rejects the 1920x1440 TextureView, but a SurfaceView with a fixed buffer size fits the
   * published list, so the preview stays live.
   */
  fun setSlowPreviewSurface(surface: Surface?) {
    slowPreviewSurface = surface
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
    if (device == null) {
      openRetries = 0
      openCurrent()
    }
  }

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
      openCurrent()
    }

  var imuRotationOn: Boolean
    get() = prefs.getBoolean("imurot", false)
    set(v) {
      prefs.edit().putBoolean("imurot", v).apply()
      if (!v) clearDeviceRotation()
    }

  var dngRotation: Int
    get() = prefs.getInt("dngrot", 90)
    set(v) {
      prefs.edit().putInt("dngrot", ((v % 360) + 360) % 360).apply()
    }

  fun cycleDngRotation() {
    dngRotation = (dngRotation + 90) % 360
    state = state.copy(status = "dng rot ${dngRotation}deg")
  }

  var levelOn: Boolean
    get() = prefs.getBoolean("level", true)
    set(v) = prefs.edit().putBoolean("level", v).apply()

  /** Restore the per-lens calibration table to its verified defaults. */
  fun resetCalibration() {
    dngRotation = 90
    extraById.clear()
    extraById["2"] = 90
    extraById["0"] = 270
    mirrorById.clear()
    mirrorById["0"] = true
    mirrorById["1"] = true
    mirrorById["3"] = true
    openCurrent()
  }

  fun cycleRatio() {
    ratio = when (ratio) {
      Ratio.R4_3 -> Ratio.R16_9
      Ratio.R16_9 -> Ratio.R1_1
      Ratio.R1_1 -> Ratio.R4_3
    }
    prefs.edit().putString("ratio", ratio.name).apply()
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
      lenses = activeIds,
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
    "1", "3", "4" -> "SELF"
    else -> id
  }

  // ---------------------------------------------------------------- lifecycle

  fun attach(textureView: TextureView) {
    this.textureView = textureView
    val listener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) = openCurrent()
      override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
      override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
      override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
    }
    if (textureView.isAvailable) openCurrent() else textureView.surfaceTextureListener = listener
  }

  fun close() {
    timelapseRunning = false
    handler.removeCallbacks(timelapseTick)
    opening = false
    stopRecordingSafe()
    closeDevice()
  }

  /** Closes the camera/reader but keeps the preview Surface alive (video hands it to a new session). */
  private fun closeDeviceKeepPreview() {
    try { session?.close() } catch (_: Throwable) {}
    session = null
    try { device?.close() } catch (_: Throwable) {}
    device = null
    try { reader?.close() } catch (_: Throwable) {}
    reader = null
    try { rawReader?.close() } catch (_: Throwable) {}
    rawReader = null
    state = state.copy(ready = false)
  }

  private fun closeDevice() {
    try { session?.close() } catch (_: Throwable) {}
    session = null
    try { device?.close() } catch (_: Throwable) {}
    device = null
    try { reader?.close() } catch (_: Throwable) {}
    reader = null
    try { rawReader?.close() } catch (_: Throwable) {}
    rawReader = null
    previewSurface?.release()
    previewSurface = null
    state = state.copy(ready = false)
  }

  private fun openCurrent() {
    val tv = textureView ?: return
    if (!tv.isAvailable) return
    val id = activeIds.getOrNull(state.lensIndex) ?: return
    if (opening) return
    opening = true
    currentId = id
    closeDevice()
    try {
      val ch = manager.getCameraCharacteristics(id)
      sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
      activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val previewSize = pickPreviewSize(map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray())
      jpegSize = pickJpegSize(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(), ratio)

      tv.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
      previewSurface = Surface(tv.surfaceTexture)
      if (dngOn) {
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

      readColorCalibration(ch)
      probeHighSpeed(ch)
      applyCharacteristicRanges(ch)
      Log.i(TAG, "open id=$id sensor=$sensorOrientation extra=${extraFor(id)} mirror=${mirrorFor(id)}")

      manager.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
          opening = false
          openRetries = 0
          device = cam
          createPreviewSession(cam)
        }
        override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
        override fun onError(cam: CameraDevice, error: Int) {
          opening = false
          cam.close(); device = null
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
    capVideoHdr = false
    colorMatrix1 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { toDoubles(it) }
    colorMatrix2 = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { toDoubles(it) }
    calibTransform1 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let { toDoubles(it) }
    calibTransform2 = ch.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { toDoubles(it) }
    forwardMatrix1 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { toDoubles(it) }
    forwardMatrix2 = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { toDoubles(it) }
    ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { illuminant1 = it.toInt() }
    ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { illuminant2 = it.toInt() }
    Log.i(TAG, "colour: m1=${colorMatrix1 != null} m2=${colorMatrix2 != null} fwd=${forwardMatrix1 != null} illum=$illuminant1")
  }

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
      exposureRange = expRange?.let { it.lower..it.upper } ?: 1_000_000L..1_000_000_000L,
      evRange = evRange?.let { it.lower..it.upper } ?: 0..0,
      minFocus = minFocus,
      status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}",
    )
    if (zoomRange != null) zoomRatio = zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
  }

  // ---------------------------------------------------------------- requests

  private fun buildPreviewRequest(cam: CameraDevice): CaptureRequest {
    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    previewSurface?.let { b.addTarget(it) }
    applyCommon(b)
    return b.build()
  }

  private fun applyCommon(b: CaptureRequest.Builder) {
    if (state.manual) {
      b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
      b.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
      b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, state.exposureNs)
      if (state.minFocus > 0f) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDiopters)
      }
    } else {
      b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
      if (state.evRange.last > state.evRange.first) {
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.ev)
      }
    }
    if (state.wbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) {
      b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
      b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
      b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(kelvin))
    } else {
      b.set(CaptureRequest.CONTROL_AWB_MODE, state.wbMode)
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
    val w = tv.width.toFloat()
    val h = tv.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    // Meter against the sensor's real active array, not an assumed 2000x2000 grid.
    val sensor = activeArray
    val sw = (sensor?.width() ?: 2000).toFloat()
    val sh = (sensor?.height() ?: 2000).toFloat()
    val ox = (sensor?.left ?: 0).toInt()
    val oy = (sensor?.top ?: 0).toInt()
    val halfW = (sw * 0.1f).toInt()
    val halfH = (sh * 0.1f).toInt()
    val cx = ox + (p.x / w * sw).toInt()
    val cy = oy + (p.y / h * sh).toInt()
    val rect = Rect(
      (cx - halfW).coerceIn(ox, ox + sw.toInt()),
      (cy - halfH).coerceIn(oy, oy + sh.toInt()),
      (cx + halfW).coerceIn(ox, ox + sw.toInt()),
      (cy + halfH).coerceIn(oy, oy + sh.toInt()),
    )
    if (rect.width() <= 0 || rect.height() <= 0) return null
    return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
  }

  private fun createPreviewSession(cam: CameraDevice) {
    val preview = previewSurface
    val still = reader?.surface ?: rawReader?.surface
    if (preview == null || still == null) return
    try {
      val config = android.hardware.camera2.params.SessionConfiguration(
        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
        listOf(
          android.hardware.camera2.params.OutputConfiguration(preview),
          android.hardware.camera2.params.OutputConfiguration(still),
        ),
        java.util.concurrent.Executor { r -> handler.post(r) },
        object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
          session = s
          try { s.setRepeatingRequest(buildPreviewRequest(cam), aeCallback, handler) } catch (t: Throwable) {
            Log.w(TAG, "repeating failed", t)
          }
          applyTransform()
          state = state.copy(
            ready = true,
            status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}",
          )
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

  fun refreshPreview() {
    val cam = device ?: return
    val s = session ?: return
    try {
      if (state.recording) {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        previewSurface?.let { b.addTarget(it) }
        recorderSurface?.let { b.addTarget(it) }
        applyCommon(b)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        s.setRepeatingRequest(b.build(), null, handler)
      } else {
        s.setRepeatingRequest(buildPreviewRequest(cam), null, handler)
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

  fun cycleMode() {
    val all = Mode.entries
    setMode(all[(state.mode.ordinal + 1) % all.size])
  }

  fun setMode(mode: Mode) {
    if (state.recording) stopRecording()
    if (mode == Mode.PRO && !state.manual) {
      // Start manual mode from whatever auto exposure had settled on, so the preview
      // does not jump to a dark frame the moment Pro is opened.
      val iso = lastAeIso?.coerceIn(state.isoRange.first, state.isoRange.last) ?: state.iso
      val exp = lastAeExposureNs?.coerceIn(state.exposureRange.first, state.exposureRange.last) ?: state.exposureNs
      state = state.copy(iso = iso, exposureNs = exp)
    }
    prefs.edit().putString("mode", mode.name).apply()
    Log.i(TAG, "mode=" + mode.name)
    state = state.copy(mode = mode, manual = mode == Mode.PRO)
    refreshPreview()
  }

  fun setZoom(value: Float) {
    zoomRatio = value.coerceIn(1.0f, 8.0f)
    prefs.edit().putFloat("zoom", zoomRatio).apply()
    refreshPreview()
  }

  fun tapFocus(x: Float, y: Float) {
    focusPoint = android.graphics.PointF(x, y)
    refreshPreview()
  }

  fun setIso(v: Int) { state = state.copy(iso = v.coerceIn(state.isoRange.first, state.isoRange.last)); refreshPreview() }
  fun setExposure(ns: Long) { state = state.copy(exposureNs = ns.coerceIn(state.exposureRange.first, state.exposureRange.last)); refreshPreview() }
  fun setFocus(d: Float) { state = state.copy(focusDiopters = d.coerceIn(0f, state.minFocus)); refreshPreview() }
  fun setEv(v: Int) { state = state.copy(ev = v.coerceIn(state.evRange.first, state.evRange.last)); refreshPreview() }
  /** Manual white balance: Kelvin -> RGGB gains (Tanner Helland approximation). */
  fun applyKelvin(k: Int) {
    kelvin = k
    state = state.copy(wbMode = CaptureRequest.CONTROL_AWB_MODE_OFF)
    refreshPreview()
  }

  private fun kelvinGains(k: Int): android.hardware.camera2.params.RggbChannelVector {
    val t = k / 100.0
    val r = if (t <= 66.0) 255.0 else 329.698727446 * Math.pow(t - 60.0, -0.1332047592)
    val g = if (t <= 66.0) 99.4708025861 * Math.log(t) - 161.1195681661 else 288.1221695283 * Math.pow(t - 60.0, -0.0755148492)
    val b = if (t >= 66.0) 255.0 else if (t <= 19.0) 0.0 else 138.5177312231 * Math.log(t - 10.0) - 305.0447927307
    val rr = r.coerceIn(1.0, 255.0) / 255.0
    val gg = g.coerceIn(1.0, 255.0) / 255.0
    val bb = b.coerceIn(1.0, 255.0) / 255.0
    val gr = (gg / rr).toFloat().coerceIn(0.2f, 5f)
    val gb = (gg / bb).toFloat().coerceIn(0.2f, 5f)
    return android.hardware.camera2.params.RggbChannelVector(gr, 1f, 1f, gb)
  }

  fun setWb(mode: Int) { prefs.edit().putInt("wb", mode).apply(); state = state.copy(wbMode = mode); refreshPreview() }

  // ---------------------------------------------------------------- photo

  fun capture() {
    when (state.mode) {
      Mode.NIGHT -> captureBurst()
      Mode.HDR -> captureHdr()
      else -> captureSingle()
    }
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
    if (dngOn) {
      val rr = rawReader ?: return
      try {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rr.surface)
        applyCommon(b)
        lastJpegRotation = jpegOrientation()
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
        }, handler)
        state = state.copy(busy = true, status = "dng…")
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
      applyCommon(b)
      lastJpegRotation = jpegOrientation()
      b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
      applyStillExtras(b)
      s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s2: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
          state = state.copy(status = "saved")
        }
      }, handler)
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
        b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
        applyStillExtras(b)
        s.capture(b.build(), null, handler)
      }
      state = state.copy(busy = true, status = "night: $NIGHT_FRAMES frames")
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
            Thread { saveBytes(processFullRes(frames, night = true), "night", lastJpegRotation) }.start()
          }
        }
        Mode.HDR -> {
          hdrFrames.add(bytes)
          if (hdrFrames.size >= HDR_FRAMES) {
            val fr = ArrayList(hdrFrames)
            hdrFrames.clear()
            Thread { saveBytes(processFullRes(fr, night = false), "hdr", lastJpegRotation) }.start()
          }
        }
        Mode.PORTRAIT -> Thread { saveBytes(blurBackground(bytes), "portrait", lastJpegRotation) }.start()
        Mode.PANORAMA -> {
          panoFrames.add(bytes)
          val count = panoFrames.size
          handler.post { state = state.copy(status = "pano $count/$PANO_TARGET") }
          if (count >= PANO_TARGET) {
            val frames = ArrayList(panoFrames)
            panoFrames.clear()
            Thread { saveBytes(stitchPanorama(frames), "panorama", lastJpegRotation) }.start()
          }
        }
        else -> Thread { saveBytes(bytes, "photo") }.start()
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
    try {
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
      val uri = DngWriter.write(
        ctx = ctx,
        image = image,
        make = make,
        model = model,
        blackLevel = black,
        whiteLevel = white,
        cfa = cfaPattern,
        orientationDegrees = dngRotation,
        asShotNeutral = neutral,
        previewJpeg = lastPreviewJpeg,
        color = DngWriter.RawColor(
          color1 = colorMatrix1,
          color2 = colorMatrix2,
          calib1 = calibTransform1,
          calib2 = calibTransform2,
          fwd1 = forwardMatrix1,
          fwd2 = forwardMatrix2,
          illum1 = illuminant1,
          illum2 = illuminant2,
        ),
      )
      handler.post {
        state = state.copy(
          busy = false,
          lastThumb = uri ?: state.lastThumb,
          status = if (uri != null) "dng saved" else "dng failed",
        )
      }
    } catch (t: Throwable) {
      Log.e(TAG, "dng write failed", t)
      handler.post { state = state.copy(busy = false, status = "dng failed: ${t.message}") }
    } finally {
      image.close()
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

  private fun saveBytes(bytes: ByteArray, kind: String, exifDegrees: Int? = null) {
    try {
      val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CoverCam")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      uri?.let { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
      if (uri != null && exifDegrees != null) writeExifOrientation(uri, exifDegrees)
      handler.post {
        state = state.copy(lastSaved = name, lastThumb = uri, busy = false, status = "$kind saved")
      }
    } catch (t: Throwable) {
      Log.e(TAG, "save failed", t)
      handler.post { state = state.copy(busy = false, status = "save failed: ${t.message}") }
    }
  }

  /**
   * Full-resolution merge without loading every frame at once: the image is decoded in
   * horizontal strips with BitmapRegionDecoder, so a 12 MP x 5-frame merge stays inside
   * a few tens of MB instead of hundreds.
   */
  private fun processFullRes(frames: List<ByteArray>, night: Boolean): ByteArray {
    val decoders = frames.mapNotNull {
      runCatching { android.graphics.BitmapRegionDecoder.newInstance(it, 0, it.size, false) }.getOrNull()
    }
    if (decoders.isEmpty()) return frames.first()
    val w = decoders[0].width
    val h = decoders[0].height
    if (w <= 0 || h <= 0) return frames.first()

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val tileH = 256
    val rect = Rect()
    var y0 = 0
    while (y0 < h) {
      val th = minOf(tileH, h - y0)
      val n = w * th
      val accR = DoubleArray(n)
      val accG = DoubleArray(n)
      val accB = DoubleArray(n)
      val accW = DoubleArray(n)
      var used = 0
      for (d in decoders) {
        rect.set(0, y0, w, y0 + th)
        val bmp = runCatching { d.decodeRegion(rect, BitmapFactory.Options()) }.getOrNull() ?: continue
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, th)
        bmp.recycle()
        used++
        for (i in 0 until n) {
          val c = px[i]
          val r = (c shr 16) and 0xFF
          val g = (c shr 8) and 0xFF
          val b = c and 0xFF
          val wt = if (night) 1.0 else {
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            Math.exp(-((lum - 0.5) * (lum - 0.5)) / 0.08).coerceAtLeast(0.02)
          }
          accR[i] += r * wt
          accG[i] += g * wt
          accB[i] += b * wt
          accW[i] += wt
        }
      }
      if (used == 0) break
      val strip = IntArray(n)
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
    decoders.forEach { runCatching { it.recycle() } }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    out.recycle()
    return bos.toByteArray()
  }
  private fun mergeNight(frames: List<ByteArray>): ByteArray {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val bitmaps = frames.mapNotNull {
      runCatching { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }.getOrNull()
    }
    if (bitmaps.isEmpty()) return frames.first()
    val w = bitmaps[0].width
    val h = bitmaps[0].height
    val acc = IntArray(w * h)
    val px = IntArray(w * h)
    var n = 0
    val refGray = grayOf(bitmaps[0])
    var ref = bitmaps[0]
    for (bmp in bitmaps) {
      if (bmp.width != w || bmp.height != h) continue
      var frame = bmp
      if (n > 0 && bmp !== ref) {
        val (dx, dy) = estimateShift(refGray, grayOf(bmp), w, h)
        if (dx != 0 || dy != 0) frame = shiftBitmap(bmp, dx, dy)
      }
      frame.getPixels(px, 0, w, 0, 0, w, h)
      n++
      for (i in acc.indices) {
        val c = px[i]
        if (n == 1) { acc[i] = c; continue }
        val a = acc[i]
        val r = (((a shr 16) and 0xFF) * (n - 1) + ((c shr 16) and 0xFF)) / n
        val g = (((a shr 8) and 0xFF) * (n - 1) + ((c shr 8) and 0xFF)) / n
        val b = ((a and 0xFF) * (n - 1) + (c and 0xFF)) / n
        acc[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
      if (frame !== bmp) frame.recycle()
      bmp.recycle()
    }
    val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    merged.setPixels(acc, 0, w, 0, 0, w, h)
    val out = merged
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    if (out !== merged) merged.recycle()
    out.recycle()
    return bos.toByteArray()
  }

  /** Portrait: keep the centre sharp, blur everything outside an oval. */
  private fun blurBackground(bytes: ByteArray): ByteArray {
    // Decode at half resolution: a full 12 MP ARGB bitmap is ~48 MB and two of them plus the
    // blurred copy is enough to OOM a phone that is already holding camera buffers.
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull() ?: return bytes
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, 0f, 0f, null)
    val small = Bitmap.createScaledBitmap(src, (w / 42).coerceAtLeast(4), (h / 42).coerceAtLeast(4), true)
    val blurred = Bitmap.createScaledBitmap(small, w, h, true)
    val mask = Path().apply {
      fillType = Path.FillType.EVEN_ODD
      addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
      addOval(RectF(w * 0.22f, h * 0.16f, w * 0.78f, h * 0.88f), Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(mask)
    canvas.drawBitmap(blurred, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    canvas.restore()
    val rotated = out
    val bos = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    src.recycle(); small.recycle(); blurred.recycle()
    if (rotated !== out) out.recycle()
    rotated.recycle()
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
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        lastJpegRotation = jpegOrientation()
        b.set(CaptureRequest.JPEG_ORIENTATION, lastJpegRotation)
        applyStillExtras(b)
        s.capture(b.build(), null, handler)
      }
      state = state.copy(busy = true, status = "hdr: ${exps.size} exposures")
    } catch (t: Throwable) {
      Log.e(TAG, "hdr failed", t)
      state = state.copy(status = "hdr failed: ${t.message}")
    }
  }

  private fun fuseHdr(frames: List<ByteArray>): ByteArray {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val bitmaps = frames.mapNotNull {
      runCatching { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }.getOrNull()
    }
    if (bitmaps.isEmpty()) return frames.first()
    val w = bitmaps[0].width
    val h = bitmaps[0].height
    val accR = DoubleArray(w * h)
    val accG = DoubleArray(w * h)
    val accB = DoubleArray(w * h)
    val accW = DoubleArray(w * h)
    val px = IntArray(w * h)
    for (bmp in bitmaps) {
      if (bmp.width != w || bmp.height != h) continue
      bmp.getPixels(px, 0, w, 0, 0, w, h)
      for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        // mid-tone weighting: well exposed pixels contribute most
        var wt = Math.exp(-((lum - 0.5) * (lum - 0.5)) / 0.08)
        if (wt < 0.02) wt = 0.02
        accR[i] += r * wt
        accG[i] += g * wt
        accB[i] += b * wt
        accW[i] += wt
      }
      bmp.recycle()
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val outp = IntArray(w * h)
    for (i in outp.indices) {
      val wt = if (accW[i] > 0.0) accW[i] else 1.0
      val r = (accR[i] / wt).toInt().coerceIn(0, 255)
      val g = (accG[i] / wt).toInt().coerceIn(0, 255)
      val b = (accB[i] / wt).toInt().coerceIn(0, 255)
      outp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    out.setPixels(outp, 0, w, 0, 0, w, h)
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
    out.recycle()
    return bos.toByteArray()
  }
  // ---------------------------------------------------------------- video

  fun startRecording(): Boolean {
    val cam = device ?: return false
    if (state.recording) return true
    val preview = previewSurface
    if (preview == null) return false
    try {
      val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
      val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CoverCam")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
      val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return false

      val ch = manager.getCameraCharacteristics(cam.id)
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val supported = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
      val target = if (video4k) Size(3840, 2160) else Size(1920, 1080)
      val size = supported
        .filter { it.width <= target.width && it.height <= target.height }
        .maxByOrNull { it.width.toLong() * it.height }
        ?: target
      val fps = if (video60) 60 else 30
      Log.i(TAG, "video " + size.width + "x" + size.height + "@" + fps + " choices=" + supported.size)

      val rec = MediaRecorder(ctx)
      if (canRecordAudio()) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
      rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setOutputFile(pfd.fileDescriptor)
      rec.setVideoEncodingBitRate(if (size.width >= 3840) 48_000_000 else 20_000_000)
      rec.setVideoFrameRate(fps)
      rec.setVideoSize(size.width, size.height)
      rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
      if (canRecordAudio()) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.prepare()
      recorder = rec
      recorderSurface = rec.surface

      val camId = cam.id
      closeDeviceKeepPreview()
      openForVideo(camId)
      return true
    } catch (t: Throwable) {
      Log.e(TAG, "record start failed", t)
      state = state.copy(status = "record failed: ${t.message}")
      stopRecordingSafe()
      return false
    }
  }

  private fun openForVideo(id: String) {
    val preview = previewSurface ?: return
    try {
      val ch = manager.getCameraCharacteristics(id)
      sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
      manager.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
          device = cam
          val surfaces = listOfNotNull(preview, recorderSurface)
          val config = android.hardware.camera2.params.SessionConfiguration(
            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
            surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) },
            java.util.concurrent.Executor { r -> handler.post(r) },
            object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
              session = s
              val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
              surfaces.forEach { b.addTarget(it) }
              applyCommon(b)
              b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
              s.setRepeatingRequest(b.build(), null, handler)
              try {
                recorder?.start()
                state = state.copy(
                  recording = true,
                  status = if (canRecordAudio()) "recording" else "recording (no audio)",
                )
              } catch (t: Throwable) {
                Log.e(TAG, "recorder start failed", t)
                state = state.copy(status = "recorder start failed: ${t.message}")
              }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
              state = state.copy(status = "video session failed")
            }
          },
          )
          cam.createCaptureSession(config)
        }
        override fun onDisconnected(cam: CameraDevice) { cam.close() }
        override fun onError(cam: CameraDevice, error: Int) {
          state = state.copy(status = "video camera error $error")
          cam.close()
        }
      }, handler)
    } catch (t: Throwable) {
      Log.e(TAG, "openForVideo failed", t)
      state = state.copy(status = "video open failed: ${t.message}")
    }
  }

  fun stopRecording() {
    try { recorder?.stop() } catch (t: Throwable) { Log.w(TAG, "recorder stop", t) }
    stopRecordingSafe()
    state = state.copy(recording = false, recordingPaused = false, status = "video saved")
    closeDevice()
    openCurrent()
  }

  // ---------------------------------------------------------------- slow motion

  private var hsSize: Size? = null
  private var hsFps = 0

  /** Picks the best high-speed configuration the HAL advertises for this camera. */
  private fun probeHighSpeed(ch: CameraCharacteristics) {
    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
    val sizes = map.highSpeedVideoSizes ?: emptyArray()
    hsSize = sizes
      .filter { it.width <= 1920 && it.height <= 1080 }
      .maxByOrNull { it.width.toLong() * it.height }
      ?: sizes.firstOrNull()
    hsFps = hsSize?.let { s ->
      map.getHighSpeedVideoFpsRangesFor(s)?.maxOfOrNull { it.upper } ?: 0
    } ?: 0
    Log.i(TAG, "highSpeed size=" + hsSize + " fps=" + hsFps)
  }

  fun startSlowMotion(): Boolean {
    val cam = device ?: return false
    val hs = hsSize ?: return false
    if (hsFps <= 0) return false
    if (state.recording) return true
    try {
      val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_slow.mp4"
      val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CoverCam")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
      val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return false

      val rec = MediaRecorder(ctx)
      if (canRecordAudio()) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
      rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setOutputFile(pfd.fileDescriptor)
      rec.setVideoEncodingBitRate(60_000_000)
      rec.setVideoFrameRate(hsFps)
      rec.setVideoSize(hs.width, hs.height)
      rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
      if (canRecordAudio()) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.prepare()
      recorder = rec
      recorderSurface = rec.surface
      Log.i(TAG, "slowmo recording " + hs.width + "x" + hs.height + "@" + hsFps)

      // A constrained high-speed session only accepts surfaces whose size is in the
      // published high-speed list (here 1280x720 / 1920x1080). The TextureView preview is
      // 1920x1440, so it cannot join: record-only, and the preview keeps its last frame.
      val surfaces = listOfNotNull(rec.surface, slowPreviewSurface)
      cam.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
          session = s
          val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
          surfaces.forEach { b.addTarget(it) }
          b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(hsFps, hsFps))
          b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
          b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
          try {
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
            state = state.copy(status = "slowmo failed: " + t.message)
            stopRecordingSafe()
          }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
          state = state.copy(status = "slowmo config failed")
          stopRecordingSafe()
        }
      }, handler)
      return true
    } catch (t: Throwable) {
      Log.e(TAG, "slowmo failed", t)
      state = state.copy(status = "slowmo failed: " + t.message)
      stopRecordingSafe()
      return false
    }
  }

  // ---------------------------------------------------------------- HDR video (experimental)

  private var hdrRecorder: HdrVideoRecorder? = null
  private var hdrDraining = false
  private val hdrDrainTick = object : Runnable {
    override fun run() {
      if (!hdrDraining) return
      runCatching { hdrRecorder?.drain() }
      handler.postDelayed(this, 40)
    }
  }

  fun startHdrVideo(): Boolean {
    val cam = device ?: return false
    if (state.recording) return true
    val preview = previewSurface ?: return false
    try {
      val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_hdr.mp4"
      val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CoverCam")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
      val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return false
      val muxer = android.media.MediaMuxer(
        pfd.fileDescriptor,
        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
      )
      val rec = HdrVideoRecorder(1920, 1080, 30, muxer)
      val surface = rec.prepare()
      hdrRecorder = rec

      val previewCfg = android.hardware.camera2.params.OutputConfiguration(preview)
      val videoCfg = android.hardware.camera2.params.OutputConfiguration(surface)
      runCatching {
        videoCfg.setDynamicRangeProfile(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)
      }.onFailure { Log.w(TAG, "HLG10 profile rejected, falling back to SDR surface", it) }

      cam.createCaptureSessionByOutputConfigurations(
        listOf(previewCfg, videoCfg),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(s: CameraCaptureSession) {
            session = s
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(preview)
            b.addTarget(surface)
            applyCommon(b)
            runCatching { s.setRepeatingRequest(b.build(), null, handler) }
              .onFailure { Log.e(TAG, "hdr repeating failed", it) }
            hdrDraining = true
            handler.post(hdrDrainTick)
            state = state.copy(recording = true, status = "hdr video recording")
          }

          override fun onConfigureFailed(s: CameraCaptureSession) {
            state = state.copy(status = "hdr session failed")
            stopHdrVideo()
          }
        },
        handler,
      )
      return true
    } catch (t: Throwable) {
      Log.e(TAG, "hdr video failed", t)
      state = state.copy(status = "hdr failed: " + t.message)
      stopHdrVideo()
      return false
    }
  }

  fun stopHdrVideo() {
    hdrDraining = false
    handler.removeCallbacks(hdrDrainTick)
    runCatching { session?.stopRepeating() }
    runCatching { session?.close() }
    session = null
    runCatching { hdrRecorder?.stop() }
    hdrRecorder = null
    state = state.copy(recording = false, recordingPaused = false, status = "hdr video saved")
    closeDevice()
    openCurrent()
  }

  private fun stopRecordingSafe() {
    try { recorder?.reset() } catch (_: Throwable) {}
    try { recorder?.release() } catch (_: Throwable) {}
    recorder = null
    try { recorderSurface?.release() } catch (_: Throwable) {}
    recorderSurface = null
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

  /** The JPEG buffer needs a further 90 degrees on this panel; verified on device. */
  fun jpegOrientation(): Int = (previewRotation() + 270) % 360

  fun bumpRotation() {
    extraById[currentId] = (extraFor(currentId) + 90) % 360
    applyTransform()
    state = state.copy(status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  fun toggleMirror() {
    mirrorById[currentId] = !mirrorFor(currentId)
    applyTransform()
    state = state.copy(status = "id=$currentId rot=${previewRotation()} mirror=${mirrorFor(currentId)}")
  }

  private fun applyTransform() {
    val tv = textureView ?: return
    val w = tv.width.toFloat()
    val h = tv.height.toFloat()
    if (w == 0f || h == 0f) return
    val m = Matrix()
    m.postRotate(previewRotation().toFloat(), w / 2f, h / 2f)
    if (mirrorFor(currentId)) m.postScale(-1f, 1f, w / 2f, h / 2f)
    tv.setTransform(m)
  }

  private fun writeExifOrientation(uri: android.net.Uri, degrees: Int) {
    val tag = when (((degrees % 360) + 360) % 360) {
      90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
      180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
      270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
      else -> android.media.ExifInterface.ORIENTATION_NORMAL
    }
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

  /** Coarse translation search (+/- SHIFT) on a subsampled grid; returns dx, dy. */
  private fun estimateShift(ref: IntArray, cur: IntArray, w: Int, h: Int): Pair<Int, Int> =
    Align.estimateShift(ref, cur, w, h)

  private fun shiftBitmap(bmp: Bitmap, dx: Int, dy: Int): Bitmap {
    val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(android.graphics.Color.BLACK)
    c.drawBitmap(bmp, dx.toFloat(), dy.toFloat(), null)
    return out
  }

  private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return bmp
    val m = Matrix()
    m.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  private fun pickPreviewSize(sizes: Array<Size>): Size {
    if (sizes.isEmpty()) return Size(1920, 1080)
    return sizes.filter { it.width <= 1920 && it.height <= 1920 }.maxByOrNull { it.width * it.height }
      ?: sizes.minByOrNull { it.width * it.height }!!
  }

  private fun pickJpegSize(sizes: Array<Size>, ratio: Ratio): Size {
    if (sizes.isEmpty()) return Size(4000, 3000)
    val wanted = when (ratio) {
      Ratio.R4_3 -> 4.0 / 3.0
      Ratio.R16_9 -> 16.0 / 9.0
      Ratio.R1_1 -> 1.0
    }
    val matching = sizes.filter {
      val r = it.width.toDouble() / it.height
      abs(r - wanted) < 0.06
    }
    return (matching.ifEmpty { sizes.toList() }).maxByOrNull { it.width.toLong() * it.height }!!
  }

  companion object {
    private const val TAG = "CoverCam"
    private const val NIGHT_FRAMES = 5
    private const val HDR_FRAMES = 3
    private const val PANO_TARGET = 3
    private const val SHIFT = 6
  }
}
