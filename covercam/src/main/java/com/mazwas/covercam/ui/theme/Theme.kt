package com.mazwas.covercam.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mazwas.covercam.R

// The launcher's Nothing tokens (app/core/designsystem/Theme.kt). CoverCam is its own APK, so they are copied here.
private val Black = Color(0xFF000000)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextSecondary = Color(0xFF999999)
private val Accent = Color(0xFFD71921)

/** Flat see-through black behind controls that sit on the live picture. */
val Scrim = Color(0x99000000)

/** "Level" on the horizon line: the one status colour Material has no role for. */
val Success = Color(0xFF4A9E5C)

private val NothingColors = darkColorScheme(
  primary = Color(0xFFFFFFFF),
  onPrimary = Black,
  secondary = TextSecondary,
  onSecondary = Black,
  tertiary = Accent,
  error = Accent,
  onError = Color(0xFFFFFFFF),
  background = Black,
  onBackground = TextPrimary,
  surface = Color(0xFF111111),
  onSurface = TextPrimary,
  surfaceVariant = Color(0xFF1A1A1A),
  onSurfaceVariant = TextSecondary,
  outline = Color(0xFF333333),
  outlineVariant = Color(0xFF222222),
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

// Space Grotesk = body, Space Mono = ALL CAPS labels; displaySmall is the self-timer countdown, headlineSmall the
// mode name shown for a moment when the mode changes.
private val NothingType = Typography(
  displaySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 64.sp),
  headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 24.sp, lineHeight = 28.sp),
  titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
  bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
  bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
  labelMedium = TextStyle(fontFamily = SpaceMono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
  labelSmall = TextStyle(fontFamily = SpaceMono, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.08.em),
)

@Composable
fun CoverCamTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = NothingColors, typography = NothingType) {
    // no Surface at the root, so Text would default to black
    CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
  }
}
