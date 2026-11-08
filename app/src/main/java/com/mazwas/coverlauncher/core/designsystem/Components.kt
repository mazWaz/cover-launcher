package com.mazwas.coverlauncher.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Keeps content off the camera when the cover is turned: it is on the right at 90°, the top at 180°, the left at 270°.
 * The bottom one (0°) each screen handles itself, so lists can scroll under it.
 */
@Composable
fun Modifier.cutoutSides(): Modifier =
  windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))

/** Instrument-panel label: Space Mono, ALL CAPS. */
@Composable
fun Label(
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

/** Nothing's signature gauge: square segments with 2 dp gaps. */
@Composable
fun SegmentedBar(
  fraction: Float,
  modifier: Modifier = Modifier,
  segments: Int = 10,
  height: Dp = 6.dp,
  color: Color = MaterialTheme.colorScheme.primary,
) {
  val empty = MaterialTheme.colorScheme.outlineVariant
  Canvas(modifier.height(height)) {
    val gap = 2.dp.toPx()
    val w = (size.width - gap * (segments - 1)) / segments
    val filled = (fraction.coerceIn(0f, 1f) * segments).roundToInt()
    repeat(segments) { i ->
      drawRect(if (i < filled) color else empty, Offset(i * (w + gap), 0f), Size(w, size.height))
    }
  }
}

/** Uniform dot grid, drawn once per size. */
fun Modifier.dotGrid(color: Color, spacing: Dp = 14.dp, dot: Dp = 2.dp): Modifier = drawWithCache {
  val step = spacing.toPx()
  val area = size // inside buildList, `size` is the list's
  val points = buildList {
    var y = step / 2
    while (y < area.height) {
      var x = step / 2
      while (x < area.width) { add(Offset(x, y)); x += step }
      y += step
    }
  }
  onDrawBehind { drawPoints(points, PointMode.Points, color, strokeWidth = dot.toPx(), cap = StrokeCap.Round) }
}

/** Round switch-like button: white with a black glyph when on, outlined when off. Long-press: details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundToggle(
  @DrawableRes icon: Int,
  label: String,
  on: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  val fill by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, tween(150), label = "fill")
  val tint by animateColorAsState(
    if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(150), label = "tint",
  )
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(
      Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(fill)
        .border(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape)
        .combinedClickable(onLongClick = onLongClick, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp)) }
    Label(label, small = true, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.status.disabled)
  }
}

/** Pill button. Primary = white fill; otherwise outlined (red outline when destructive). */
@Composable
fun PillButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  primary: Boolean = false,
  destructive: Boolean = false,
) {
  val shape = RoundedCornerShape(50)
  val content = when {
    primary -> MaterialTheme.colorScheme.onPrimary
    destructive -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
  }
  Box(
    modifier
      .heightIn(min = 40.dp)
      .clip(shape)
      .background(if (primary) MaterialTheme.colorScheme.primary else Color.Transparent)
      .border(1.dp, if (primary) Color.Transparent else if (destructive) content else MaterialTheme.colorScheme.outline, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp),
    contentAlignment = Alignment.Center,
  ) { Label(text, color = content) }
}

/** 2–4 options; the active one is inverted. */
@Composable
fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(50)
  Row(modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
    options.forEach { (value, text) ->
      val active = value == selected
      Box(
        Modifier
          .height(36.dp)
          .clip(shape)
          .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
          .clickable { onSelect(value) }
          .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
      ) { Label(text, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}
