package com.mazwas.covercam.ui.camera

import android.hardware.camera2.CaptureRequest
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mazwas.covercam.CameraEngine
import com.mazwas.covercam.PreviewGeometry
import com.mazwas.covercam.R
import com.mazwas.covercam.ui.theme.CoverCamTheme
import com.mazwas.covercam.ui.theme.Scrim
import com.mazwas.covercam.ui.theme.Success
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** A mode as the screen names it: [short] on the rail, [name] and [hint] in the mode list. */
internal class ModeInfo(val mode: CameraEngine.Mode, val short: String, val name: String, val hint: String)

/** In the engine's own order, so "next mode" (a swipe, volume-up) moves one step along this list. */
internal val MODES = listOf(
  ModeInfo(CameraEngine.Mode.PHOTO, "FOTO", "Foto", "Satu jepretan"),
  ModeInfo(CameraEngine.Mode.VIDEO, "VIDEO", "Video", "1080p atau 4K"),
  ModeInfo(CameraEngine.Mode.PRO, "PRO", "Pro", "ISO, shutter, fokus manual"),
  ModeInfo(CameraEngine.Mode.NIGHT, "NIGHT", "Malam", "5 frame digabung"),
  ModeInfo(CameraEngine.Mode.PORTRAIT, "BOKEH", "Bokeh", "Latar diburamkan"),
  ModeInfo(CameraEngine.Mode.HDR, "HDR", "HDR", "3 eksposur digabung"),
  ModeInfo(CameraEngine.Mode.TIMELAPSE, "TLAPSE", "Timelapse", "Foto berkala"),
  ModeInfo(CameraEngine.Mode.SLOWMO, "SLOWMO", "Slow-mo", "Gerak lambat"),
  ModeInfo(CameraEngine.Mode.PANORAMA, "PANO", "Panorama", "3 foto disambung"),
  ModeInfo(CameraEngine.Mode.HDRVID, "HDRV", "Video HDR", "HLG 10-bit, tanpa suara"),
)

/** Modes whose big button starts and stops something instead of taking one picture. */
private val RECORDING_MODES = setOf(
  CameraEngine.Mode.VIDEO,
  CameraEngine.Mode.TIMELAPSE,
  CameraEngine.Mode.SLOWMO,
  CameraEngine.Mode.HDRVID,
)

private val WB_PRESETS = listOf(
  CaptureRequest.CONTROL_AWB_MODE_AUTO to "A",
  CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "D",
  CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "C",
  CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "I",
  CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "F",
)

private val ZOOM_STEPS = listOf(2, 4)

/** What the small-controls rail is showing: its three buttons, the zoom ladder, or the options. */
private enum class Drawer { None, Zoom, Options }

private val RailMin = 64.dp
private val RailGap = 8.dp

/**
 * Stateless camera screen for the Flip5 cover (748x720 px): renders [state], reports [onAction]. No engine
 * reference, no sensors, no MediaStore queries - those live in [CameraViewModel].
 *
 * The picture stands alone and the controls live in the black beside it. A 3:4 picture on the nearly square
 * cover leaves a bar on each side (or one below, when the cover is turned); on OLED those are switched-off
 * pixels, so five controls sit there and nothing lies on the photo. Everything used less often opens in place
 * from those five. One rail of small round buttons, drawers that open where they are, and modes as pages you
 * swipe: that part is how Samsung's own cover camera works.
 */
@Composable
fun CameraScreen(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (state.cameraGranted) Stage(state, onAction) else PermissionPanel(onAction)
    if (state.settingsOpen) SettingsPanel(state, onAction)
    if (state.modesOpen) ModePanel(state, onAction)
    if (state.galleryOpen) GalleryPanel(state.galleryItems, onAction)
    state.viewer?.let { ViewerPanel(it, onAction) }
  }
}

/** The picture and its two groups of controls, placed in whatever black the picture leaves over. */
@Composable
private fun Stage(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  var drawer by rememberSaveable { mutableStateOf(Drawer.None) }
  // An open drawer closes by itself a little after the last change made in it; a zoom step is picked quickly.
  LaunchedEffect(drawer, state.zoom, state.lensIndex, state.torch, state.timerSeconds, state.ratioLabel, state.grid) {
    if (drawer != Drawer.None) {
      delay(if (drawer == Drawer.Zoom) 3000 else 6000)
      drawer = Drawer.None
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
    // Wider than the picture: the spare room is at the sides. Otherwise it is underneath.
    if (maxWidth / maxHeight > state.pictureAspect) {
      val rail = ((maxWidth - maxHeight * state.pictureAspect) / 2).coerceAtLeast(RailMin)
      Picture(state, onAction, Modifier.align(Alignment.Center))
      Column(
        Modifier.align(Alignment.CenterStart).width(rail).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(RailGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (drawer == Drawer.None) {
          RailButtons(state, onAction, drawer) { drawer = it }
        } else {
          DrawerItems(state, onAction, drawer, climbing = true) { drawer = it }
        }
      }
      MainControls(
        state, onAction, vertical = true,
        modifier = Modifier.align(Alignment.CenterEnd).width(rail).fillMaxHeight(),
      )
    } else {
      val rail = (maxHeight - maxWidth / state.pictureAspect).coerceAtLeast(RailMin + 24.dp)
      Picture(state, onAction, Modifier.align(Alignment.TopCenter))
      Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rail).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(RailGap, Alignment.CenterVertically),
      ) {
        if (drawer != Drawer.None) {
          Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) { DrawerItems(state, onAction, drawer, climbing = false) { drawer = it } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(RailGap),
            verticalAlignment = Alignment.CenterVertically,
          ) { RailButtons(state, onAction, drawer) { drawer = it } }
          MainControls(state, onAction, vertical = false)
        }
      }
    }
  }
}

// ---------------------------------------------------------------- picture

/** The live picture in a box of its own shape, with only what belongs on a picture drawn over it. */
@Composable
private fun Picture(state: CameraUiState, onAction: (CameraAction) -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.aspectRatio(state.pictureAspect)) {
    Viewfinder(state, onAction)

    if (state.slowPreview) {
      // The take's own 16:9 picture, standing the same way as the photo one, over a black sheet that hides the
      // TextureView's last frame. The TextureView itself must stay: its surface is the engine's preview.
      val wide = state.slowPreviewWidth.toFloat() / state.slowPreviewHeight
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
      Box(Modifier.align(Alignment.Center).aspectRatio(if (state.pictureAspect >= 1f) wide else 1f / wide)) {
        SlowPreview(state, onAction)
      }
    }

    Column(
      Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Badges(state)
      StatusToast(state.status, state.lastThumb)
    }
    if (state.histogram) Histogram(state.histogramData, Modifier.align(Alignment.TopEnd).padding(8.dp))
    ModeFlash(state.mode, Modifier.align(Alignment.Center))

    if (state.countdown > 0) {
      Text(
        state.countdown.toString(),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Center).testTag("countdown"),
      )
    }

    when {
      state.calibrating -> CalibrationBar(onAction, Modifier.align(Alignment.BottomCenter))
      state.mode == CameraEngine.Mode.PRO -> ProStrip(state, onAction, Modifier.align(Alignment.BottomCenter))
    }
  }
}

@Composable
private fun Viewfinder(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  // pointerInput(Unit) must not capture a stale zoom value for pinch-to-zoom.
  val zoom = rememberUpdatedState(state.zoom)
  var focusRing by remember { mutableStateOf<Offset?>(null) }
  LaunchedEffect(focusRing) {
    if (focusRing != null) {
      delay(900)
      focusRing = null
    }
  }

  AndroidView(
    factory = { c -> TextureView(c).also { onAction(CameraAction.AttachPreview(it)) } },
    modifier = Modifier
      .fillMaxSize()
      .testTag("preview")
      .pointerInput(Unit) {
        detectTapGestures(
          onTap = { o ->
            focusRing = o
            onAction(CameraAction.TapFocus(o.x, o.y))
          },
          onLongPress = { onAction(CameraAction.ToggleLock) },
        )
      }
      .pointerInput(Unit) {
        detectTransformGestures { _, _, change, _ ->
          if (change != 1f) onAction(CameraAction.SetZoom(zoom.value * change))
        }
      }
      .pointerInput(Unit) {
        // One finger dragged sideways = the next or previous mode, like pages. Two fingers are a pinch, never a swipe.
        val far = 56.dp.toPx()
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          var dx = 0f
          var dy = 0f
          var pinch = false
          do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) pinch = true
            event.changes.firstOrNull { it.id == down.id }?.let {
              dx += it.positionChange().x
              dy += it.positionChange().y
            }
          } while (event.changes.any { it.pressed })
          if (!pinch && abs(dx) > far && abs(dx) > 2 * abs(dy)) {
            onAction(if (dx < 0) CameraAction.NextMode else CameraAction.PrevMode)
          }
        }
      },
  )

  state.peakMask?.let { mask ->
    Image(
      mask.asImageBitmap(),
      contentDescription = null,
      contentScale = ContentScale.FillBounds,
      modifier = Modifier.fillMaxSize().graphicsLayer {
        // The mask is a copy of the raw TextureView content, so it gets the TextureView's transform: scale, turn,
        // mirror. A layer scales before it turns, and "mirror after a turn" is "turn the other way after a mirror".
        scaleX = if (state.previewMirrored) -state.previewScaleX else state.previewScaleX
        scaleY = state.previewScaleY
        rotationZ = (if (state.previewMirrored) -1 else 1) * state.previewRotationDegrees.toFloat()
        alpha = 0.85f
      },
    )
  }
  if (state.grid || state.levelOn) PictureGuides(state)
  focusRing?.let { at ->
    val ring = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) { drawCircle(ring, 26.dp.toPx(), at, style = Stroke(1.5.dp.toPx())) }
  }
}

/**
 * Live preview of a slow-motion take: the high-speed session rejects the TextureView (1920x1440), but a
 * SurfaceView with the recorder's own size fits. It is mounted the moment a take is asked for, because the
 * session needs its surface when it is created; outside a take the TextureView is the live one.
 * The system stands a SurfaceView's camera picture upright by itself, and it cannot be mirrored: during a
 * slow-motion take the preview shows the scene as the clip will, not as a mirror.
 */
@Composable
private fun SlowPreview(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  AndroidView(
    factory = { c ->
      SurfaceView(c).apply {
        // The high-speed session only accepts surfaces from the published size list, and some
        // HALs (the front camera here) silently drop video frames when the preview size differs
        // from the recorder size. Mirror the recorder exactly.
        holder.setFixedSize(state.slowPreviewWidth, state.slowPreviewHeight)
        holder.addCallback(object : SurfaceHolder.Callback {
          override fun surfaceCreated(h: SurfaceHolder) = onAction(CameraAction.AttachSlowPreview(h.surface))
          override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) = Unit
          override fun surfaceDestroyed(h: SurfaceHolder) = onAction(CameraAction.AttachSlowPreview(null))
        })
      }
    },
    modifier = Modifier.fillMaxSize(),
  )
}

/** Rule-of-thirds grid and horizon line. The box they are drawn in is exactly the picture. */
@Composable
private fun PictureGuides(state: CameraUiState) {
  val light = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
  val dark = MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
  val roll = state.roll
  // Gravity is measured against the phone's body; the launcher turns the cover display in quarter turns. Take the
  // turn out, or the line stands upright whenever the cover is held sideways.
  val turned = (LocalView.current.display?.rotation ?: 0) * 90f
  Canvas(Modifier.fillMaxSize()) {
    // every guide is a light line on a wider dark one, so it shows on bright and on dark parts of the picture
    fun guide(from: Offset, to: Offset, color: Color = light) {
      drawLine(dark, from, to, 3.dp.toPx())
      drawLine(color, from, to, 1.dp.toPx())
    }
    if (state.grid) {
      for (i in 1..2) {
        guide(Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height))
        guide(Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f))
      }
    }
    if (state.levelOn && roll != null) {
      val half = size.width * 0.22f
      val rad = Math.toRadians((roll - turned).toDouble())
      val dx = (half * Math.cos(rad)).toFloat()
      val dy = (half * Math.sin(rad)).toFloat()
      guide(
        Offset(center.x - dx, center.y - dy),
        Offset(center.x + dx, center.y + dy),
        if (PreviewGeometry.levelError(roll) < 1.5f) Success else light,
      )
    }
  }
}

/** What the camera is doing right now, small, at the top of the picture. Nothing when there is nothing to say. */
@Composable
private fun Badges(state: CameraUiState) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    when {
      state.recording -> Badge {
        val dot = if (state.recordingPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Label(
          "%02d:%02d".format(state.recordingSeconds / 60, state.recordingSeconds % 60),
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.recordingPaused) Label("JEDA")
      }
      state.timelapseActive -> Badge {
        Label("TIMELAPSE ${state.timelapseIntervalSec}S", color = MaterialTheme.colorScheme.error, small = true)
      }
      else -> {
        if (state.locked) Badge { Label("AE/AF TERKUNCI", color = MaterialTheme.colorScheme.error, small = true) }
        if (state.rawActive) Badge { Label("RAW", color = MaterialTheme.colorScheme.onSurface, small = true) }
        if (state.timerSeconds > 0 && state.countdown == 0) {
          Badge { Label("TIMER ${state.timerSeconds}S", color = MaterialTheme.colorScheme.onSurface, small = true) }
        }
      }
    }
  }
}

@Composable
private fun Badge(content: @Composable RowScope.() -> Unit) {
  Row(
    Modifier.background(Scrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** The engine's last message, shown for a moment and then faded away. [trigger] re-shows a repeated message. */
@Composable
private fun StatusToast(status: String, trigger: Any?) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(status, trigger) {
    visible = status.isNotBlank()
    delay(2500)
    visible = false
  }
  AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
    Label(
      status,
      Modifier.background(Scrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
      color = MaterialTheme.colorScheme.onSurface,
      small = true,
    )
  }
}

/** A swipe or volume-up changes the mode without a look at the rail: name the new one in the middle for a moment. */
@Composable
private fun ModeFlash(mode: CameraEngine.Mode, modifier: Modifier = Modifier) {
  var shown by remember { mutableStateOf<CameraEngine.Mode?>(null) }
  var seen by remember { mutableStateOf(mode) }
  LaunchedEffect(mode) {
    if (mode == seen) return@LaunchedEffect // the mode the screen opened with is not a change
    seen = mode
    shown = mode
    delay(900)
    shown = null
  }
  AnimatedVisibility(shown != null, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(250))) {
    Text(
      MODES.firstOrNull { it.mode == seen }?.name.orEmpty(),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.background(Scrim, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
    )
  }
}

@Composable
private fun Histogram(hist: IntArray, modifier: Modifier = Modifier) {
  val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
  Canvas(modifier.size(96.dp, 40.dp).background(Scrim, RoundedCornerShape(4.dp))) {
    val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
    val bw = size.width / hist.size
    for (i in hist.indices) {
      val hgt = size.height * (hist[i].toFloat() / max)
      drawRect(bar, topLeft = Offset(i * bw, size.height - hgt), size = Size(bw, hgt))
    }
  }
}

/** Shown while calibrating from Settings: the preview stays in view, so each tap can be judged. */
@Composable
private fun CalibrationBar(onAction: (CameraAction) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().background(Scrim).swallowTaps().padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PillBtn("PUTAR", modifier = Modifier.testTag("rotate")) { onAction(CameraAction.BumpRotation) }
    PillBtn("CERMIN", modifier = Modifier.testTag("mirror")) { onAction(CameraAction.ToggleMirror) }
    PillBtn("SELESAI", primary = true, modifier = Modifier.testTag("calibrate_done")) {
      onAction(CameraAction.StopCalibration)
    }
  }
}

// ---------------------------------------------------------------- the two groups of controls

/**
 * Zoom, options and gallery: the three small buttons that are always there. The first two open a drawer, and
 * show it by staying lit; a second tap closes it.
 */
@Composable
private fun RailButtons(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  drawer: Drawer,
  onDrawer: (Drawer) -> Unit,
) {
  RoundBtn(
    "Zoom ${zoomLabel(state)}",
    text = zoomLabel(state),
    on = drawer == Drawer.Zoom,
    modifier = Modifier.testTag("zoom"),
  ) { onDrawer(if (drawer == Drawer.Zoom) Drawer.None else Drawer.Zoom) }
  RoundBtn(
    "Opsi",
    icon = R.drawable.ic_layout_grid,
    on = drawer == Drawer.Options,
    modifier = Modifier.testTag("options"),
  ) { onDrawer(if (drawer == Drawer.Options) Drawer.None else Drawer.Options) }
  LastShot(state.lastThumb) { onAction(CameraAction.OpenGallery) }
}

/** What an open drawer holds. [climbing]: listed bottom-up, for the zoom ladder of a side rail. */
@Composable
private fun DrawerItems(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  drawer: Drawer,
  climbing: Boolean,
  onDrawer: (Drawer) -> Unit,
) {
  val running = state.recording || state.timelapseActive || state.slowPreview
  when (drawer) {
    Drawer.None -> Unit

    Drawer.Zoom -> {
      val atOne = abs(state.zoom - 1f) < 0.05f
      // the lenses, then the zoom steps: 0.5x, 1.0x, 2x, 4x
      val lenses = state.lenses.withIndex().sortedBy { it.value }.map { (i, label) ->
        Triple(label, i == state.lensIndex && atOne) { onAction(CameraAction.SelectLens(i - state.lensIndex)) }
      }
      val steps = ZOOM_STEPS.map { z ->
        Triple("${z}x", abs(state.zoom - z) < 0.05f) { onAction(CameraAction.SetZoom(z.toFloat())) }
      }
      (lenses + steps).let { if (climbing) it.reversed() else it }.forEach { (label, selected, pick) ->
        RoundBtn("Zoom $label", text = label, on = selected, modifier = Modifier.testTag("zoom_$label"), onClick = pick)
      }
    }

    Drawer.Options -> {
      RoundBtn(
        if (state.torch) "Matikan senter" else "Nyalakan senter",
        icon = R.drawable.ic_flashlight,
        on = state.torch,
        enabled = state.facingBack,
        modifier = Modifier.testTag("torch"),
      ) { onAction(CameraAction.ToggleTorch) }
      when {
        state.mode == CameraEngine.Mode.TIMELAPSE -> RoundBtn(
          "Jeda antar foto ${state.timelapseIntervalSec} detik",
          text = "${state.timelapseIntervalSec}s",
        ) { onAction(CameraAction.CycleTimelapseInterval) }
        state.timerSeconds > 0 -> RoundBtn(
          "Timer ${state.timerSeconds} detik",
          text = "${state.timerSeconds}s",
          on = true,
          modifier = Modifier.testTag("timer"),
        ) { onAction(CameraAction.CycleTimer) }
        else -> RoundBtn("Timer mati", icon = R.drawable.ic_timer, modifier = Modifier.testTag("timer")) {
          onAction(CameraAction.CycleTimer)
        }
      }
      RoundBtn("Rasio foto ${state.ratioLabel}", text = state.ratioLabel, modifier = Modifier.testTag("ratio")) {
        onAction(CameraAction.CycleRatio)
      }
      RoundBtn("Grid", icon = R.drawable.ic_grid, on = state.grid, modifier = Modifier.testTag("grid")) {
        onAction(CameraAction.SetGrid(!state.grid))
      }
      RoundBtn(
        if (state.facingBack) "Kamera depan" else "Kamera belakang",
        icon = R.drawable.ic_switch_camera,
        enabled = !running,
        modifier = Modifier.testTag("flip"),
      ) { onAction(CameraAction.SwitchCamera) }
      RoundBtn("Pengaturan", icon = R.drawable.ic_settings, modifier = Modifier.testTag("settings")) {
        onDrawer(Drawer.None)
        onAction(CameraAction.OpenSettings)
      }
      RoundBtn("Tutup opsi", icon = R.drawable.ic_x, modifier = Modifier.testTag("options_close")) {
        onDrawer(Drawer.None)
      }
    }
  }
}

private fun zoomLabel(state: CameraUiState): String = when {
  abs(state.zoom - 1f) < 0.05f -> state.lenses.getOrNull(state.lensIndex) ?: "1x"
  abs(state.zoom - state.zoom.roundToInt()) < 0.05f -> "${state.zoom.roundToInt()}x"
  else -> "%.1fx".format(state.zoom)
}

/** The shutter and the mode it shoots in. While a video runs, the mode's place holds the pause button. */
@Composable
private fun MainControls(
  state: CameraUiState,
  onAction: (CameraAction) -> Unit,
  vertical: Boolean,
  modifier: Modifier = Modifier,
) {
  val recordingMode = state.mode in RECORDING_MODES
  val running = state.recording || state.timelapseActive || state.slowPreview
  val modeOrPause: @Composable () -> Unit = {
    if (state.mode == CameraEngine.Mode.VIDEO && state.recording) {
      RoundBtn(
        if (state.recordingPaused) "Lanjutkan rekaman" else "Jeda rekaman",
        icon = if (state.recordingPaused) R.drawable.ic_play else R.drawable.ic_pause,
        on = state.recordingPaused,
        modifier = Modifier.testTag("pause"),
      ) { onAction(CameraAction.TogglePause) }
    } else {
      ModeBadge(state.mode, enabled = !running) { onAction(CameraAction.OpenModes) }
    }
  }
  if (vertical) {
    Column(
      modifier,
      verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      modeOrPause()
      Shutter(state, recordingMode, running) { onAction(CameraAction.Capture) }
      // as tall as the mode above, so the shutter stays in the middle of the rail
      Spacer(Modifier.height(44.dp))
    }
  } else {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
      modeOrPause()
      Shutter(state, recordingMode, running) { onAction(CameraAction.Capture) }
    }
  }
}

/** The current mode, and where it sits among the ten: one lit square in two rows of five. Tap for the list. */
@Composable
private fun ModeBadge(mode: CameraEngine.Mode, enabled: Boolean, onClick: () -> Unit) {
  val index = MODES.indexOfFirst { it.mode == mode }
  val lit = MaterialTheme.colorScheme.primary
  val dim = MaterialTheme.colorScheme.outline
  Column(
    Modifier
      .widthIn(min = 56.dp)
      .heightIn(min = 44.dp)
      .alpha(if (enabled) 1f else 0.35f)
      .clip(RoundedCornerShape(10.dp))
      .clickable(enabled = enabled, onClickLabel = "Pilih mode", role = Role.Button, onClick = onClick)
      .padding(horizontal = 4.dp, vertical = 6.dp)
      .testTag("mode"),
    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Label(MODES.getOrNull(index)?.short.orEmpty(), color = lit)
    Canvas(Modifier.size(28.dp, 10.dp)) {
      val cell = 4.dp.toPx()
      val gap = 2.dp.toPx()
      for (i in MODES.indices) {
        drawRect(if (i == index) lit else dim, Offset((i % 5) * (cell + gap), (i / 5) * (cell + gap)), Size(cell, cell))
      }
    }
  }
}

@Composable
private fun Shutter(state: CameraUiState, recordingMode: Boolean, running: Boolean, onClick: () -> Unit) {
  val label = when {
    !recordingMode -> "Ambil foto"
    running -> "Berhenti merekam"
    else -> "Mulai merekam"
  }
  Box(
    Modifier
      .size(64.dp)
      .alpha(if (state.ready || running) 1f else 0.4f)
      .clip(CircleShape)
      .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
      .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label }
      .testTag("shutter"),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      when {
        // red is the signal: working on a shot, or something to record
        !recordingMode -> Modifier.size(52.dp).background(
          if (state.busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          CircleShape,
        )
        running -> Modifier.size(22.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
        else -> Modifier.size(48.dp).background(MaterialTheme.colorScheme.error, CircleShape)
      },
    )
  }
}

@Composable
private fun LastShot(uri: android.net.Uri?, onClick: () -> Unit) {
  val thumb = rememberThumbnail(uri, 160)
  Box(
    Modifier
      .size(44.dp)
      .clip(CircleShape)
      .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
      .clickable(onClickLabel = "Buka galeri", role = Role.Button, onClick = onClick)
      .testTag("gallery"),
    contentAlignment = Alignment.Center,
  ) {
    val image = thumb.bitmap
    if (image != null) {
      Image(image, contentDescription = "Galeri, foto terakhir", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(
        painterResource(R.drawable.ic_image),
        contentDescription = "Galeri",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

// ---------------------------------------------------------------- pro

private enum class ProParam { ISO, SHUTTER, FOCUS, EV, WB }

/**
 * Pro's values in one line along the bottom of the picture. Tap a value and its slider opens above the line;
 * tap it again and the slider goes. Everything is automatic until its slider is moved.
 */
@Composable
private fun ProStrip(state: CameraUiState, onAction: (CameraAction) -> Unit, modifier: Modifier = Modifier) {
  var open by rememberSaveable { mutableStateOf<ProParam?>(null) }
  val hasFocus = state.minFocus > 0f
  val hasEv = state.evRange.last > state.evRange.first
  fun toggle(p: ProParam) {
    open = if (open == p) null else p
  }
  Column(modifier.fillMaxWidth().background(Scrim).swallowTaps()) {
    open?.let { param ->
      Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        when (param) {
          ProParam.ISO -> ProSlider(
            logPosition(state.iso.toLong(), state.isoRange.first.toLong(), state.isoRange.last.toLong()),
            "ISO",
          ) { t ->
            onAction(CameraAction.SetIso(logLerp(state.isoRange.first.toLong(), state.isoRange.last.toLong(), t).toInt()))
          }
          ProParam.SHUTTER -> ProSlider(
            logPosition(state.exposureNs, state.exposureRange.first, state.exposureRange.last),
            "Kecepatan shutter",
          ) { t -> onAction(CameraAction.SetShutter(logLerp(state.exposureRange.first, state.exposureRange.last, t))) }
          ProParam.FOCUS -> ProSlider(if (hasFocus) state.focusDiopters / state.minFocus else 0f, "Jarak fokus") { t ->
            onAction(CameraAction.SetFocus(state.minFocus * t))
          }
          ProParam.EV -> {
            val span = (state.evRange.last - state.evRange.first).coerceAtLeast(1)
            ProSlider((state.ev - state.evRange.first).toFloat() / span, "Kompensasi eksposur") { t ->
              onAction(CameraAction.SetEv(state.evRange.first + (t * span).roundToInt()))
            }
          }
          ProParam.WB -> {
            WB_PRESETS.forEach { (mode, label) ->
              Chip(label, selected = state.wbMode == mode) { onAction(CameraAction.SetWb(mode)) }
            }
            ProSlider((state.kelvin - 2500) / 6000f, "White balance Kelvin") { t ->
              onAction(CameraAction.SetKelvin(2500 + (t * 6000).roundToInt()))
            }
          }
        }
        // back to automatic, offered only when something is hand-set
        if (state.manual || state.manualFocus) {
          Chip("AUTO", selected = false, modifier = Modifier.testTag("pro_auto")) { onAction(CameraAction.SetAuto) }
        }
      }
    }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
      ProValue("ISO", if (state.manual) state.iso.toString() else "AUTO", open == ProParam.ISO) { toggle(ProParam.ISO) }
      ProValue("S", if (state.manual) shutterLabel(state.exposureNs) else "AUTO", open == ProParam.SHUTTER) {
        toggle(ProParam.SHUTTER)
      }
      if (hasFocus) {
        ProValue("F", if (state.manualFocus) "%.1f".format(state.focusDiopters) else "AF", open == ProParam.FOCUS) {
          toggle(ProParam.FOCUS)
        }
      }
      if (hasEv) {
        ProValue("EV", if (state.manual) "-" else "%+d".format(state.ev), open == ProParam.EV) { toggle(ProParam.EV) }
      }
      ProValue(
        "WB",
        if (state.wbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) {
          "${state.kelvin}K"
        } else {
          WB_PRESETS.firstOrNull { it.first == state.wbMode }?.second ?: "A"
        },
        open == ProParam.WB,
      ) { toggle(ProParam.WB) }
    }
  }
}

@Composable
private fun RowScope.ProSlider(value: Float, label: String, onChange: (Float) -> Unit) {
  Slider(
    value = if (value.isNaN()) 0f else value.coerceIn(0f, 1f),
    onValueChange = onChange,
    modifier = Modifier.weight(1f).height(36.dp).semantics { contentDescription = label },
    colors = SliderDefaults.colors(
      thumbColor = MaterialTheme.colorScheme.primary,
      activeTrackColor = MaterialTheme.colorScheme.primary,
      inactiveTrackColor = MaterialTheme.colorScheme.outline,
    ),
  )
}

@Composable
private fun RowScope.ProValue(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .weight(1f)
      .fillMaxHeight()
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .testTag("pro_$label"),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Label(label, small = true, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Label(value, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
  }
}

private fun logLerp(a: Long, b: Long, t: Float): Long {
  val la = ln(a.coerceAtLeast(1).toDouble())
  val lb = ln(b.coerceAtLeast(1).toDouble())
  return exp(la + (lb - la) * t).toLong().coerceIn(a, b)
}

/** Where [v] sits between [a] and [b] on a logarithmic slider, 0..1. */
private fun logPosition(v: Long, a: Long, b: Long): Float {
  val la = ln(a.coerceAtLeast(1).toDouble())
  val lb = ln(b.coerceAtLeast(1).toDouble())
  if (lb <= la) return 0f
  return ((ln(v.coerceAtLeast(1).toDouble()) - la) / (lb - la)).toFloat().coerceIn(0f, 1f)
}

private fun shutterLabel(ns: Long): String =
  if (ns >= 1_000_000_000L) "%.1fs".format(ns / 1_000_000_000.0) else "1/%.0f".format(1_000_000_000.0 / ns.coerceAtLeast(1))

// ---------------------------------------------------------------- shared pieces

/** A panel or bar is a surface: a tap on its empty space must not fall through to tap-to-focus underneath. */
internal fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures() }

/** Instrument-panel label: Space Mono, ALL CAPS. */
@Composable
internal fun Label(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  small: Boolean = false,
) = Text(
  text.uppercase(),
  modifier = modifier,
  color = color,
  style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
  maxLines = 1,
  overflow = TextOverflow.Ellipsis,
)

/** Bare icon with a 40 dp touch area; [active] brightens it. */
@Composable
internal fun IconBtn(
  @DrawableRes icon: Int,
  label: String,
  modifier: Modifier = Modifier,
  active: Boolean = false,
  tint: Color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
  onClick: () -> Unit,
) {
  Box(
    modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp)) }
}

/**
 * Round button of the rails: outlined on the black, white with a black glyph when [on]. Icon or short text.
 * It carries its own dark fill, so it still reads where a rail has to lie over the picture.
 */
@Composable
internal fun RoundBtn(
  label: String,
  modifier: Modifier = Modifier,
  @DrawableRes icon: Int? = null,
  text: String? = null,
  on: Boolean = false,
  enabled: Boolean = true,
  size: Dp = 44.dp,
  onClick: () -> Unit,
) {
  val tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  Box(
    modifier
      .size(size)
      .alpha(if (enabled) 1f else 0.35f)
      .clip(CircleShape)
      .background(if (on) MaterialTheme.colorScheme.primary else Scrim)
      .border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape)
      .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    if (icon != null) {
      Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    } else {
      Text(
        text.orEmpty(),
        color = tint,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.semantics { contentDescription = label },
      )
    }
  }
}

/** Small pill for one of a few choices; the chosen one is inverted. */
@Composable
internal fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  Box(
    modifier
      .heightIn(min = 28.dp)
      .widthIn(min = 28.dp)
      .clip(shape)
      .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
      .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant, shape)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 7.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

// ---------------------------------------------------------------- previews: the cover at its two turns (280 dpi)

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 427, heightDp = 363)
@Composable
private fun UprightPreview() {
  CoverCamTheme {
    CameraScreen(
      state = CameraUiState(ready = true, lenses = listOf("1.0x", "0.5x"), pictureAspect = 0.75f, status = "foto tersimpan"),
      onAction = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 363, heightDp = 427)
@Composable
private fun TurnedRecordingPreview() {
  CoverCamTheme {
    CameraScreen(
      state = CameraUiState(
        ready = true,
        mode = CameraEngine.Mode.VIDEO,
        lenses = listOf("1.0x", "0.5x"),
        recording = true,
        recordingSeconds = 75,
        zoom = 2f,
      ),
      onAction = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 427, heightDp = 363)
@Composable
private fun ProPreview() {
  CoverCamTheme {
    CameraScreen(
      state = CameraUiState(
        ready = true,
        mode = CameraEngine.Mode.PRO,
        lenses = listOf("1.0x", "0.5x"),
        pictureAspect = 0.75f,
        manual = true,
        iso = 400,
        minFocus = 10f,
        evRange = -4..4,
        rawActive = true,
      ),
      onAction = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 427, heightDp = 363)
@Composable
private fun ModeListPreview() {
  CoverCamTheme { CameraScreen(state = CameraUiState(ready = true, modesOpen = true), onAction = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 427, heightDp = 363)
@Composable
private fun PermissionPreview() {
  CoverCamTheme { CameraScreen(state = CameraUiState(cameraGranted = false), onAction = {}) }
}
