package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The matchers behind night alignment and panorama stitching are pure functions, so they can be
 * tested without a device. Synthetic frames with a known shift are the cheapest real check.
 */
class AlignTest {

  private fun noise(w: Int, h: Int, seed: Int = 7): IntArray {
    // java.util.Random (not a hand-rolled LCG): the low bits of a linear congruential
    // generator repeat with a short period and make shift detection ambiguous.
    val rnd = java.util.Random(seed.toLong())
    val out = IntArray(w * h)
    for (i in out.indices) out[i] = rnd.nextInt(256)
    return out
  }

  /** b is [a] shifted left by [dx] columns: b[y][x] = a[y][x + dx]. */
  private fun shiftLeft(a: IntArray, w: Int, h: Int, dx: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        out[y * w + x] = if (x + dx < w) a[y * w + x + dx] else 0
      }
    }
    return out
  }

  @Test
  fun `bestOverlap finds a known column shift`() {
    val w = 240
    val h = 120
    val base = noise(w, h)
    val report = StringBuilder()
    var worst = 0
    // Overlap candidates are sampled every 8 px, so test shifts that land on that grid.
    for (dx in listOf(64, 96, 144)) {
      val shifted = shiftLeft(base, w, h, dx)
      val overlap = Align.bestOverlap(base, shifted, w, h)
      val measuredDx = w - overlap
      report.append("dx=$dx overlap=$overlap measured=$measuredDx; ")
      worst = maxOf(worst, abs(measuredDx - dx))
    }
    assertTrue(report.toString(), worst <= 8)
  }

  @Test
  fun `bestOverlap stays in range for degenerate input`() {
    val overlap = Align.bestOverlap(IntArray(0), IntArray(0), 100, 100)
    assertTrue(overlap in 1..100)
  }

  @Test
  fun `estimateShift recovers a translation`() {
    val w = 96
    val h = 96
    val ref = noise(w, h, seed = 11)
    for ((dx, dy) in listOf(0 to 0, 2 to -1, -3 to 2)) {
      val cur = IntArray(w * h)
      for (y in 0 until h) {
        for (x in 0 until w) {
          val sy = y + dy
          val sx = x + dx
          cur[y * w + x] =
            if (sy in 0 until h && sx in 0 until w) ref[sy * w + sx] else 0
        }
      }
      // A displacement of (dx,dy) needs the opposite correction applied to cur.
      val (mx, my) = Align.estimateShift(ref, cur, w, h)
      assertTrue("expected ($(-dx),$(-dy)) got ($mx,$my)", abs(mx + dx) <= 1 && abs(my + dy) <= 1)
    }
  }

  @Test
  fun `toGray weights green the most`() {
    val red = Align.toGray(intArrayOf(0xFFFF0000.toInt()))[0]
    val green = Align.toGray(intArrayOf(0xFF00FF00.toInt()))[0]
    val blue = Align.toGray(intArrayOf(0xFF0000FF.toInt()))[0]
    assertTrue("g=$green r=$red b=$blue", green > red && red > blue)
  }
}
