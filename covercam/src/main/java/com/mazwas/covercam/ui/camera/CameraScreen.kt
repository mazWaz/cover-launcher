package com.mazwas.covercam.ui.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.hardware.camera2.CaptureRequest
import android.view.TextureView
import com.mazwas.covercam.CameraEngine
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF000000)
private val Fg = Color(0xFFEDEDED)
private val Dim = Color(0xFF8A8A8A)
private val Accent = Color(0xFFD71921)
private val LevelColor = Color(0xFF58D68D)

// White-balance presets used by the Pro panel chips.
private const val WB_AUTO = CaptureRequest.CONTROL_AWB_MODE_AUTO
private const val WB_DAY = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
private const val WB_CLOUD = CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
private const val WB_INC = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
private const val WB_FLUO = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT

/**
 * Stateless camera screen: renders [state], reports [onAction]. No engine reference, no sensors,
 * no MediaStore queries - those live in [CameraViewModel].
 */
@Composable
fun CameraScreen(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  // pointerInput(Unit) must not capture a stale zoom value for pinch-to-zoom.
  val zoomState = rememberUpdatedState(state.zoom)

  Box(modifier.fillMaxSize().background(Bg)) {
    AndroidView(
      factory = { c -> TextureView(c).also { onAction(CameraAction.AttachPreview(it)) } },
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = { o -> onAction(CameraAction.TapFocus(o.x, o.y)) },
            onLongPress = { onAction(CameraAction.ToggleLock) },
          )
        }
        .pointerInput(Unit) {
          detectTransformGestures { _, _, zoom, _ ->
            if (zoom != 1f) onAction(CameraAction.SetZoom(zoomState.value * zoom))
          }
        },
    )

    // Live preview while recording slow motion: the high-speed session rejects the
    // TextureView (1920x1440), but a SurfaceView with a fixed 720p buffer fits.
    // Outside a take the TextureView is the live one, so only mount this while recording.
    if (state.mode == CameraEngine.Mode.SLOWMO && state.recording) {
      AndroidView(
        factory = { c ->
          android.view.SurfaceView(c).apply {
            holder.setFixedSize(1280, 720)
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
              override fun surfaceCreated(h: android.view.SurfaceHolder) {
                onAction(CameraAction.AttachSlowPreview(h.surface))
              }

              override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, ht: Int) = Unit

              override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                onAction(CameraAction.AttachSlowPreview(null))
              }
            })
          }
        },
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            rotationZ = state.previewRotationDegrees.toFloat()
            scaleX = if (state.previewMirrored) -1f else 1f
          },
      )
    }

    state.peakMask?.let { mask ->
      Image(mask.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.85f))
    }
    if (state.histogram) HistogramOverlay(state.histogramData)
    if (state.grid) GridOverlay()
    LevelOverlay(state.roll)

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
          onClick = { onAction(CameraAction.BumpRotation) },
          onLongClick = { onAction(CameraAction.ToggleMirror) },
        ),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "PEAK",
          color = if (state.peaking) Fg else Dim,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .clickable { onAction(CameraAction.SetPeaking(!state.peaking)) }
            .padding(horizontal = 4.dp),
        )
        Text(
          "HIST",
          color = if (state.histogram) Fg else Dim,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .clickable { onAction(CameraAction.SetHistogram(!state.histogram)) }
            .padding(horizontal = 4.dp),
        )
        Text(
          "SET",
          color = Dim,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable { onAction(CameraAction.OpenSettings) }.padding(horizontal = 6.dp),
        )
        Text(
          state.ratioLabel,
          color = Dim,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable { onAction(CameraAction.CycleRatio) }.padding(horizontal = 6.dp),
        )
        Text(
          "GRID",
          color = if (state.grid) Fg else Dim,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier
            .clickable { onAction(CameraAction.SetGrid(!state.grid)) }
            .padding(horizontal = 6.dp),
        )
        Text(
          if (state.facingBack) "BACK" else "SELF",
          color = Dim,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable { onAction(CameraAction.SwitchCamera) }.padding(horizontal = 6.dp),
        )
        if (state.torch) Text("TORCH", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
      }
    }

    Text(
      state.status + "  P" + state.pitch.roundToInt() + (if (state.levelActive) " LEVEL" else ""),
      color = Dim,
      fontSize = 9.sp,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
    )

    if (state.countdown > 0) {
      Text(
        state.countdown.toString(),
        color = Fg,
        fontSize = 54.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.Center),
      )
    }

    if (state.mode == CameraEngine.Mode.PRO) ProPanel(state, onAction)

    Thumbnail(state.lastThumb) { onAction(CameraAction.OpenGallery) }

    if (state.settingsOpen) SettingsPanel(state, onAction) { onAction(CameraAction.CloseSettings) }
    if (state.galleryOpen) {
      GalleryPanel(
        items = state.galleryItems,
        onClose = { onAction(CameraAction.CloseGallery) },
        onOpen = { uri -> onAction(CameraAction.OpenViewer(uri)) },
      )
    }
    state.viewerUri?.let { uri ->
      ViewerPanel(uri, onClose = { onAction(CameraAction.CloseViewer) }, onDelete = {
        onAction(CameraAction.DeletePhoto(uri))
      })
    }

    Column(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Center,
      ) {
        ModeChip("FOTO", state.mode == CameraEngine.Mode.PHOTO) { onAction(CameraAction.SetMode(CameraEngine.Mode.PHOTO)) }
        ModeChip("VIDEO", state.mode == CameraEngine.Mode.VIDEO) { onAction(CameraAction.SetMode(CameraEngine.Mode.VIDEO)) }
        ModeChip("PRO", state.mode == CameraEngine.Mode.PRO) { onAction(CameraAction.SetMode(CameraEngine.Mode.PRO)) }
        ModeChip("NIGHT", state.mode == CameraEngine.Mode.NIGHT) { onAction(CameraAction.SetMode(CameraEngine.Mode.NIGHT)) }
        ModeChip("HDR", state.mode == CameraEngine.Mode.HDR) { onAction(CameraAction.SetMode(CameraEngine.Mode.HDR)) }
        ModeChip("BOKEH", state.mode == CameraEngine.Mode.PORTRAIT) { onAction(CameraAction.SetMode(CameraEngine.Mode.PORTRAIT)) }
        ModeChip("TLAPSE", state.mode == CameraEngine.Mode.TIMELAPSE) { onAction(CameraAction.SetMode(CameraEngine.Mode.TIMELAPSE)) }
        ModeChip("SLOWMO", state.mode == CameraEngine.Mode.SLOWMO) { onAction(CameraAction.SetMode(CameraEngine.Mode.SLOWMO)) }
        ModeChip("PANO", state.mode == CameraEngine.Mode.PANORAMA) { onAction(CameraAction.SetMode(CameraEngine.Mode.PANORAMA)) }
        ModeChip("HDRV", state.mode == CameraEngine.Mode.HDRVID) { onAction(CameraAction.SetMode(CameraEngine.Mode.HDRVID)) }
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
              .clickable { onAction(CameraAction.SelectLens(i - state.lensIndex)) }
              .padding(horizontal = 9.dp, vertical = 4.dp),
          )
        }
        Spacer(Modifier.width(8.dp))
        Text("1x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { onAction(CameraAction.SetZoom(1f)) }.padding(3.dp))
        Text("2x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { onAction(CameraAction.SetZoom(2f)) }.padding(3.dp))
        Text("4x", color = Fg, fontSize = 9.sp, modifier = Modifier.clickable { onAction(CameraAction.SetZoom(4f)) }.padding(3.dp))
      }

      Spacer(Modifier.height(8.dp))

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CircleButton("FLASH", state.torch) { onAction(CameraAction.ToggleTorch) }
        when (state.mode) {
          CameraEngine.Mode.TIMELAPSE -> RecordButton(
            recording = state.timelapseActive,
            paused = false,
            onClick = { onAction(CameraAction.ToggleTimelapse) },
            onLongClick = { onAction(CameraAction.ToggleTimelapse) },
          )
          CameraEngine.Mode.SLOWMO -> RecordButton(
            recording = state.recording,
            paused = false,
            onClick = { onAction(CameraAction.ToggleSlowMotion) },
            onLongClick = { if (state.recording) onAction(CameraAction.StopRecording) },
          )
          CameraEngine.Mode.HDRVID -> RecordButton(
            recording = state.recording,
            paused = false,
            onClick = { onAction(CameraAction.ToggleHdrVideo) },
            onLongClick = { if (state.recording) onAction(CameraAction.StopRecording) },
          )
          CameraEngine.Mode.VIDEO -> RecordButton(
            recording = state.recording,
            paused = state.recordingPaused,
            onClick = { onAction(CameraAction.ToggleRecording) },
            onLongClick = { if (state.recording) onAction(CameraAction.StopRecording) },
          )
          else -> ShutterButton(state.busy) { onAction(CameraAction.Capture) }
        }
        CircleButton("TMR", state.timerSeconds > 0) { onAction(CameraAction.CycleTimer) }
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
  var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
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
private fun BoxScope.ProPanel(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  Column(
    Modifier
      .align(Alignment.CenterEnd)
      .padding(end = 8.dp)
      .background(Color(0xCC0A0A0A), CircleShape)
      .padding(8.dp)
      .width(200.dp),
  ) {
    ParamRow("ISO", state.iso.toString()) { v ->
      onAction(CameraAction.SetIso(lerpInt(state.isoRange.first, state.isoRange.last, v)))
    }
    ParamRow("SHUTTER", shutterLabel(state.exposureNs)) { v ->
      onAction(CameraAction.SetShutter(lerpLog(state.exposureRange.first, state.exposureRange.last, v)))
    }
    if (state.minFocus > 0f) {
      ParamRow("FOCUS", "%.1f".format(state.focusDiopters)) { v ->
        onAction(CameraAction.SetFocus(state.minFocus * v))
      }
    }
    if (state.evRange.last > state.evRange.first) {
      ParamRow("EV", state.ev.toString()) { v ->
        onAction(
          CameraAction.SetEv(
            state.evRange.first + (v * (state.evRange.last - state.evRange.first)).roundToInt(),
          ),
        )
      }
    }
    Row {
      WbChip("A", state.wbMode == WB_AUTO) { onAction(CameraAction.SetWb(WB_AUTO)) }
      WbChip("D", state.wbMode == WB_DAY) { onAction(CameraAction.SetWb(WB_DAY)) }
      WbChip("C", state.wbMode == WB_CLOUD) { onAction(CameraAction.SetWb(WB_CLOUD)) }
      WbChip("I", state.wbMode == WB_INC) { onAction(CameraAction.SetWb(WB_INC)) }
      WbChip("F", state.wbMode == WB_FLUO) { onAction(CameraAction.SetWb(WB_FLUO)) }
    }
    ParamRow("WB K", if (state.wbMode == 0) state.kelvin.toString() else "auto") { v ->
      onAction(CameraAction.SetKelvin(2500 + (v * 6000).roundToInt()))
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
private fun BoxScope.SettingsPanel(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  onClose: () -> Unit,
) {
  var quality by remember { mutableStateOf(state.quality.toFloat()) }
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
    SettingRow("RASIO", state.ratioLabel) { onAction(CameraAction.CycleRatio) }
    SettingRow("SHUTTER SOUND", if (state.soundOn) "ON" else "OFF") { onAction(CameraAction.ToggleShutterSound) }
    SettingRow("GEOTAG", if (state.geotagOn) "ON" else "OFF") { onAction(CameraAction.ToggleGeotag) }
    SettingRow("LEVEL", if (state.levelOn) "ON" else "OFF") { onAction(CameraAction.ToggleLevel) }
    SettingRow("RAW DNG", if (state.dngOn) "ON" else "OFF") { onAction(CameraAction.ToggleDng) }
    SettingRow("DNG ROT", "${state.dngRotation}°") { onAction(CameraAction.CycleDngRotation) }
    SettingRow("IMU ROT", if (state.imuRotationOn) "ON" else "OFF") { onAction(CameraAction.ToggleImuRotation) }
    SettingRow("VIDEO", if (state.video4k) "4K" else "1080p") { onAction(CameraAction.CycleVideoRes) }
    SettingRow("FPS", if (state.video60) "60" else "30") { onAction(CameraAction.CycleVideoFps) }
    SettingRow("TLAPSE", "${state.timelapseIntervalSec}s") { onAction(CameraAction.CycleTimelapseInterval) }
    SettingRow("HAL CAPS", state.capsInfo) { }
    SettingRow("RESET KALIBRASI", "") { onAction(CameraAction.ResetCalibration) }
    SettingRow("VERSI", "CoverCam 0.1") { }
    Spacer(Modifier.height(6.dp))
    Text("KUALITAS JPEG ${quality.toInt()}", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Slider(
      value = quality,
      onValueChange = { quality = it; onAction(CameraAction.SetQuality(it.toInt())) },
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
private fun GalleryPanel(items: List<Uri>, onClose: () -> Unit, onOpen: (Uri) -> Unit) {
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
  var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
  var loaded by remember(uri) { mutableStateOf(false) }
  LaunchedEffect(uri) {
    bmp = decodeRotated(ctx, uri, 8)
    loaded = true
  }
  Box(
    Modifier
      .size(72.dp)
      .background(Color(0x22FFFFFF), RoundedCornerShape(6.dp))
      .clickable { onClick() },
    contentAlignment = Alignment.Center,
  ) {
    val image = bmp
    when {
      image != null -> Image(image.asImageBitmap(), contentDescription = "Foto galeri", modifier = Modifier.fillMaxSize())
      // A DNG decodes to null by design - label it instead of showing an empty tile.
      loaded -> Text("RAW", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      else -> Text("…", color = Dim, fontSize = 12.sp)
    }
  }
}

@Composable
private fun BoxScope.ViewerPanel(uri: Uri, onClose: () -> Unit, onDelete: () -> Unit) {
  val ctx = LocalContext.current
  var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
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
        onDelete()
      })
      Text("TUTUP", color = Fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClose() })
    }
    bmp?.let {
      Image(it.asImageBitmap(), contentDescription = "Pratinjau foto", modifier = Modifier.fillMaxSize().padding(4.dp))
    }
  }
}

/** Decode a MediaStore image and apply its EXIF rotation so thumbnails look right. */
private fun decodeRotated(ctx: Context, uri: Uri, sample: Int): Bitmap? {
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
    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }.getOrDefault(bmp)
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

@Preview(showBackground = true, widthDp = 352, heightDp = 339)
@Composable
private fun CameraScreenPreview() {
  CameraScreen(
    state = CameraUiState(
      ready = true,
      mode = CameraEngine.Mode.PRO,
      lensLabel = "1.0x",
      lenses = listOf("0", "2"),
      lensIndex = 0,
      status = "id=0 rot=0 mirror=true",
      iso = 400,
      exposureNs = 8_000_000L,
      ev = 0,
      ratioLabel = "4:3",
      pitch = 0f,
      grid = true,
      histogram = true,
    ),
    onAction = {},
  )
}
