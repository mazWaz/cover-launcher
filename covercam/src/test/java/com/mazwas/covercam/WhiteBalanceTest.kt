package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The calibration the Flip5's main lens (id 0) reports, in 1024ths (`dumpsys media.camera`, 2026-11-10). */
class WhiteBalanceTest {

  private fun m(vararg n: Int) = DoubleArray(9) { n[it] / 1024.0 }

  private val flip5 = DngWriter.RawColor(
    color1 = m(831, -188, -126, -609, 1538, 60, -166, 341, 444),
    color2 = m(1759, -987, -166, -419, 1517, -93, -29, 216, 510),
    calib1 = m(874, 0, 0, 0, 1024, 0, 0, 0, 1141),
    calib2 = m(874, 0, 0, 0, 1024, 0, 0, 0, 1141),
    fwd1 = m(671, 133, 183, 266, 741, 17, 33, -390, 1202),
    fwd2 = m(507, 210, 271, 109, 786, 129, -70, -790, 1704),
    illum1 = 21,
    illum2 = 17,
  )

  @Test
  fun `a white card reads redder as the light gets warmer, and never as equal red green and blue`() {
    // the phone's own auto white balance read 0.53 to 0.55 and 0.48 to 0.49 under the office's roughly 3750 K
    val office = WhiteBalance.neutral(3750, flip5)!!
    assertEquals(0.565, office[0], 0.01)
    assertEquals(1.0, office[1], 0.0)
    assertEquals(0.513, office[2], 0.01)

    val daylight = WhiteBalance.neutral(6500, flip5)!!
    assertEquals(0.400, daylight[0], 0.005)
    assertEquals(0.744, daylight[2], 0.005)
  }

  @Test
  fun `the matrix to sRGB keeps white white`() {
    for (kelvin in listOf(2500, 4000, 6500, 8500)) {
      val t = WhiteBalance.toSrgb(kelvin, flip5)!!
      for (row in 0..2) assertEquals("row $row at $kelvin K", 1.0, t[row * 3] + t[row * 3 + 1] + t[row * 3 + 2], 0.01)
    }
  }

  @Test
  fun `the matrix for 10-bit video gives the same colour as the sRGB one, written in Rec2020`() {
    val sensor = doubleArrayOf(0.3, 0.5, 0.2)
    fun apply(t: DoubleArray) = DoubleArray(3) { r -> (0..2).sumOf { t[r * 3 + it] * sensor[it] } }
    val srgb = apply(WhiteBalance.toSrgb(6500, flip5)!!)
    val wide = apply(WhiteBalance.toRec2020(6500, flip5)!!)
    // ITU-R BT.2087, as printed in the recommendation
    assertEquals(0.6274 * srgb[0] + 0.3293 * srgb[1] + 0.0433 * srgb[2], wide[0], 1e-4)
    assertEquals(0.0691 * srgb[0] + 0.9195 * srgb[1] + 0.0114 * srgb[2], wide[1], 1e-4)
    assertEquals(0.0164 * srgb[0] + 0.0880 * srgb[1] + 0.8956 * srgb[2], wide[2], 1e-4)
    // and white stays white
    val t = WhiteBalance.toRec2020(6500, flip5)!!
    for (row in 0..2) assertEquals(1.0, t[row * 3] + t[row * 3 + 1] + t[row * 3 + 2], 0.01)
  }

  @Test
  fun `a camera that reports no calibration gets no manual white balance`() {
    assertNull(WhiteBalance.neutral(5000, DngWriter.RawColor()))
    assertNull(WhiteBalance.toSrgb(5000, DngWriter.RawColor()))
    assertNull(WhiteBalance.toRec2020(5000, DngWriter.RawColor()))
  }
}
