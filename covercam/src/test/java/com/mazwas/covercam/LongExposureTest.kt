package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongExposureTest {

  @Test
  fun `a shutter the preview can follow is used as it is`() {
    assertEquals(100 to 50_000_000L, LongExposure.preview(100, 50_000_000L, 3200))
    assertEquals(400 to 100_000_000L, LongExposure.preview(400, 100_000_000L, 3200))
  }

  @Test
  fun `a longer shutter is previewed at a tenth of a second with the gain that keeps the brightness`() {
    // 200 ms at ISO 100 is 20 "ISO-seconds"; at 100 ms that takes ISO 200
    assertEquals(200 to LongExposure.PREVIEW_MAX_NS, LongExposure.preview(100, 200_000_000L, 3200))
  }

  @Test
  fun `the gain stops at the sensor's highest ISO instead of asking for one it does not have`() {
    // 30 s at ISO 50 would need ISO 15000
    assertEquals(3200 to LongExposure.PREVIEW_MAX_NS, LongExposure.preview(50, 30_000_000_000L, 3200))
  }

  @Test
  fun `only an exposure over a second counts as long`() {
    assertFalse(LongExposure.isLong(1_000_000_000L))
    assertTrue(LongExposure.isLong(1_000_000_001L))
  }
}
