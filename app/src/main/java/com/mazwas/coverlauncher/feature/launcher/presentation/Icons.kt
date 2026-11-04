package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp

/** Null in previews: icons draw as placeholders. */
val LocalIconLoader = staticCompositionLocalOf<IconLoader?> { null }

@Composable
fun AppIcon(app: LaunchableApp, style: IconStyle, size: Dp, modifier: Modifier = Modifier) {
  val loader = LocalIconLoader.current
  val px = with(LocalDensity.current) { size.roundToPx() }
  val icon by produceState(loader?.cached(app, style, px), app.key, style, px) {
    value = loader?.app(app, style, px)
  }
  IconOrPlaceholder(icon, size, modifier)
}

@Composable
fun NotificationIcon(key: String, size: Dp, tint: Color, modifier: Modifier = Modifier) {
  val loader = LocalIconLoader.current
  val px = with(LocalDensity.current) { size.roundToPx() }
  val icon by produceState<ImageBitmap?>(null, key, px) { value = loader?.notification(key, px) }
  IconOrPlaceholder(icon, size, modifier, ColorFilter.tint(tint))
}

@Composable
private fun IconOrPlaceholder(icon: ImageBitmap?, size: Dp, modifier: Modifier, filter: ColorFilter? = null) {
  if (icon != null) Image(icon, contentDescription = null, modifier.size(size), colorFilter = filter)
  else Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
}
