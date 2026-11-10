package com.mazwas.covercam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover is 748 x 720 px and the preview buffer 4:3; these are the numbers the screen really gets. */
class PreviewGeometryTest {

  private val w = 748f
  private val h = 720f
  private val aspect = 4f / 3f

  @Test
  fun `a portrait picture is fitted by height and keeps its 3 to 4 shape`() {
    val (pw, ph) = PreviewGeometry.pictureSize(w, h, aspect, jpegOrientation = 270)
    assertEquals(540f, pw, 0.01f)
    assertEquals(720f, ph, 0.01f)
  }

  @Test
  fun `a landscape picture is fitted by width and keeps its 4 to 3 shape`() {
    val (pw, ph) = PreviewGeometry.pictureSize(w, h, aspect, jpegOrientation = 0)
    assertEquals(748f, pw, 0.01f)
    assertEquals(561f, ph, 0.01f)
  }

  @Test
  fun `the scale undoes the stretch, before a quarter turn as well as without one`() {
    // no turn: the view-sized content simply shrinks to the picture
    val (sx, sy) = PreviewGeometry.contentScale(w, h, 540f, 720f, rotation = 0)
    assertEquals(540f, w * sx, 0.01f)
    assertEquals(720f, h * sy, 0.01f)
    // quarter turn: the content's width becomes the picture's height
    val (tx, ty) = PreviewGeometry.contentScale(w, h, 748f, 561f, rotation = 90)
    assertEquals(561f, w * tx, 0.01f)
    assertEquals(748f, h * ty, 0.01f)
  }

  @Test
  fun `a tap goes back to the sensor through the turn that stands the picture upright`() {
    // upright picture 540 x 720, centred: its top-left corner sits at x = 104
    val topLeft = { o: Int -> PreviewGeometry.tapToSensor(104f, 0f, w, h, 540f, 720f, o)!! }
    // turning the sensor image 90 clockwise puts its bottom-left corner at the picture's top-left
    assertPoint(0f, 1f, topLeft(90))
    assertPoint(1f, 1f, topLeft(180))
    // 270 clockwise puts the sensor's top-right corner there
    assertPoint(1f, 0f, topLeft(270))
    assertPoint(0f, 0f, PreviewGeometry.tapToSensor(0f, 79.5f, w, h, 748f, 561f, 0)!!)
    // the centre stays the centre whatever the turn
    for (o in listOf(0, 90, 180, 270)) assertPoint(0.5f, 0.5f, PreviewGeometry.tapToSensor(374f, 360f, w, h, 540f, 720f, o)!!)
  }

  @Test
  fun `on a mirrored preview a tap on the left is the right of the photo`() {
    // upright (0 degrees) landscape picture filling the width: left edge of the screen = right edge of the sensor
    assertPoint(1f, 0.5f, PreviewGeometry.tapToSensor(0f, 360f, w, h, 748f, 561f, 0, mirrored = true)!!)
    assertPoint(0f, 0.5f, PreviewGeometry.tapToSensor(0f, 360f, w, h, 748f, 561f, 0, mirrored = false)!!)
  }

  /** Applies an EXIF orientation to a tiny labelled image the way a viewer does; pixels are listed row by row. */
  private fun show(code: Int, px: List<String>, w: Int): List<String> {
    val h = px.size / w
    fun at(x: Int, y: Int) = px[y * w + x]
    return when (code) {
      1 -> px
      2 -> List(w * h) { at(w - 1 - it % w, it / w) }
      3 -> List(w * h) { at(w - 1 - it % w, h - 1 - it / w) }
      4 -> List(w * h) { at(it % w, h - 1 - it / w) }
      // the turned ones are h wide and w tall
      5 -> List(w * h) { at(it / h, it % h) }
      6 -> List(w * h) { at(it / h, h - 1 - it % h) }
      7 -> List(w * h) { at(w - 1 - it / h, h - 1 - it % h) }
      else -> List(w * h) { at(w - 1 - it / h, it % h) }
    }
  }

  @Test
  fun `the mirrored exif codes are the plain turn followed by a left-right flip`() {
    // a 3 x 2 sensor image: a b c / d e f
    val px = listOf("a", "b", "c", "d", "e", "f")
    for (degrees in listOf(0, 90, 180, 270)) {
      val turned = show(PreviewGeometry.exifOrientation(degrees, flip = false), px, 3)
      val width = if (degrees % 180 == 0) 3 else 2
      val flipped = List(turned.size) { turned[(it / width) * width + (width - 1 - it % width)] }
      assertEquals("turn $degrees", flipped, show(PreviewGeometry.exifOrientation(degrees, flip = true), px, 3))
    }
    // and the plain quarter turn really is clockwise: the bottom-left pixel goes to the top-left
    assertEquals(listOf("d", "a", "e", "b", "f", "c"), show(PreviewGeometry.exifOrientation(90, flip = false), px, 3))
  }

  @Test
  fun `a tap on the black bar beside the picture is ignored`() {
    assertNull(PreviewGeometry.tapToSensor(50f, 360f, w, h, 540f, 720f, 270))
    assertNull(PreviewGeometry.tapToSensor(374f, 360f, w, h, 0f, 0f, 270))
  }

  @Test
  fun `level means straight in any of the four ways the phone is held`() {
    for (pose in listOf(0f, 90f, 180f, -180f, -90f, 270f)) {
      assertEquals(0f, PreviewGeometry.levelError(pose), 0.001f)
      assertEquals(1f, PreviewGeometry.levelError(pose + 1f), 0.001f)
      assertEquals(1f, PreviewGeometry.levelError(pose - 1f), 0.001f)
    }
    assertEquals(45f, PreviewGeometry.levelError(45f), 0.001f)
  }

  private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
    assertEquals(x, actual.first, 0.001f)
    assertEquals(y, actual.second, 0.001f)
  }
}
