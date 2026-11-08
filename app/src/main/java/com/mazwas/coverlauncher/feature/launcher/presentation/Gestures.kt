package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

private val SWIPE = 40.dp

/**
 * Fires once per drag that travels [SWIPE] up or down. Only gets drags no child scrolled with.
 * Keyed on Unit: new callbacks (any recomposition, e.g. the clock ticking) must not restart it and drop a swipe in progress.
 */
@Composable
fun Modifier.verticalSwipe(onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null): Modifier {
  val up by rememberUpdatedState(onUp)
  val down by rememberUpdatedState(onDown)
  return pointerInput(Unit) {
    val threshold = SWIPE.toPx()
    var total = 0f
    detectVerticalDragGestures(
      onDragStart = { total = 0f },
      onDragEnd = {
        when {
          total < -threshold -> up?.invoke()
          total > threshold -> down?.invoke()
        }
      },
    ) { change, dy ->
      change.consume()
      total += dy
    }
  }
}

/**
 * Fires when a list is pulled past its end: down at the top ([down] = true) or up at the bottom. Closes the drawer and
 * panel, expands the panel's tiles. Takes the overscroll so the list doesn't stretch meanwhile.
 */
@Composable
fun rememberPullPastEnd(down: Boolean, onPull: () -> Unit): NestedScrollConnection {
  val close by rememberUpdatedState(onPull)
  val threshold = with(LocalDensity.current) { SWIPE.toPx() * 1.5f }
  return remember(down, threshold) {
    object : NestedScrollConnection {
      var pulled = 0f

      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val toward = if (down) available.y > 0 else available.y < 0
        if (source != NestedScrollSource.UserInput || !toward) {
          pulled = 0f
          return Offset.Zero
        }
        pulled += kotlin.math.abs(available.y)
        if (pulled > threshold) {
          pulled = 0f
          close()
        }
        return available
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        pulled = 0f
        return Velocity.Zero
      }
    }
  }
}

/** Tap / long-press with a short shrink while pressed instead of a ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "press")
  return graphicsLayer { scaleX = scale; scaleY = scale }
    .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}
