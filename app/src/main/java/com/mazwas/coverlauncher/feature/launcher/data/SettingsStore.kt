package com.mazwas.coverlauncher.feature.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherSettings
import com.mazwas.coverlauncher.feature.launcher.domain.toEntrySet
import com.mazwas.coverlauncher.feature.launcher.domain.toIntMap
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
  private val rotationsKey = stringSetPreferencesKey("rotations")
  private val densitiesKey = stringSetPreferencesKey("densities")
  private val densityKey = intPreferencesKey("density")
  private val restrictedKey = stringSetPreferencesKey("restricted")
  private val tilesKey = stringPreferencesKey("tiles")
  private val hiddenTilesKey = stringSetPreferencesKey("hidden_tiles")
  private val aodKey = booleanPreferencesKey("aod")
  private val bankingKey = stringSetPreferencesKey("banking")
  private val deepSleepKey = booleanPreferencesKey("deep_sleep")

  val wallpaperFile = File(context.filesDir, "wallpaper.jpg")

  val settings: Flow<LauncherSettings> = context.dataStore.data.map { p ->
    LauncherSettings(
      dock = p[dockKey]?.split('\n')?.filter { it.isNotEmpty() },
      iconStyle = p[iconsKey]?.let(IconStyle::valueOf) ?: IconStyle.Mono,
      wallpaperVersion = p[wallpaperKey] ?: 0,
      dotFilter = p[dotsKey] ?: true,
      popups = p[popupsKey] ?: false,
      appRotation = rotations(p),
      appDensity = p[densitiesKey].orEmpty().toIntMap(),
      density = p[densityKey],
      restricted = p[restrictedKey].orEmpty(),
      tiles = p[tilesKey]?.split(','),
      hiddenTiles = p[hiddenTilesKey].orEmpty(),
      aod = p[aodKey] ?: false,
      banking = p[bankingKey].orEmpty(),
      deepSleep = p[deepSleepKey] ?: false,
    )
  }

  suspend fun setDock(keys: List<String>) = context.dataStore.edit { it[dockKey] = keys.joinToString("\n") }
  suspend fun setIconStyle(style: IconStyle) = context.dataStore.edit { it[iconsKey] = style.name }
  suspend fun setDotFilter(on: Boolean) = context.dataStore.edit { it[dotsKey] = on }
  suspend fun setPopups(on: Boolean) = context.dataStore.edit { it[popupsKey] = on }
  suspend fun setAod(on: Boolean) = context.dataStore.edit { it[aodKey] = on }
  suspend fun setDeepSleep(on: Boolean) = context.dataStore.edit { it[deepSleepKey] = on }

  /** [rotation] null: the app follows the rotate tile again. */
  suspend fun setAppRotation(pkg: String, rotation: Int?) = context.dataStore.edit {
    it[rotationsKey] = rotations(it).with(pkg, rotation).toEntrySet()
    it.remove(flippedKey) // folded into rotations
  }

  /** [dpi] null: the app gets the cover's density again. */
  suspend fun setAppDensity(pkg: String, dpi: Int?) = context.dataStore.edit {
    it[densitiesKey] = it[densitiesKey].orEmpty().toIntMap().with(pkg, dpi).toEntrySet()
  }

  suspend fun setRestricted(pkg: String, on: Boolean) = context.dataStore.edit {
    val now = it[restrictedKey].orEmpty()
    it[restrictedKey] = if (on) now + pkg else now - pkg
  }

  suspend fun setBanking(pkg: String, on: Boolean) = context.dataStore.edit {
    val now = it[bankingKey].orEmpty()
    it[bankingKey] = if (on) now + pkg else now - pkg
  }

  suspend fun setTileOrder(names: List<String>) = context.dataStore.edit { it[tilesKey] = names.joinToString(",") }

  suspend fun setTileHidden(name: String, hidden: Boolean) = context.dataStore.edit {
    val now = it[hiddenTilesKey].orEmpty()
    it[hiddenTilesKey] = if (hidden) now + name else now - name
  }

  suspend fun setDensity(dpi: Int?) = context.dataStore.edit { if (dpi == null) it.remove(densityKey) else it[densityKey] = dpi }

  // "flipped" (upside down only) came before per-app rotations: still read, as 180°
  private fun rotations(p: Preferences) = p[flippedKey].orEmpty().associateWith { 2 } + p[rotationsKey].orEmpty().toIntMap()

  private fun Map<String, Int>.with(pkg: String, value: Int?) = if (value == null) this - pkg else this + (pkg to value)

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
