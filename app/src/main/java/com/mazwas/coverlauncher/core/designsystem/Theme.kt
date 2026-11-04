package com.mazwas.coverlauncher.core.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mazwas.coverlauncher.R

// Nothing-inspired tokens (dark / OLED). Monochrome; red is a signal, never decoration.
private val Black = Color(0xFF000000)
private val Surface = Color(0xFF111111)
private val SurfaceRaised = Color(0xFF1A1A1A)
private val Border = Color(0xFF222222)
private val BorderVisible = Color(0xFF333333)
private val TextDisabled = Color(0xFF666666)
private val TextSecondary = Color(0xFF999999)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextDisplay = Color(0xFFFFFFFF)
private val Accent = Color(0xFFD71921)

/** The status colours Material has no role for. */
@Immutable
data class StatusColors(val disabled: Color, val success: Color, val warning: Color)

private val LocalStatusColors = staticCompositionLocalOf {
  StatusColors(disabled = TextDisabled, success = Color(0xFF4A9E5C), warning = Color(0xFFD4A843))
}

val MaterialTheme.status: StatusColors
  @Composable get() = LocalStatusColors.current

private val NothingColors = darkColorScheme(
  primary = TextDisplay,
  onPrimary = Black,
  secondary = TextSecondary,
  onSecondary = Black,
  tertiary = Accent,
  error = Accent,
  onError = TextDisplay,
  background = Black,
  onBackground = TextPrimary,
  surface = Surface,
  onSurface = TextPrimary,
  surfaceVariant = SurfaceRaised,
  onSurfaceVariant = TextSecondary,
  surfaceContainer = SurfaceRaised,
  surfaceContainerHigh = SurfaceRaised,
  outline = BorderVisible,
  outlineVariant = Border,
  inverseSurface = TextDisplay,
  inverseOnSurface = Black,
)

private val Doto = FontFamily(
  Font(
    R.font.doto, FontWeight.Black,
    variationSettings = FontVariation.Settings(FontVariation.weight(900), FontVariation.Setting("ROND", 100f)),
  ),
)

private val SpaceGrotesk = FontFamily(
  Font(R.font.space_grotesk, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
  Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
  Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val SpaceMono = FontFamily(
  Font(R.font.space_mono_regular, FontWeight.Normal),
  Font(R.font.space_mono_bold, FontWeight.Bold),
)

// Three sizes per screen: Doto hero, Space Grotesk body, Space Mono ALL CAPS labels.
private val NothingType = Typography(
  displayLarge = TextStyle(fontFamily = Doto, fontWeight = FontWeight.Black, fontSize = 92.sp, lineHeight = 92.sp, letterSpacing = (-0.02).em),
  headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 24.sp, lineHeight = 28.sp),
  titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
  bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
  bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
  labelMedium = TextStyle(fontFamily = SpaceMono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
  labelSmall = TextStyle(fontFamily = SpaceMono, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.08.em),
)

@Composable
fun NothingTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = NothingColors, typography = NothingType) {
    // no Surface at the root, so Text would default to black
    CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
  }
}
