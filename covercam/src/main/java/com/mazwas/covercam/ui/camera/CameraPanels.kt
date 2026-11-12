package com.mazwas.covercam.ui.camera

import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mazwas.covercam.R
import com.mazwas.covercam.ui.theme.Scrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen surface over the camera: a title row with its actions and a close button, then the content. */
@Composable
private fun Panel(
  title: String,
  onClose: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .swallowTaps()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
  ) {
    Row(
      Modifier.fillMaxWidth().height(40.dp).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Label(title, Modifier.weight(1f))
      actions()
      IconBtn(R.drawable.ic_x, "Tutup", active = true, modifier = Modifier.testTag("close"), onClick = onClose)
    }
    content()
  }
}

/** Height of the camera cutout at the bottom of the cover: lists end with this much room so they scroll clear of it. */
@Composable
private fun cutoutBottom() = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()

// ---------------------------------------------------------------- settings

@Composable
internal fun SettingsPanel(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  Panel("PENGATURAN", onClose = { onAction(CameraAction.CloseSettings) }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
      SettingRow("Suara shutter", onOff(state.soundOn)) { onAction(CameraAction.ToggleShutterSound) }
      SettingRow("Geotag", onOff(state.geotagOn)) { onAction(CameraAction.ToggleGeotag) }
      SettingRow("Garis level", onOff(state.levelOn)) { onAction(CameraAction.ToggleLevel) }
      SettingRow("Histogram", onOff(state.histogram)) { onAction(CameraAction.SetHistogram(!state.histogram)) }
      SettingRow("Focus peaking", onOff(state.peaking)) { onAction(CameraAction.SetPeaking(!state.peaking)) }
      SettingRow("Rotasi dari IMU", onOff(state.imuRotationOn)) { onAction(CameraAction.ToggleImuRotation) }
      SettingRow("Kualitas JPEG", state.quality.toString())
      Slider(
        value = state.quality.toFloat().coerceIn(60f, 100f),
        onValueChange = { onAction(CameraAction.SetQuality(it.toInt())) },
        valueRange = 60f..100f,
        modifier = Modifier.height(36.dp).semantics { contentDescription = "Kualitas JPEG" },
        colors = SliderDefaults.colors(
          thumbColor = MaterialTheme.colorScheme.primary,
          activeTrackColor = MaterialTheme.colorScheme.primary,
          inactiveTrackColor = MaterialTheme.colorScheme.outline,
        ),
      )
      SettingRow("Kalibrasi preview", "PUTAR / CERMIN") { onAction(CameraAction.StartCalibration) }
      SettingRow("Kemampuan HAL", state.capsInfo)
      SettingRow("Versi", "CoverCam 0.1")
      SettingRow("Reset kalibrasi", "", destructive = true) { onAction(CameraAction.ResetCalibration) }
      Spacer(Modifier.height(cutoutBottom() + 8.dp))
    }
  }
}

private fun onOff(on: Boolean) = if (on) "ON" else "OFF"

/** [onClick] null = a read-only line. */
@Composable
private fun SettingRow(label: String, value: String, destructive: Boolean = false, onClick: (() -> Unit)? = null) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 40.dp)
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.bodyMedium,
      color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Label(value, color = if (onClick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// ---------------------------------------------------------------- modes

/** All ten modes with a word on what each does, as large targets: the direct way to a mode. */
@Composable
internal fun ModePanel(state: CameraUiState, onAction: (CameraAction) -> Unit) {
  Panel("MODE", onClose = { onAction(CameraAction.CloseModes) }) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = cutoutBottom() + 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
        Text(
          "Geser gambar ke kiri atau kanan untuk pindah mode tanpa membuka daftar ini.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
      items(MODES, key = { it.mode.name }) { info ->
        val selected = info.mode == state.mode
        val shape = RoundedCornerShape(14.dp)
        Column(
          Modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
            .selectable(selected = selected, role = Role.Tab) { onAction(CameraAction.SetMode(info.mode)) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("pick_${info.mode.name}"),
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            info.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
          )
          Text(
            info.hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }
    }
  }
}

// ---------------------------------------------------------------- gallery

@Composable
internal fun GalleryPanel(items: List<GalleryItem>, onAction: (CameraAction) -> Unit) {
  Panel("GALERI (${items.size})", onClose = { onAction(CameraAction.CloseGallery) }) {
    if (items.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Belum ada foto atau video", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = cutoutBottom() + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(items, key = { it.uri.toString() }) { item -> MediaTile(item) { onAction(CameraAction.OpenViewer(item)) } }
      }
    }
  }
}

@Composable
private fun MediaTile(item: GalleryItem, onClick: () -> Unit) {
  val thumb = rememberThumbnail(item.uri, 256)
  val what = if (item.isVideo) "Video" else "Foto"
  Box(
    Modifier
      .aspectRatio(1f)
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClickLabel = "Buka $what", role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    val image = thumb.bitmap
    when {
      image != null -> Image(image, contentDescription = what, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      // a file the platform can't draw a thumbnail for (some DNGs): name it instead of leaving an empty tile
      thumb.loaded -> Label(if (item.isVideo) "VIDEO" else "RAW")
      else -> Label("…")
    }
    if (item.isVideo && image != null) PlayBadge(Modifier.size(28.dp))
  }
}

@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
  Box(modifier.background(Scrim, CircleShape), contentAlignment = Alignment.Center) {
    Icon(
      painterResource(R.drawable.ic_play),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.fillMaxSize(0.5f),
    )
  }
}

// ---------------------------------------------------------------- viewer

@Composable
internal fun ViewerPanel(item: GalleryItem, onAction: (CameraAction) -> Unit) {
  // deleting takes two taps: the first one only arms it
  var confirmDelete by remember(item) { mutableStateOf(false) }
  val thumb = rememberThumbnail(item.uri, 1024)
  Panel(
    title = if (item.isVideo) "VIDEO" else "FOTO",
    onClose = { onAction(CameraAction.CloseViewer) },
    actions = {
      if (confirmDelete) {
        PillBtn("HAPUS?", destructive = true, modifier = Modifier.testTag("delete_confirm")) {
          onAction(CameraAction.DeleteMedia(item))
        }
      } else {
        IconBtn(R.drawable.ic_trash_2, "Hapus", tint = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("delete")) {
          confirmDelete = true
        }
      }
      IconBtn(R.drawable.ic_send, "Bagikan", active = true, modifier = Modifier.testTag("share")) {
        onAction(CameraAction.ShareMedia(item))
      }
    },
  ) {
    Box(Modifier.fillMaxSize().padding(bottom = cutoutBottom()), contentAlignment = Alignment.Center) {
      val image = thumb.bitmap
      when {
        image != null -> Image(
          image,
          contentDescription = if (item.isVideo) "Pratinjau video" else "Pratinjau foto",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize().padding(4.dp),
        )
        thumb.loaded -> Label("TIDAK ADA PRATINJAU")
        else -> Label("…")
      }
      if (item.isVideo) {
        PlayBadge(
          Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Putar video", role = Role.Button) { onAction(CameraAction.PlayMedia(item)) }
            .testTag("play"),
        )
      }
    }
  }
}

// ---------------------------------------------------------------- permission

@Composable
internal fun PermissionPanel(onAction: (CameraAction) -> Unit) {
  val pkg = LocalContext.current.packageName
  Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).swallowTaps().padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Label("IZIN KAMERA", color = MaterialTheme.colorScheme.onSurface)
    Text(
      "CoverCam belum boleh memakai kamera. Di layar cover dialog izin kadang tidak muncul; kalau tombol di bawah " +
        "tidak bereaksi, berikan lewat adb:",
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )
    Text(
      "adb shell pm grant $pkg android.permission.CAMERA",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PillBtn("BERI IZIN", primary = true, modifier = Modifier.testTag("grant")) { onAction(CameraAction.RequestPermission) }
      PillBtn("PENGATURAN") { onAction(CameraAction.OpenAppSettings) }
    }
  }
}

/** Pill button. Primary = white fill; otherwise outlined (red outline when destructive). */
@Composable
internal fun PillBtn(
  text: String,
  modifier: Modifier = Modifier,
  primary: Boolean = false,
  destructive: Boolean = false,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(50)
  val content = when {
    primary -> MaterialTheme.colorScheme.onPrimary
    destructive -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
  }
  Box(
    modifier
      .heightIn(min = 36.dp)
      .clip(shape)
      .background(if (primary) MaterialTheme.colorScheme.primary else Color.Transparent)
      .border(1.dp, if (primary) Color.Transparent else if (destructive) content else MaterialTheme.colorScheme.outline, shape)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center,
  ) { Label(text, color = content) }
}

// ---------------------------------------------------------------- thumbnails

/** [loaded] false = still reading; true with a null [bitmap] = the file has no picture to show. */
internal class Thumb(val loaded: Boolean, val bitmap: ImageBitmap?)

/**
 * The platform's own thumbnail for a MediaStore item, read off the main thread. It is cached by MediaStore,
 * already turned upright, and works for videos as well as photos.
 */
@Composable
internal fun rememberThumbnail(uri: Uri?, sizePx: Int): Thumb {
  val resolver = LocalContext.current.contentResolver
  val thumb by produceState(Thumb(loaded = false, bitmap = null), uri, sizePx) {
    value = Thumb(
      loaded = true,
      bitmap = uri?.let {
        withContext(Dispatchers.IO) {
          runCatching { resolver.loadThumbnail(it, Size(sizePx, sizePx), null).asImageBitmap() }.getOrNull()
        }
      },
    )
  }
  return thumb
}
