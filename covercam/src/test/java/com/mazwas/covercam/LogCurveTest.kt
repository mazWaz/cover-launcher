package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCurveTest {

  @Test
  fun `the curve is Protune Flat - black stays black, middle grey lands at 65 percent, the clipped sensor at full white`() {
    assertEquals(0.0, LogCurve.encode(0.0), 0.0)
    assertEquals(0.6456, LogCurve.encode(0.18), 0.0005)
    assertEquals(1.0, LogCurve.encode(1.0), 1e-12)
  }

  @Test
  fun `the camera gets 64 evenly spaced points that only rise, from black to full white`() {
    val p = LogCurve.points()
    assertEquals(128, p.size)
    for (i in 0 until 64) assertEquals(i / 63f, p[i * 2], 1e-6f)
    for (i in 1 until 64) assertTrue("point $i", p[i * 2 + 1] > p[i * 2 - 1])
    assertEquals(0f, p[1], 0f)
    assertEquals(1f, p[127], 0f)
  }
}
