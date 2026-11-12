package com.mazwas.covercam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface

/**
 * HEVC Main10 recorder of the 10-bit video mode: HLG at 30 fps, or log at 60 (the camera's tone curve decides
 * which). The encoder tags the stream HLG either way; with [sdrTag] the tag is rewritten, see [HevcTag].
 *
 * Deliberately isolated from the MediaRecorder path: if anything here fails, normal
 * 1080p recording keeps working untouched. Video-only (no audio) for now.
 */
class HdrVideoRecorder(
  private val width: Int,
  private val height: Int,
  private val fps: Int,
  private val muxer: MediaMuxer,
  private val sdrTag: Boolean = false,
) {
  private var encoder: MediaCodec? = null
  private var inputSurface: Surface? = null
  private var trackIndex = -1
  private var muxerStarted = false
  private val info = MediaCodec.BufferInfo()

  /** Configures the encoder and returns the surface the camera should render into. */
  fun prepare(): Surface {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, if (fps > 30) 60_000_000 else 40_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    runCatching { format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) }
      .onFailure { Log.w(TAG, "Main10 profile rejected", it) }
    runCatching { format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020) }
    runCatching { format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG) }
    runCatching { format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL) }

    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = codec.createInputSurface()
    codec.start()
    encoder = codec
    inputSurface = surface
    Log.i(TAG, "HDR encoder ready ${width}x${height}@$fps HEVC Main10")
    return surface
  }

  /**
   * Moves whatever the encoder already produced into the muxer. Called from the main thread every 16 ms, so while
   * recording it never waits for the encoder; only the final drain does, and that one gives up after two seconds
   * instead of hanging the app on an encoder that never signals its end.
   */
  fun drain(endOfStream: Boolean = false) {
    val codec = encoder ?: return
    val deadline = System.nanoTime() + 2_000_000_000L
    while (true) {
      val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || System.nanoTime() > deadline) return
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if (muxerStarted) continue
          trackIndex = muxer.addTrack(codec.outputFormat.also { if (sdrTag) tagSdr(it) })
          muxer.start()
          muxerStarted = true
          Log.i(TAG, "HDR muxer started track=$trackIndex")
        }
        index >= 0 -> {
          val buffer = codec.getOutputBuffer(index)
          if (buffer != null && info.size > 0 && muxerStarted) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(trackIndex, buffer, info)
          }
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  /** A log clip is not HLG: BT.709 transfer in the stream's own parameter sets and in the container's colour box. */
  private fun tagSdr(format: MediaFormat) {
    val csd = format.getByteBuffer("csd-0") ?: return
    val patched = HevcTag.hlgToSdr(ByteArray(csd.remaining()).also { csd.duplicate().get(it) })
    if (patched == null) {
      Log.w(TAG, "no HLG tag found in csd-0, the clip stays tagged HLG")
      return
    }
    // both or neither: two tags that disagree are worse than one wrong tag
    format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(patched))
    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
  }

  fun stop() {
    try {
      encoder?.signalEndOfInputStream()
      drain(endOfStream = true)
    } catch (t: Throwable) {
      Log.w(TAG, "HDR encoder drain failed", t)
    }
    runCatching { encoder?.stop() }.onFailure { Log.w(TAG, "encoder stop failed", it) }
    runCatching { encoder?.release() }.onFailure { Log.w(TAG, "encoder release failed", it) }
    runCatching { inputSurface?.release() }.onFailure { Log.w(TAG, "surface release failed", it) }
    encoder = null
    inputSurface = null
    runCatching {
      if (muxerStarted) muxer.stop()
    }.onFailure { Log.w(TAG, "muxer stop failed", it) }
    runCatching { muxer.release() }.onFailure { Log.w(TAG, "muxer release failed", it) }
    muxerStarted = false
  }

  companion object {
    private const val TAG = "CoverCamHdr"
  }
}
