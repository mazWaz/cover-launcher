package com.mazwas.covercam

/**
 * Where the upright picture sits inside the preview view, and where a tap on it lands on the sensor.
 * Free of Android types so it can be unit tested.
 */
internal object PreviewGeometry {

  /**
   * Size of the upright picture, fitted inside a [w] x [h] view without stretching.
   *
   * [sensorAspect] is the buffer's long/short ratio (4:3 = 1.33). [jpegOrientation] is the clockwise turn that
   * stands the sensor image upright, so 90/270 means the picture is portrait.
   */
  fun pictureSize(w: Float, h: Float, sensorAspect: Float, jpegOrientation: Int): Pair<Float, Float> {
    val aspect = pictureAspect(sensorAspect, jpegOrientation)
    return if (aspect > w / h) w to w / aspect else h * aspect to h
  }

  /** Width / height of the upright picture: the sensor's own shape, or that shape on its side. */
  fun pictureAspect(sensorAspect: Float, jpegOrientation: Int): Float =
    if (jpegOrientation % 180 == 0) sensorAspect else 1f / sensorAspect

  /**
   * TextureView stretches its buffer over the whole view. This is the scale to apply BEFORE the content is turned
   * by [rotation], so that after the turn it covers exactly [pw] x [ph].
   */
  fun contentScale(w: Float, h: Float, pw: Float, ph: Float, rotation: Int): Pair<Float, Float> =
    if (rotation % 180 == 0) pw / w to ph / h else ph / w to pw / h

  /**
   * A tap in view pixels as normalised sensor coordinates (0..1 across the active array), or null when it misses the
   * picture. Inverse of "turn the sensor image clockwise by [jpegOrientation] to stand it upright", after undoing
   * the left-right flip of a [mirrored] (selfie-style) preview.
   */
  fun tapToSensor(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    pw: Float,
    ph: Float,
    jpegOrientation: Int,
    mirrored: Boolean = false,
  ): Pair<Float, Float>? {
    if (pw <= 0f || ph <= 0f) return null
    val across = (x - (w - pw) / 2f) / pw
    val u = if (mirrored) 1f - across else across
    val v = (y - (h - ph) / 2f) / ph
    if (u !in 0f..1f || v !in 0f..1f) return null
    return when (((jpegOrientation % 360) + 360) % 360) {
      90 -> v to 1f - u
      180 -> 1f - u to 1f - v
      270 -> 1f - v to u
      else -> u to v
    }
  }

  /**
   * EXIF orientation that shows a stored sensor image the way the preview shows it: turned clockwise by [degrees],
   * then flipped left-right when [flip]. (2/4/5/7 are EXIF's mirrored codes; 5 and 7 are the two diagonal flips.)
   */
  fun exifOrientation(degrees: Int, flip: Boolean): Int = when (((degrees % 360) + 360) % 360) {
    90 -> if (flip) 5 else 6
    180 -> if (flip) 4 else 3
    270 -> if (flip) 7 else 8
    else -> if (flip) 2 else 1
  }

  /** Degrees away from the nearest straight pose (0/90/180/270): the horizon line is level near zero. */
  fun levelError(rollDegrees: Float): Float {
    val r = ((rollDegrees % 90f) + 90f) % 90f
    return minOf(r, 90f - r)
  }
}
