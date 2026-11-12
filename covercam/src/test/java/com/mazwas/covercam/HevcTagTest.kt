package com.mazwas.covercam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevcTagTest {

  private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

  /** The parameter sets of a log clip recorded on the Flip5 (2026-11-12), as the encoder hands them out. */
  private val phone = bytes(
    "0000000140010c01ffff222000000300b00000030000030096ac09" +
      "00000001420101222000000300b00000030000030096a003c0801107cad96bb9324ba9a848904817685094" +
      "000000014401c0e30c32190941ef6108",
  )

  @Test
  fun `the clip's HLG tag becomes BT709 exactly as ffmpeg's hevc_metadata rewrites it`() {
    // the same clip through `ffmpeg -c copy -bsf:v hevc_metadata=transfer_characteristics=1`
    val ffmpeg = bytes(
      "0000000140010c01ffff222000000300b00000030000030096ac09" +
        "00000001420101222000000300b00000030000030096a003c0801107cad96bb9324ba9a848084817685094" +
        "000000014401c0e30c32190941ef6108",
    )
    assertArrayEquals(ffmpeg, HevcTag.hlgToSdr(phone))
  }

  @Test
  fun `a stream without the HLG description, or with two, is left alone`() {
    assertNull(HevcTag.hlgToSdr(bytes("0000000142010122200000030096a003c08011")))
    assertNull(HevcTag.hlgToSdr(HevcTag.hlgToSdr(phone)!!))
    assertNull(HevcTag.hlgToSdr(phone + phone))
  }
}
