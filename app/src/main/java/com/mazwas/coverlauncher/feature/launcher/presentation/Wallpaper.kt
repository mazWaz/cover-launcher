package com.mazwas.coverlauncher.feature.launcher.presentation

import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.core.designsystem.dotGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dot-matrix halftone: each cell becomes one white dot whose area follows the cell's brightness.
 * Runs on the GPU as a render effect, so the photo stays a photo on disk.
 */
private const val HALFTONE = """
uniform shader image;
uniform float cell;
half4 main(float2 p) {
  float2 c = (floor(p / cell) + 0.5) * cell;
  half3 rgb = image.eval(c).rgb;
  float l = dot(rgb, half3(0.2126, 0.7152, 0.0722));
  float r = 0.5 * cell * sqrt(l);
  float a = 1.0 - smoothstep(r - 0.75, r + 0.75, distance(p, c));
  return half4(half3(a * 0.8), 1.0);
}
"""

/** Black with a faint dot grid, or the user's picture (optionally as halftone) under a scrim. */
@Composable
fun WallpaperLayer(wallpaper: Wallpaper?, modifier: Modifier = Modifier) {
  if (wallpaper == null) {
    Box(modifier.fillMaxSize().dotGrid(Color.White.copy(alpha = 0.08f)))
    return
  }
  val image by produceState<ImageBitmap?>(null, wallpaper.path, wallpaper.version) {
    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(wallpaper.path)?.asImageBitmap() }
  }
  val cell = with(LocalDensity.current) { 5.dp.toPx() }
  val effect = remember(wallpaper.dots, cell) {
    if (!wallpaper.dots) null
    else RenderEffect.createRuntimeShaderEffect(RuntimeShader(HALFTONE).apply { setFloatUniform("cell", cell) }, "image")
      .asComposeRenderEffect()
  }
  image?.let {
    Image(
      it, contentDescription = null, contentScale = ContentScale.Crop,
      modifier = modifier.fillMaxSize().graphicsLayer { renderEffect = effect },
    )
  }
  // keeps white text readable on any picture
  Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = if (wallpaper.dots) 0.2f else 0.45f)))
}
