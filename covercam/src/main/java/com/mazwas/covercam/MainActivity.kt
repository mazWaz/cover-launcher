package com.mazwas.covercam

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Bg = Color(0xFF000000)
private val Fg = Color(0xFFEDEDED)
private val Dim = Color(0xFF8A8A8A)
private val Accent = Color(0xFFD71921)
private val LevelColor = Color(0xFF58D68D)

class MainActivity : ComponentActivity() {

  private val engine by lazy { CameraEngine(this) }
  private val ui = Handler(Looper.getMainLooper())
  private val shutterSound by lazy { MediaActionSound() }
  private var countdown by mutableStateOf(0)
  private var pendingCapture: Runnable? = null

  /** Volume-down = shutter, volume-up = next mode (hardware control, no pixel hunting). */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        shoot()
        return true
      }
      KeyEvent.KEYCODE_VOLUME_UP -> {
        engine.cycleMode()
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      if (granted[Manifest.permission.CAMERA] == true) engine.probeLenses()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { Screen() }
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      engine.probeLenses()
    } else {
      requestPermissions.launch(
        arrayOf(
          Manifest.permission.CAMERA,
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.ACCESS_FINE_LOCATION,
        ),
      )
    }
  }

  override fun onResume() {
    super.onResume()
    engine.reopenIfNeeded()
  }

  override fun onDestroy() {
    // A pending timer capture must not fire after the engine is closed.
    pendingCapture?.let { ui.removeCallbacks(it) }
    pendingCapture = null
    engine.close()
    super.onDestroy()
  }

  private fun shoot() {
    val s = engine.state
    if (s.timerSeconds > 0) {
      countdown = s.timerSeconds
      val task = Runnable {
        countdown = 0
        pendingCapture = null
        doCapture()
      }
      pendingCapture = task
      ui.postDelayed(task, s.timerSeconds * 1000L)
    } else {
      doCapture()
    }
  }

  private fun doCapture() {
    runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
    if (engine.dngOn) engine.stashPreview()
    engine.capture()
  }

  @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
  @Composable
  private fun Screen() {
    val state = engine.state
    var grid by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Uri?>(null) }
    var peaking by remember { mutableStateOf(false) }
    var histo by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf(0f) }
    var histData by remember { mutableStateOf(IntArray(64)) }
    var peakBmp by remember { mutableStateOf<Bitmap?>(null) }
    var roll by remember { mutableStateOf(0f) }
    val ctx = LocalContext.current

    DisposableEffect(Unit) {
      val sm = getSystemService(SENSOR_SERVICE) as SensorManager
      val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      val orientationSensor = sm.getDefaultSensor(27) // android.sensor.device_orientation
      var pendingRotation = -1
      var pendingSince = 0L
      var committedRotation = -1
      val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
          val x = e.values[0]
          val y = e.values[1]
          val z = e.values[2]
          if (abs(x) > 0.01f || abs(y) > 0.01f) {
            roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            pitch = Math.toDegrees(atan2(-z.toDouble(), sqrt((x * x + y * y).toDouble()))).toFloat()
          }
          // Coarse quadrant from gravity, with a dead zone so a slightly tilted phone
          // does not flip the preview back and forth.
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
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
      }
      if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
      val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
          val quadrant = e.values.firstOrNull()?.toInt() ?: return
          engine.setDeviceRotation(((quadrant % 4) + 4) % 4 * 90)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
      }
      if (orientationSensor != null) {
        sm.registerListener(orientationListener, orientationSensor, SensorManager.SENSOR_DELAY_UI)
      }
      onDispose {
        sm.unregisterListener(listener)
        if (orientationSensor != null) sm.unregisterListener(orientationListener)
      }
    }

    LaunchedEffect(peaking, histo) {
      while (peaking || histo) {
        val frame = engine.previewBitmap(128, 96)
        if (frame != null) {
          val (h64, mask) = analyzeFrame(frame, histo, peaking)
          frame.recycle()
          histData = h64
          peakBmp = mask
        }
        kotlinx.coroutines.delay(450)
      }
      if (!peaking) peakBmp = null
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
      AndroidView(
        factory = { c -> TextureView(c).also { engine.attach(it) } },
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures(
              onTap = { o -> engine.tapFocus(o.x, o.y) },
              onLongPress = { engine.toggleLock() },
            )
          }
          .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
              if (zoom != 1f) engine.setZoom(engine.currentZoom() * zoom)
            }
          },
      )

      // Live preview while recording slow motion: the high-speed session rejects the
      // TextureView (1920x1440), but a SurfaceView with a fixed 720p buffer fits.
      if (state.mode == CameraEngine.Mode.SLOWMO) {
        AndroidView(
          factory = { c ->
            android.view.SurfaceView(c).apply {
              holder.setFixedSize(1280, 720)
              holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) {
                  engine.setSlowPreviewSurface(h.surface)
                }

                override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, ht: Int) = Unit

                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                  engine.setSlowPreviewSurface(null)
                }
              })
            }
          },
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
              rotationZ = engine.previewRotationDegrees().toFloat()
              scaleX = if (engine.previewMirrored()) -1f else 1f
            },
        )
      }

      peakBmp?.let { mask ->
        Image(mask.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.85f))
      }
      if (histo) HistogramOverlay(histData)
      if (grid) GridOverlay()
      LevelOverlay(roll)

      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "COVERCAM",
          color = Dim,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.combinedClickable(
            onClick = { engine.bumpRotation() },
            onLongClick = { engine.toggleMirror() },
          ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "PEAK",
            color = if (peaking) Fg else Dim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { peaking = !peaking }.padding(horizontal = 4.dp),
          )
          Text(
            "HIST",
            color = if (histo) Fg else Dim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { histo = !histo }.padding(horizontal = 4.dp),
          )
          Text(
            "SET",
            color = Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { showSettings = true }.padding(horizontal = 6.dp),
          )
          Text(
            engine.ratio.label,
            color = Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { engine.cycleRatio() }.padding(horizontal = 6.dp),
          )
          Text(
            "GRID",
            color = if (grid) Fg else Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { grid = !grid }.padding(horizontal = 6.dp),
          )
          Text(
            if (state.facingBack) "BACK" else "SELF",
            color = Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { engine.switchCamera() }.padding(horizontal = 6.dp),
          )
          if (state.torch) Text("TORCH", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
      }

      Text(
        state.status + "  P${pitch.roundToInt()}" + (if (abs(pitch) < 1.5f) " LEVEL" else ""),
        color = Dim,
        fontSize = 9.sp,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
      )

      if (countdown > 0) {
        Text(
          countdown.toString(),
          color = Fg,
          fontSize = 54.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.Center),
        )
      }

      if (state.mode == CameraEngine.Mode.PRO) ProPanel(state)

      Thumbnail(state.lastThumb) { showGallery = true }

      if (showSettings) SettingsPanel { showSettings = false }
      if (showGallery) GalleryPanel(
        onClose = { showGallery = false },
        onOpen = { uri -> viewer = uri },
      )
      viewer?.let { uri -> ViewerPanel(uri) { viewer = null } }

      Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(horizontalArrangement = Arrangement.Center) {
          ModeChip("FOTO", state.mode == CameraEngine.Mode.PHOTO) { engine.setMode(CameraEngine.Mode.PHOTO) }
          ModeChip("VIDEO", state.mode == CameraEngine.Mode.VIDEO) { engine.setMode(CameraEngine.Mode.VIDEO) }
          ModeChip("PRO", state.mode == CameraEngine.Mode.PRO) { engine.setMode(CameraEngine.Mode.PRO) }
          ModeChip("NIGHT", state.mode == CameraEngine.Mode.NIGHT) { engine.setMode(CameraEngine.Mode.NIGHT) }
          ModeChip("HDR", state.mode == CameraEngine.Mode.HDR) { engine.setMode(CameraEngine.Mode.HDR) }
          ModeChip("BOKEH", state.mode == CameraEngine.Mode.PORTRAIT) { engine.setMode(CameraEngine.Mode.PORTRAIT) }
          ModeChip("TLAPSE", state.mode == CameraEngine.Mode.TIMELAPSE) { engine.setMode(CameraEngine.Mode.TIMELAPSE) }
          ModeChip("SLOWMO", state.mode == CameraEngine.Mode.SLOWMO) { engine.setMode(CameraEngine.Mode.SLOWMO) }
          ModeChip("PANO", state.mode == CameraEngine.Mode.PANORAMA) { engine.setMode(CameraEngine.Mode.PANORAMA) }
          ModeChip("HDRV", state.mode == CameraEngine.Mode.HDRVID) { engine.setMode(CameraEngine.Mode.HDRVID) }
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
          state.lenses.forEachIndexed { i, id ->
            val selected = i == state.lensIndex
            Text(
              text = if (id == "2" || id == "58") "0.5x" else if (id == "0" || id == "56") "1.0x" else id,
              color = if (selected) Color.Black else Fg,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier
                .padding(horizontal = 3.dp)
                .background(if (selected) Fg else Color(0x33FFFFFF), CircleShape)
                .clickable { engine.switchLens(i - state.lensIndex) }
                .padding(horizontal = 9.dp, vertical = 4.dp),
            )
          }
          Spacer(Modifier.width(8.dp))
          Text("1x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { engine.setZoom(1f) }.padding(3.dp))
          Text("2x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { engine.setZoom(2f) }.padding(3.dp))
          Text("4x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { engine.setZoom(4f) }.padding(3.dp))
        }

        Spacer(Modifier.height(8.dp))

        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircleButton("FLASH", state.torch) { engine.toggleTorch() }
          if (state.mode == CameraEngine.Mode.TIMELAPSE) {
            RecordButton(engine.timelapseActive, false, { engine.toggleTimelapse() }, { engine.toggleTimelapse() })
          } else if (state.mode == CameraEngine.Mode.SLOWMO) {
            RecordButton(state.recording, false, {
              if (state.recording) engine.stopRecording() else engine.startSlowMotion()
            }, {
              if (state.recording) engine.stopRecording()
            })
          } else if (state.mode == CameraEngine.Mode.HDRVID) {
            RecordButton(state.recording, false, {
              if (state.recording) engine.stopHdrVideo() else engine.startHdrVideo()
            }, {
              if (state.recording) engine.stopHdrVideo()
            })
          } else if (state.mode == CameraEngine.Mode.VIDEO) {
            RecordButton(state.recording, state.recordingPaused, {
              if (state.recording) engine.pauseOrResumeRecording() else engine.startRecording()
            }, {
              if (state.recording) engine.stopRecording()
            })
          } else {
            ShutterButton(state.busy) { shoot() }
          }
          CircleButton("TMR", state.timerSeconds > 0) { engine.cycleTimer() }
        }
      }
    }
  }

  @Composable
  private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
      val c = Color(0x33FFFFFF)
      val w1 = size.width / 3f
      val h1 = size.height / 3f
      drawLine(c, Offset(w1, 0f), Offset(w1, size.height), 1f)
      drawLine(c, Offset(w1 * 2, 0f), Offset(w1 * 2, size.height), 1f)
      drawLine(c, Offset(0f, h1), Offset(size.width, h1), 1f)
      drawLine(c, Offset(0f, h1 * 2), Offset(size.width, h1 * 2), 1f)
    }
  }

  @Composable
  private fun LevelOverlay(roll: Float) {
    val level = abs(roll) < 1.5f
    Canvas(Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height / 2f
      val half = size.width * 0.22f
      val rad = Math.toRadians(roll.toDouble())
      val dx = (half * Math.cos(rad)).toFloat()
      val dy = (half * Math.sin(rad)).toFloat()
      drawLine(
        if (level) LevelColor else Color(0x66FFFFFF),
        Offset(cx - dx, cy - dy),
        Offset(cx + dx, cy + dy),
        2f,
      )
    }
  }

  @Composable
  private fun BoxScope.Thumbnail(uri: Uri?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
      bmp = uri?.let {
        runCatching { ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) } }.getOrNull()
      }
    }
    Box(
      Modifier
        .align(Alignment.BottomStart)
        .padding(start = 14.dp, bottom = 20.dp)
        .size(44.dp)
        .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp))
        .clickable { onClick() },
      contentAlignment = Alignment.Center,
    ) {
      val image = bmp
      if (image != null) {
        Image(image.asImageBitmap(), contentDescription = "last shot", modifier = Modifier.fillMaxSize().padding(2.dp))
      } else {
        Text("GAL", color = Fg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      }
    }
  }

  @Composable
  private fun BoxScope.ProPanel(state: CameraEngine.State) {
    Column(
      Modifier
        .align(Alignment.CenterEnd)
        .padding(end = 8.dp)
        .background(Color(0xCC0A0A0A), CircleShape)
        .padding(8.dp)
        .width(200.dp),
    ) {
      ParamRow("ISO", state.iso.toString()) { v -> engine.setIso(lerpInt(state.isoRange.first, state.isoRange.last, v)) }
      ParamRow("SHUTTER", shutterLabel(state.exposureNs)) { v -> engine.setExposure(lerpLog(state.exposureRange.first, state.exposureRange.last, v)) }
      if (state.minFocus > 0f) {
        ParamRow("FOCUS", "%.1f".format(state.focusDiopters)) { v -> engine.setFocus(state.minFocus * v) }
      }
      if (state.evRange.last > state.evRange.first) {
        ParamRow("EV", state.ev.toString()) { v ->
          engine.setEv(state.evRange.first + (v * (state.evRange.last - state.evRange.first)).roundToInt())
        }
      }
      Row {
        WbChip("A", state.wbMode == WB_AUTO) { engine.setWb(WB_AUTO) }
        WbChip("D", state.wbMode == WB_DAY) { engine.setWb(WB_DAY) }
        WbChip("C", state.wbMode == WB_CLOUD) { engine.setWb(WB_CLOUD) }
        WbChip("I", state.wbMode == WB_INC) { engine.setWb(WB_INC) }
        WbChip("F", state.wbMode == WB_FLUO) { engine.setWb(WB_FLUO) }
      }
      ParamRow("WB K", if (state.wbMode == 0) engine.kelvin.toString() else "auto") { v ->
        engine.applyKelvin(2500 + (v * 6000).roundToInt())
      }
    }
  }

  @Composable
  private fun ParamRow(label: String, value: String, onChange: (Float) -> Unit) {
    var pos by remember { mutableStateOf(0.5f) }
    Column(Modifier.padding(vertical = 1.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Dim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Fg, fontSize = 8.sp)
      }
      Slider(
        value = pos,
        onValueChange = { pos = it; onChange(it) },
        modifier = Modifier.height(20.dp),
        colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Fg, inactiveTrackColor = Color(0x44FFFFFF)),
      )
    }
  }

  @Composable
  private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
      label,
      color = if (selected) Fg else Dim,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 3.dp).clickable { onClick() }.padding(vertical = 3.dp),
    )
  }

  @Composable
  private fun WbChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
      label,
      color = if (selected) Color.Black else Fg,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .padding(1.dp)
        .background(if (selected) Fg else Color(0x33FFFFFF), CircleShape)
        .clickable { onClick() }
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }

  @Composable
  private fun CircleButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
      Modifier
        .size(50.dp)
        .background(if (active) Color(0x33D71921) else Color(0x22FFFFFF), CircleShape)
        .clickable { onClick() },
      contentAlignment = Alignment.Center,
    ) {
      Text(label, color = if (active) Accent else Fg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
  }

  @Composable
  private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
      Modifier
        .size(70.dp)
        .background(Color(0x22FFFFFF), CircleShape)
        .padding(5.dp)
        .background(if (busy) Accent else Fg, CircleShape)
        .clickable { onClick() },
    )
  }

  @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
  @Composable
  private fun RecordButton(
    recording: Boolean,
    paused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
  ) {
    Box(
      Modifier
        .size(70.dp)
        .background(Color(0x22FFFFFF), CircleShape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        Modifier
          .size(if (recording) 24.dp else 44.dp)
          .background(if (paused) Fg else Accent, CircleShape),
      )
    }
  }

  private fun lerpInt(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).roundToInt()

  private fun lerpLog(a: Long, b: Long, t: Float): Long {
    val la = kotlin.math.ln(a.toDouble())
    val lb = kotlin.math.ln(b.toDouble())
    return kotlin.math.exp(la + (lb - la) * t).toLong()
  }

  private fun shutterLabel(ns: Long): String = when {
    ns >= 1_000_000_000L -> "%.1fs".format(ns / 1_000_000_000.0)
    ns >= 1_000_000L -> "1/%.0f".format(1_000_000_000.0 / ns)
    else -> "${ns / 1000}us"
  }

  @Composable
  private fun BoxScope.SettingsPanel(onClose: () -> Unit) {
    var quality by remember { mutableStateOf(engine.quality.toFloat()) }
    Column(
      Modifier
        .fillMaxSize()
        .background(Color(0xF2000000))
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("SETTINGS", color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("TUTUP", color = Fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClose() })
      }
      Spacer(Modifier.height(10.dp))
      SettingRow("RASIO", engine.ratio.label) { engine.cycleRatio() }
      SettingRow("SHUTTER SOUND", if (engine.soundOn) "ON" else "OFF") { engine.soundOn = !engine.soundOn }
      SettingRow("GEOTAG", if (engine.geotagOn) "ON" else "OFF") { engine.geotagOn = !engine.geotagOn }
      SettingRow("LEVEL", if (engine.levelOn) "ON" else "OFF") { engine.levelOn = !engine.levelOn }
      SettingRow("RAW DNG", if (engine.dngOn) "ON" else "OFF") { engine.dngOn = !engine.dngOn }
      SettingRow("DNG ROT", "${engine.dngRotation}°") { engine.cycleDngRotation() }
      SettingRow("IMU ROT", if (engine.imuRotationOn) "ON" else "OFF") { engine.imuRotationOn = !engine.imuRotationOn }
      SettingRow("VIDEO", if (engine.video4k) "4K" else "1080p") { engine.cycleVideoRes() }
      SettingRow("FPS", if (engine.video60) "60" else "30") { engine.cycleVideoFps() }
      SettingRow("TLAPSE", "${engine.timelapseIntervalSec}s") { engine.cycleTimelapseInterval() }
      SettingRow("HAL CAPS", engine.capsInfo()) { }
      SettingRow("RESET KALIBRASI", "") { engine.resetCalibration() }
      SettingRow("VERSI", "CoverCam 0.1") { }
      Spacer(Modifier.height(6.dp))
      Text("KUALITAS JPEG ${quality.toInt()}", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      Slider(
        value = quality,
        onValueChange = { quality = it; engine.quality = it.toInt() },
        valueRange = 60f..100f,
        modifier = Modifier.height(26.dp),
        colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Fg, inactiveTrackColor = Color(0x44FFFFFF)),
      )
    }
  }

  @Composable
  private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
      Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 7.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(label, color = Fg, fontSize = 10.sp)
      Text(value, color = Dim, fontSize = 10.sp)
    }
  }

  @Composable
  private fun BoxScope.GalleryPanel(onClose: () -> Unit, onOpen: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(Unit) { items = queryMedia(ctx) }
    Column(Modifier.fillMaxSize().background(Color(0xF2000000)).padding(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("GALERI (${items.size})", color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("TUTUP", color = Fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClose() })
      }
      Spacer(Modifier.height(8.dp))
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(items) { uri -> MediaThumb(uri) { onOpen(uri) } }
      }
    }
  }

  @Composable
  private fun MediaThumb(uri: Uri, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
      bmp = decodeRotated(ctx, uri, 8)
    }
    Box(
      Modifier
        .size(72.dp)
        .background(Color(0x22FFFFFF), RoundedCornerShape(6.dp))
        .clickable { onClick() },
      contentAlignment = Alignment.Center,
    ) {
      bmp?.let { Image(it.asImageBitmap(), contentDescription = "Foto galeri", modifier = Modifier.fillMaxSize()) }
        ?: Text("…", color = Dim, fontSize = 12.sp)
    }
  }

  @Composable
  private fun BoxScope.ViewerPanel(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
      bmp = decodeRotated(ctx, uri, 2)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFA000000))) {
      Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("SHARE", color = Fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              },
            )
          }
        })
        Text("HAPUS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
          runCatching { ctx.contentResolver.delete(uri, null, null) }
          onClose()
        })
        Text("TUTUP", color = Fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClose() })
      }
      bmp?.let {
        Image(it.asImageBitmap(), contentDescription = "Pratinjau foto", modifier = Modifier.fillMaxSize().padding(4.dp))
      }
    }
  }

  /** Decode a MediaStore image and apply its EXIF rotation so thumbnails look right. */
  private fun decodeRotated(ctx: Context, uri: Uri, sample: Int): android.graphics.Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = runCatching {
      ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull() ?: return null
    val orientation = runCatching {
      ctx.contentResolver.openInputStream(uri)?.use {
        android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
      }
    }.getOrNull() ?: 1
    val m = Matrix()
    when (orientation) {
      3 -> m.postRotate(180f)
      6 -> m.postRotate(90f)
      8 -> m.postRotate(270f)
    }
    return if (orientation == 1) bmp else runCatching {
      android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }.getOrDefault(bmp)
  }
  private fun queryMedia(ctx: Context): List<Uri> {
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
      }
    }
    return out
  }
  @Composable
  private fun BoxScope.HistogramOverlay(hist: IntArray) {
    Canvas(
      Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 10.dp, bottom = 96.dp)
        .size(96.dp, 40.dp)
        .background(Color(0x66000000), RoundedCornerShape(4.dp)),
    ) {
      val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
      val bw = size.width / hist.size
      for (i in hist.indices) {
        val hgt = size.height * (hist[i].toFloat() / max)
        drawRect(
          Color(0xCCFFFFFF),
          topLeft = Offset(i * bw, size.height - hgt),
          size = androidx.compose.ui.geometry.Size(bw, hgt),
        )
      }
    }
  }

  /** Luminance histogram + Sobel edge mask for focus peaking. */
  private fun analyzeFrame(bmp: Bitmap, wantHist: Boolean, wantPeak: Boolean): Pair<IntArray, Bitmap?> {
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
  companion object {
    private const val WB_AUTO = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
    private const val WB_DAY = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
    private const val WB_CLOUD = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    private const val WB_INC = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    private const val WB_FLUO = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
  }
}
