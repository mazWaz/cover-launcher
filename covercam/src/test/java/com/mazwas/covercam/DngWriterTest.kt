package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Test

class DngWriterTest {

  private val w = 16
  private val h = 16

  private fun mosaic(value: (x: Int, y: Int) -> Int): ByteArray {
    val px = ByteArray(w * h * 2)
    for (y in 0 until h) for (x in 0 until w) {
      val v = value(x, y)
      px[2 * (y * w + x)] = v.toByte()
      px[2 * (y * w + x) + 1] = (v shr 8).toByte()
    }
    return px
  }

  @Test
  fun `a lone hot photosite drops to its brightest neighbour and a real edge stays`() {
    // a dark, slightly uneven frame (70..79 over a black level of 64), one photosite at 900, a bright edge from x = 10
    val scene = { x: Int, y: Int -> if (x >= 10) 600 else 70 + (x * 3 + y) % 10 }
    val px = mosaic { x, y -> if (x == 6 && y == 6) 900 else scene(x, y) }

    assertEquals(1, DngWriter.removeHotPixels(px, w, h, black = 64))

    // the brightest of (4|6|8, 4|6|8) around it is 78, at (8, 4) and (4, 6)
    val expected = mosaic { x, y -> if (x == 6 && y == 6) 78 else scene(x, y) }
    assertEquals(expected.toList(), px.toList())
  }
}
