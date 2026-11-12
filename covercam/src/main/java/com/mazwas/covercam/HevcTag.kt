package com.mazwas.covercam

/**
 * The transfer tag inside an HEVC stream's parameter sets. The encoder writes HLG there whatever it is asked for
 * (it follows the camera stream), and a log clip tagged HLG is tone-mapped as HDR by every player and editor.
 */
object HevcTag {
  private const val BT2020 = 9
  private const val HLG = 18
  private const val BT709 = 1

  /**
   * [csd] (the encoder's `csd-0`) with the transfer changed from HLG to BT.709; null when the colour description
   * isn't there exactly once, so a stream this doesn't understand is left as it is.
   *
   * shortcut: finds the description by its bits (primaries BT.2020, transfer HLG, matrix BT.2020, side by side at no
   * particular bit) instead of parsing the SPS; write the parser when another encoder or another tag needs this.
   */
  fun hlgToSdr(csd: ByteArray): ByteArray? {
    fun byteAt(bit: Int): Int {
      var v = 0
      for (i in bit until bit + 8) v = (v shl 1) or ((csd[i / 8].toInt() shr (7 - i % 8)) and 1)
      return v
    }
    val at = (0..csd.size * 8 - 24)
      .filter { byteAt(it) == BT2020 && byteAt(it + 8) == HLG && byteAt(it + 16) == BT2020 }
      .singleOrNull() ?: return null
    val out = csd.copyOf()
    for (i in 0 until 8) {
      val bit = at + 8 + i
      val mask = 1 shl (7 - bit % 8)
      val one = (BT709 shr (7 - i)) and 1 == 1
      out[bit / 8] = (if (one) out[bit / 8].toInt() or mask else out[bit / 8].toInt() and mask.inv()).toByte()
    }
    return out
  }
}
