package com.mazwas.covercam

import kotlin.math.ln

/**
 * The tone curve of log video: GoPro's Protune Flat, `ln(112 x + 1) / ln(113)`.
 *
 * A curve that ends at 1 on purpose. The Flip5's HAL draws the last stretch of any curve up to full white, so one
 * that ends lower (Apple Log stops at 0.69 for a clipped sensor) turned every nearly clipped channel into a false
 * colour: green and purple rings around each lamp.
 */
object LogCurve {
  /** Linear [x] (0 = black, 0.18 = middle grey, 1 = the clipped sensor) to the encoded signal, 0..1. */
  fun encode(x: Double): Double = ln(112 * x + 1) / ln(113.0)

  /**
   * The curve as `TonemapCurve` wants it: x, y pairs. Evenly spaced on purpose: the HAL ignores the x of each
   * point and spreads the 64 outputs evenly, so points packed into the shadows came out as a far darker picture.
   */
  fun points(): FloatArray = FloatArray(POINTS * 2) { n ->
    val x = (n / 2) / (POINTS - 1.0)
    (if (n % 2 == 0) x else encode(x)).toFloat()
  }

  /** `TONEMAP_MAX_CURVE_POINTS` of every Flip5 lens. */
  private const val POINTS = 64
}
