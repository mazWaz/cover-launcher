package com.mazwas.covercam

/**
 * Long hand-set shutter. A preview frame cannot last as long as a 30 s exposure (the picture would freeze), so the
 * preview keeps its brightness with gain instead and only the still is exposed for the full time.
 */
internal object LongExposure {
  /** The longest exposure a preview frame gets: 10 frames a second. */
  const val PREVIEW_MAX_NS = 100_000_000L

  /** The ISO and exposure time a preview frame uses for a hand-set [iso] and [exposureNs]. */
  fun preview(iso: Int, exposureNs: Long, isoMax: Int): Pair<Int, Long> {
    if (exposureNs <= PREVIEW_MAX_NS) return iso to exposureNs
    val gain = exposureNs.toDouble() / PREVIEW_MAX_NS
    return minOf(isoMax.toDouble(), iso * gain).toInt() to PREVIEW_MAX_NS
  }

  /** Longer than a second: the shutter button cancels and the screen counts the seconds. */
  fun isLong(exposureNs: Long): Boolean = exposureNs > 1_000_000_000L
}
