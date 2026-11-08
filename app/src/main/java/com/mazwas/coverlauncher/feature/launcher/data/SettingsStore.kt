package com.mazwas.coverlauncher.feature.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private val Context.dataStore by preferencesDataStore("launcher")

class SettingsStore(private val context: Context) {
  private val dockKey = stringPreferencesKey("dock")
  private val iconsKey = stringPreferencesKey("icons")
  private val wallpaperKey = longPreferencesKey("wallpaper")
  private val dotsKey = booleanPreferencesKey("dots")
  private val popupsKey = booleanPreferencesKey("popups")
  private val flippedKey = stringSetPreferencesKey("flipped")
  private val aodKey = booleanPreferencesKey("aod")

  val wallpaperFile = File(context.filesDir, "wallpaper.jpg")

  val settings: Flow<LauncherSettings> = context.dataStore.data.map { p ->
    LauncherSettings(
      dock = p[dockKey]?.split('\n')?.filter { it.isNotEmpty() },
      iconStyle = p[iconsKey]?.let(IconStyle::valueOf) ?: IconStyle.Mono,
      wallpaperVersion = p[wallpaperKey] ?: 0,
      dotFilter = p[dotsKey] ?: true,
      popups = p[popupsKey] ?: false,
      flipped = p[flippedKey].orEmpty(),
      aod = p[aodKey] ?: false,
    )
  }

  suspend fun setDock(keys: List<String>) = context.dataStore.edit { it[dockKey] = keys.joinToString("\n") }
  suspend fun setIconStyle(style: IconStyle) = context.dataStore.edit { it[iconsKey] = style.name }
  suspend fun setDotFilter(on: Boolean) = context.dataStore.edit { it[dotsKey] = on }
  suspend fun setPopups(on: Boolean) = context.dataStore.edit { it[popupsKey] = on }
  suspend fun setAod(on: Boolean) = context.dataStore.edit { it[aodKey] = on }
  suspend fun setFlipped(pkg: String, on: Boolean) = context.dataStore.edit {
    val now = it[flippedKey].orEmpty()
    it[flippedKey] = if (on) now + pkg else now - pkg
  }

  /** Copies the picked image, scaled down to just cover the 748×720 screen. */
  suspend fun importWallpaper(uri: Uri) {
    withContext(Dispatchers.IO) {
      val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val scale = max(COVER_W / info.size.width, COVER_H / info.size.height)
        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
      }
      wallpaperFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }
    context.dataStore.edit { it[wallpaperKey] = System.currentTimeMillis() }
  }

  suspend fun resetWallpaper() {
    withContext(Dispatchers.IO) { wallpaperFile.delete() }
    context.dataStore.edit { it.remove(wallpaperKey) }
  }

  private companion object {
    const val COVER_W = 748f
    const val COVER_H = 720f
  }
}
