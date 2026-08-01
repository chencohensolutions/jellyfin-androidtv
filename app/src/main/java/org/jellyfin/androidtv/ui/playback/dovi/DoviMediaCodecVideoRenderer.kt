package org.jellyfin.androidtv.ui.playback.dovi

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.jellyfin.androidtv.dovi.DoviRpuConverter
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * A [MediaCodecVideoRenderer] that rewrites Dolby Vision profile 7 (dual-layer, FEL/MEL) streams
 * to profile 8 (single-layer) on-device, so a genuine Dolby Vision profile 8 decoder/output path
 * is used during Direct Play instead of requiring the server to transcode profile 7 sources.
 *
 * Two things happen for a detected profile 7 track:
 * 1. [getDecoderInfos] retargets the [Format]'s `codecs` string from profile 7 to profile 8
 *    before decoder selection, so [MediaCodecSelector] picks a real Dolby Vision profile 8
 *    decoder.
 * 2. [onQueueInputBuffer] rewrites each encoded access unit's Dolby Vision RPU (via
 *    [DoviRpuConverter], backed by a native Rust implementation of the `dolby_vision` crate) and
 *    strips profile-7-only enhancement-layer NAL units, right before the buffer is queued to
 *    `MediaCodec`.
 *
 * If conversion fails for any reason the original, unmodified buffer/format is used instead -
 * this never crashes playback, though the resulting picture may not be genuine Dolby Vision.
 */
@UnstableApi
class DoviMediaCodecVideoRenderer(
	context: Context,
	codecAdapterFactory: MediaCodecAdapter.Factory,
	mediaCodecSelector: MediaCodecSelector,
	allowedJoiningTimeMs: Long,
	enableDecoderFallback: Boolean,
	eventHandler: Handler,
	eventListener: VideoRendererEventListener,
	maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
	context,
	codecAdapterFactory,
	mediaCodecSelector,
	allowedJoiningTimeMs,
	enableDecoderFallback,
	eventHandler,
	eventListener,
	maxDroppedFramesToNotify,
) {
	// Set by getDecoderInfos() whenever the current format was retargeted from profile 7 to
	// profile 8; read by onQueueInputBuffer() to decide whether per-buffer RPU conversion is
	// needed. There is only ever one input format "in flight" for a MediaCodecRenderer at a time.
	private var convertingDolbyVisionProfile7 = false

	override fun getDecoderInfos(
		mediaCodecSelector: MediaCodecSelector,
		format: Format,
		requiresSecureDecoder: Boolean,
	): List<MediaCodecInfo> {
		val retargeted = retargetDolbyVisionProfile7ToProfile8(format)
		convertingDolbyVisionProfile7 = retargeted != null

		return super.getDecoderInfos(mediaCodecSelector, retargeted ?: format, requiresSecureDecoder)
	}

	override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
		if (convertingDolbyVisionProfile7) {
			val data = buffer.data
			if (data != null && data.hasRemaining()) {
				val original = ByteArray(data.remaining())
				val originalPosition = data.position()
				data.get(original)
				data.position(originalPosition)

				val converted = DoviRpuConverter.convertAccessUnit(original)
				if (converted != null) buffer.data = ByteBuffer.wrap(converted)
			}
		}

		super.onQueueInputBuffer(buffer)
	}

	private fun retargetDolbyVisionProfile7ToProfile8(format: Format): Format? {
		if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null

		val codecs = format.codecs ?: return null
		val match = PROFILE_7_CODECS_REGEX.matchEntire(codecs) ?: return null
		val retargetedCodecs = "${match.groupValues[1]}.08.${match.groupValues[2]}"

		Timber.i("Retargeting Dolby Vision profile 7 codecs \"%s\" to profile 8 \"%s\" for on-device conversion", codecs, retargetedCodecs)

		return format.buildUpon().setCodecs(retargetedCodecs).build()
	}

	private companion object {
		// Matches Dolby Vision profile 7 codecs strings, e.g. "dvhe.07.06" or "dvh1.07.09".
		val PROFILE_7_CODECS_REGEX = Regex("""(dvhe|dvh1)\.07\.(\d{2})""")
	}
}
