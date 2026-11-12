package com.mazwas.covercam

/**
 * Manual white balance from a colour temperature, worked out with the sensor's own calibration: the same matrices
 * a DNG carries. All matrices are 3 x 3, row by row.
 *
 * The sensor does not see white as equal red, green and blue: under office light this one reads red at about 0.55
 * and blue at about 0.5 of green. Gains taken from the colour of a black body on a screen (close to 1 : 1 : 1)
 * therefore left the picture green.
 */
object WhiteBalance {

  /** XYZ with a D50 white (where the forward matrix ends) to linear sRGB (what the camera puts out). */
  private val XYZ_D50_TO_SRGB = doubleArrayOf(
    3.1338561, -1.6168667, -0.4906146,
    -0.9787684, 1.9161415, 0.0334540,
    0.0719453, -0.2289914, 1.4052427,
  )

  /** Linear sRGB to linear Rec.2020 (ITU-R BT.2087). */
  private val SRGB_TO_REC2020 = doubleArrayOf(
    0.627404, 0.329283, 0.043313,
    0.069097, 0.919540, 0.011362,
    0.016391, 0.088013, 0.895595,
  )

  /**
   * What the sensor reads off a white card lit by a black body of [kelvin]: red, green, blue, with green = 1.
   * The gains that make it white are 1 / red and 1 / blue. Null when the camera reports no colour matrix.
   */
  fun neutral(kelvin: Int, c: DngWriter.RawColor): DoubleArray? {
    val w = second(kelvin, c)
    val toSensor = mix(c.color1 ?: return null, c.color2, w)
    val seen = times(toSensor, blackBody(kelvin.toDouble()))
    val calibrated = c.calib1?.let { times(mix(it, c.calib2, w), seen) } ?: seen
    return DoubleArray(3) { calibrated[it] / calibrated[1] }
  }

  /** The matrix from white-balanced sensor colours to linear sRGB at [kelvin]. Null without a forward matrix. */
  fun toSrgb(kelvin: Int, c: DngWriter.RawColor): DoubleArray? {
    val forward = mix(c.fwd1 ?: return null, c.fwd2, second(kelvin, c))
    return product(XYZ_D50_TO_SRGB, forward)
  }

  /** The same matrix ending in linear Rec.2020, for a stream tagged BT.2020 (10-bit video). */
  fun toRec2020(kelvin: Int, c: DngWriter.RawColor): DoubleArray? = toSrgb(kelvin, c)?.let { product(SRGB_TO_REC2020, it) }

  /** How much of the second calibration set counts at [kelvin]: linear in 1 / T between the two lights, as in DNG. */
  private fun second(kelvin: Int, c: DngWriter.RawColor): Double {
    val first = 1 / kelvinOf(c.illum1)
    val other = 1 / kelvinOf(c.illum2)
    if (first == other) return 0.0
    return ((1.0 / kelvin - first) / (other - first)).coerceIn(0.0, 1.0)
  }

  // shortcut: only the lights this phone calibrates with (D65, A) and the other D ones, anything else counts as D50;
  // add its code when a sensor reports another
  private fun kelvinOf(illuminant: Int): Double = when (illuminant) {
    17 -> 2856.0
    20 -> 5503.0
    21 -> 6504.0
    22 -> 7504.0
    else -> 5003.0
  }

  /** XYZ (Y = 1) of a black body, by Kim's fit of the Planckian locus: within 0.001 in xy from 2222 K to 25000 K. */
  private fun blackBody(t: Double): DoubleArray {
    val x = if (t <= 4000) {
      -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
    } else {
      -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
    }
    val y = if (t <= 4000) {
      -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
    } else {
      3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
    }
    return doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
  }

  private fun mix(a: DoubleArray, b: DoubleArray?, w: Double): DoubleArray =
    if (b == null) a else DoubleArray(a.size) { (1 - w) * a[it] + w * b[it] }

  private fun product(a: DoubleArray, b: DoubleArray): DoubleArray =
    DoubleArray(9) { i -> (0..2).sumOf { a[i / 3 * 3 + it] * b[it * 3 + i % 3] } }

  private fun times(m: DoubleArray, v: DoubleArray): DoubleArray =
    DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }
}
