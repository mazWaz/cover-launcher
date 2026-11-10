package com.mazwas.covercam

/**
 * Pure image-alignment helpers shared by night merge, panorama stitching and the
 * focus-peaking analyser. Kept free of Android types so they can be unit tested.
 */
internal object Align {

  /**
   * Searches the overlap width (in pixels) that best matches the tail of [a] against the
   * head of [b]. Returns the overlap, so the horizontal step is `w - overlap`.
   */
  fun bestOverlap(a: IntArray, b: IntArray, w: Int, h: Int, step: Int = 8): Int {
    if (w <= 0 || h <= 0 || a.size < w * h || b.size < w * h) return w / 3
    var best = w / 3
    var bestScore = Long.MAX_VALUE
    var o = w / 10
    while (o <= (w * 9) / 10) {
      var sad = 0L
      var count = 0
      var y = step
      while (y < h - step) {
        var x = 0
        while (x < o) {
          val d = a[y * w + (w - o + x)] - b[y * w + x]
          sad += if (d < 0) -d else d
          count++
          x += step
        }
        y += step
      }
      if (count > 0) {
        val score = sad / count
        if (score < bestScore) {
          bestScore = score
          best = o
        }
      }
      o += step
    }
    return best
  }

  /**
   * Coarse integer translation between two frames.
   *
   * Returns where the content of [ref] is found in [cur]: `ref[y][x] ~ cur[y + dy][x + dx]`. If the content of
   * [cur] sits 5 px to the right of [ref], this returns +5, and lining [cur] up means moving it by the negative.
   */
  fun estimateShift(
    ref: IntArray,
    cur: IntArray,
    w: Int,
    h: Int,
    maxShift: Int = 6,
    step: Int = 16,
  ): Pair<Int, Int> {
    if (w <= 0 || h <= 0 || ref.size < w * h || cur.size < w * h) return 0 to 0
    var bestDx = 0
    var bestDy = 0
    var best = Long.MAX_VALUE
    for (dy in -maxShift..maxShift) {
      for (dx in -maxShift..maxShift) {
        var sad = 0L
        var count = 0
        var y = maxShift
        while (y < h - maxShift) {
          val ry = y + dy
          if (ry < 0 || ry >= h) { y += step; continue }
          var x = maxShift
          while (x < w - maxShift) {
            val cx = x + dx
            if (cx in 0 until w) {
              val d = ref[y * w + x] - cur[ry * w + cx]
              sad += if (d < 0) -d else d
              count++
            }
            x += step
          }
          y += step
        }
        if (count > 0) {
          val score = sad / count
          if (score < best) {
            best = score
            bestDx = dx
            bestDy = dy
          }
        }
      }
    }
    return bestDx to bestDy
  }

  /**
   * Adds one decoded strip of a frame to the running sums of a merge.
   *
   * [px] holds [rows] rows of width [w]. Its first row lands on strip row [firstRow] and every pixel moves [moveX]
   * to the right (the frame's alignment). Pixels pushed off the strip are skipped, so [accW] ends up holding how
   * much each output pixel received. [night] averages evenly; otherwise mid-tones weigh most (exposure fusion).
   */
  fun accumulate(
    px: IntArray,
    w: Int,
    rows: Int,
    firstRow: Int,
    moveX: Int,
    night: Boolean,
    accR: DoubleArray,
    accG: DoubleArray,
    accB: DoubleArray,
    accW: DoubleArray,
  ) {
    val x0 = maxOf(0, moveX)
    val x1 = minOf(w, w + moveX)
    for (row in 0 until rows) {
      val out = (firstRow + row) * w
      val src = row * w - moveX
      for (x in x0 until x1) {
        val c = px[src + x]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val wt = if (night) 1.0 else {
          val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
          Math.exp(-((lum - 0.5) * (lum - 0.5)) / 0.08).coerceAtLeast(0.02)
        }
        val i = out + x
        accR[i] += r * wt
        accG[i] += g * wt
        accB[i] += b * wt
        accW[i] += wt
      }
    }
  }

  /** Luminance plane used by every matcher above. */
  fun toGray(pixels: IntArray): IntArray {
    val g = IntArray(pixels.size)
    for (i in pixels.indices) {
      val c = pixels[i]
      g[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    }
    return g
  }
}
