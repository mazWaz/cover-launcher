package com.mazwas.coverlauncher.feature.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App and notification icons as bitmaps, rendered off the main thread and cached. */
class IconLoader(private val context: Context, private val notifications: NotificationStore) {
  private val launcherApps = context.getSystemService(LauncherApps::class.java)
  private val density = context.resources.displayMetrics.densityDpi
  private val cache = LruCache<String, ImageBitmap>(256)

  fun cached(app: LaunchableApp, style: IconStyle, sizePx: Int): ImageBitmap? = cache.get("${app.key}|$style|$sizePx")

  suspend fun app(app: LaunchableApp, style: IconStyle, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = "${app.key}|$style|$sizePx"
    cache.get(key) ?: run {
      val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app.packageName, app.className))
      val icon = launcherApps.resolveActivity(intent, Process.myUserHandle())?.getIcon(density) ?: return@run null
      val bitmap = if (style == IconStyle.Mono) mono(icon, sizePx) else render(icon, sizePx)
      bitmap.asImageBitmap().also { cache.put(key, it) }
    }
  }

  /** The notification's small icon in white; tint it in the UI. */
  suspend fun notification(key: String, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val drawable = notifications.get(key)?.notification?.smallIcon?.loadDrawable(context) ?: return@withContext null
    drawable.mutate().setTint(Color.WHITE)
    render(drawable, sizePx).asImageBitmap()
  }

  /**
   * Nothing-style: the app's monochrome glyph, black on a light disc.
   * Apps without a monochrome layer get their normal icon in greyscale.
   */
  private fun mono(icon: Drawable, size: Int): Bitmap {
    val glyph = (icon as? AdaptiveIconDrawable)?.monochrome?.mutate()
      ?: return render(icon.mutate().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
      val canvas = Canvas(bitmap)
      canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC })
      glyph.setTint(Color.BLACK)
      // adaptive layers are 108 units with the visible icon in the middle 72: draw the layer 1.5× the disc
      val inset = size / 4
      glyph.setBounds(-inset, -inset, size + inset, size + inset)
      glyph.draw(canvas)
    }
  }

  private fun render(drawable: Drawable, size: Int): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
      drawable.setBounds(0, 0, size, size)
      drawable.draw(Canvas(it))
    }

  private companion object {
    const val DISC = 0xFFE8E8E8.toInt() // text-primary
  }
}
