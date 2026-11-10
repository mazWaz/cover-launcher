package com.mazwas.covercam

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal DNG (TIFF/EP) writer for Camera2 RAW_SENSOR images.
 *
 * IFD0  : CFA raw strip (16-bit) with real white balance / levels from the capture result
 * IFD1  : optional embedded JPEG preview so normal galleries show correct colours
 *         instead of the green Bayer mosaic
 */
object DngWriter {

  private const val TYPE_BYTE = 1
  private const val TYPE_ASCII = 2
  private const val TYPE_SHORT = 3
  private const val TYPE_LONG = 4
  private const val TYPE_RATIONAL = 5
  private const val TYPE_SRATIONAL = 10

  private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

  /** Per-device colour calibration read from CameraCharacteristics. */
  class RawColor(
    val color1: DoubleArray? = null,
    val color2: DoubleArray? = null,
    val calib1: DoubleArray? = null,
    val calib2: DoubleArray? = null,
    val fwd1: DoubleArray? = null,
    val fwd2: DoubleArray? = null,
    val illum1: Int = 21,
    val illum2: Int = 17,
  )

  fun write(
    ctx: Context,
    image: Image,
    make: String,
    model: String,
    blackLevel: Int,
    whiteLevel: Int,
    cfa: Int,
    orientation: Int,
    asShotNeutral: DoubleArray,
    previewJpeg: ByteArray?,
    color: RawColor = RawColor(),
  ): Uri? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height

    val pixels = ByteArray(width * height * 2)
    val row = ByteArray(rowStride)
    var out = 0
    for (y in 0 until height) {
      buffer.position(y * rowStride)
      val n = minOf(rowStride, buffer.remaining())
      buffer.get(row, 0, n)
      val copy = minOf(width * 2, n)
      System.arraycopy(row, 0, pixels, out, copy)
      out += copy
      if (copy < width * 2) {
        java.util.Arrays.fill(pixels, out, out + (width * 2 - copy), 0)
        out += width * 2 - copy
      }
    }

    val ifd0 = ArrayList<Entry>()
    val ifd1 = ArrayList<Entry>()

    fun ascii(list: MutableList<Entry>, tag: Int, text: String) {
      val bytes = text.toByteArray(Charsets.US_ASCII)
      val buf = ByteArray(bytes.size + 1)
      System.arraycopy(bytes, 0, buf, 0, bytes.size)
      list.add(Entry(tag, TYPE_ASCII, buf.size, buf))
    }

    fun shorts(list: MutableList<Entry>, tag: Int, values: IntArray) {
      val b = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { b.putShort(it.toShort()) }
      list.add(Entry(tag, TYPE_SHORT, values.size, b.array()))
    }

    fun bytes(list: MutableList<Entry>, tag: Int, values: IntArray) {
      list.add(Entry(tag, TYPE_BYTE, values.size, ByteArray(values.size) { values[it].toByte() }))
    }

    fun rational(list: MutableList<Entry>, tag: Int, values: DoubleArray, signed: Boolean) {
      val b = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach {
        b.putInt(Math.round(it * 100000.0).toInt())
        b.putInt(100000)
      }
      list.add(Entry(tag, if (signed) TYPE_SRATIONAL else TYPE_RATIONAL, values.size, b.array()))
    }

    fun singleLong(list: MutableList<Entry>, tag: Int, value: Int) {
      list.add(Entry(tag, TYPE_LONG, 1, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()))
    }

    fun singleShort(list: MutableList<Entry>, tag: Int, value: Int) {
      list.add(Entry(tag, TYPE_SHORT, 1, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()))
    }

    val pattern = when (cfa) {
      1 -> intArrayOf(0, 1, 1, 2)
      2 -> intArrayOf(1, 0, 2, 1)
      3 -> intArrayOf(1, 2, 0, 1)
      4 -> intArrayOf(2, 1, 1, 0)
      else -> intArrayOf(0, 1, 1, 2)
    }

    // ---- IFD0: the raw image ----
    singleLong(ifd0, 254, 0)
    singleLong(ifd0, 256, width)
    singleLong(ifd0, 257, height)
    singleShort(ifd0, 258, 16)
    singleShort(ifd0, 259, 1)
    singleShort(ifd0, 262, 32803)
    ascii(ifd0, 271, make)
    ascii(ifd0, 272, model)
    singleLong(ifd0, 273, 0)
    singleShort(ifd0, 274, orientation) // EXIF/TIFF orientation code, mirrored ones included
    singleShort(ifd0, 277, 1)
    singleLong(ifd0, 278, height)
    singleLong(ifd0, 279, pixels.size)
    singleShort(ifd0, 284, 1)
    shorts(ifd0, 33421, intArrayOf(2, 2))
    bytes(ifd0, 33422, pattern)
    bytes(ifd0, 50706, intArrayOf(1, 4, 0, 0))
    bytes(ifd0, 50707, intArrayOf(1, 1, 0, 0))
    ascii(ifd0, 50708, "$make $model")
    bytes(ifd0, 50710, intArrayOf(0, 1, 2))
    singleShort(ifd0, 50711, 1)
    singleLong(ifd0, 50714, blackLevel)
    singleLong(ifd0, 50717, whiteLevel)
    // Colour calibration straight from the camera HAL when it is available.
    rational(
      ifd0, 50721,
      color.color1 ?: doubleArrayOf(
        0.6722, -0.0635, -0.0963,
        -0.4287, 1.2460, 0.2028,
        -0.0908, 0.2162, 0.5668,
      ),
      true,
    )
    singleShort(ifd0, 50778, color.illum1)
    color.color2?.let { rational(ifd0, 50722, it, true); singleShort(ifd0, 50779, color.illum2) }
    color.calib1?.let { rational(ifd0, 50723, it, true) }
    color.calib2?.let { rational(ifd0, 50724, it, true) }
    color.fwd1?.let { rational(ifd0, 50964, it, true) }
    color.fwd2?.let { rational(ifd0, 50965, it, true) }
    rational(ifd0, 50728, asShotNeutral, false)

    // ---- IFD1: embedded JPEG preview ----
    if (previewJpeg != null) {
      singleLong(ifd1, 254, 1)
      singleLong(ifd1, 256, PREVIEW_WIDTH)
      singleLong(ifd1, 257, PREVIEW_HEIGHT)
      singleShort(ifd1, 258, 8)
      singleShort(ifd1, 259, 7)      // JPEG compression
      singleShort(ifd1, 262, 6)      // YCbCr
      singleLong(ifd1, 273, 0)
      singleShort(ifd1, 277, 3)
      singleLong(ifd1, 278, PREVIEW_HEIGHT)
      singleLong(ifd1, 279, previewJpeg.size)
      singleShort(ifd1, 284, 1)
      singleShort(ifd1, 530, 2)      // YCbCrPositioning
      rational(ifd1, 282, doubleArrayOf(72.0), false)
      rational(ifd1, 283, doubleArrayOf(72.0), false)
      singleShort(ifd1, 296, 2)
    }

    ifd0.sortBy { it.tag }
    ifd1.sortBy { it.tag }

    val ifd0Offset = 8
    val ifd0Size = 2 + ifd0.size * 12 + 4
    val ifd1Offset = ifd0Offset + ifd0Size
    val ifd1Size = if (previewJpeg != null) 2 + ifd1.size * 12 + 4 else 0

    // auxiliary blocks for IFD0, then IFD1, then strip data, then preview
    var auxOffset = ifd1Offset + ifd1Size
    val aux0 = ByteArrayOutputStream()
    val off0 = IntArray(ifd0.size) { -1 }
    ifd0.forEachIndexed { i, e ->
      if (e.data.size > 4) {
        off0[i] = auxOffset
        aux0.write(e.data)
        auxOffset += e.data.size
        if (e.data.size % 2 == 1) { aux0.write(0); auxOffset++ }
      }
    }
    val aux1 = ByteArrayOutputStream()
    val off1 = IntArray(ifd1.size) { -1 }
    ifd1.forEachIndexed { i, e ->
      if (e.data.size > 4) {
        off1[i] = auxOffset
        aux1.write(e.data)
        auxOffset += e.data.size
        if (e.data.size % 2 == 1) { aux1.write(0); auxOffset++ }
      }
    }
    val stripOffset = auxOffset
    val previewOffset = stripOffset + pixels.size

    // Only the header is assembled in memory; the 24 MB of pixels go straight to the file after it.
    val buf = ByteBuffer.allocate(stripOffset).order(ByteOrder.LITTLE_ENDIAN)

    buf.put('I'.code.toByte()); buf.put('I'.code.toByte())
    buf.putShort(42.toShort())
    buf.putInt(ifd0Offset)

    fun writeIfd(offset: Int, entries: List<Entry>, auxOffsets: IntArray, nextIfd: Int) {
      buf.position(offset)
      buf.putShort(entries.size.toShort())
      entries.forEachIndexed { i, e ->
        buf.putShort(e.tag.toShort())
        buf.putShort(e.type.toShort())
        buf.putInt(e.count)
        if (auxOffsets[i] < 0) {
          val padded = ByteArray(4)
          System.arraycopy(e.data, 0, padded, 0, e.data.size)
          buf.put(padded)
        } else {
          buf.putInt(auxOffsets[i])
        }
      }
      buf.putInt(nextIfd)
    }

    writeIfd(ifd0Offset, ifd0, off0, if (previewJpeg != null) ifd1Offset else 0)
    if (previewJpeg != null) writeIfd(ifd1Offset, ifd1, off1, 0)

    buf.position(ifd0Offset + ifd0Size + ifd1Size)
    buf.put(aux0.toByteArray())
    buf.put(aux1.toByteArray())

    // patch strip / preview offsets now that the numbers are known
    val stripIdx = ifd0.indexOfFirst { it.tag == 273 }
    buf.putInt(ifd0Offset + 2 + stripIdx * 12 + 8, stripOffset)
    if (previewJpeg != null) {
      val prevIdx = ifd1.indexOfFirst { it.tag == 273 }
      buf.putInt(ifd1Offset + 2 + prevIdx * 12 + 8, previewOffset)
    }

    val name = "COVERCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".dng"
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
      put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CoverCam")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { os ->
      os.write(buf.array())
      os.write(pixels)
      previewJpeg?.let(os::write)
    }
    return uri
  }

  private const val PREVIEW_WIDTH = 480
  private const val PREVIEW_HEIGHT = 360
}
