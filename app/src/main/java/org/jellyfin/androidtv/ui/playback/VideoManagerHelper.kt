package org.jellyfin.androidtv.ui.playback

import android.graphics.ColorMatrix
import android.graphics.Color
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import androidx.core.net.toUri
import org.jellyfin.androidtv.util.AndroidVersion
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType
import org.jellyfin.sdk.model.api.MediaStream
import timber.log.Timber

/**
 * Return the media type for the codec found in this media stream. First tries to infer the media type from the streams delivery URL and
 * falls back to the original stream codec.
 */
fun getSubtitleMediaStreamCodec(stream: MediaStream): String {
	val codec = requireNotNull(stream.codec)
	val codecMediaType = getFfmpegSubtitleMimeType(codec, "").ifBlank { null }

	val urlSubtitleExtension = stream.deliveryUrl?.toUri()?.lastPathSegment?.split('.')?.last()
	val urlExtensionMediaType = urlSubtitleExtension?.let { getFfmpegSubtitleMimeType(it, "") }?.ifBlank { null }

	return urlExtensionMediaType ?: codecMediaType ?: urlSubtitleExtension ?: codec
}

fun Format.isHdrVideo(): Boolean =
	colorInfo?.let { ColorInfo.isTransferHdr(it) } == true ||
		sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

fun calculateHdrGuiBrightnessFactor(isHdr: Boolean, brightnessPercent: Int): Float =
	if (isHdr) brightnessPercent.coerceIn(10, 100) / 100f else 1f

fun createHdrGuiColorMatrix(brightnessFactor: Float): ColorMatrix {
	val factor = brightnessFactor.coerceIn(0.1f, 1f)
	return ColorMatrix(floatArrayOf(
		factor, 0f, 0f, 0f, 0f,
		0f, factor, 0f, 0f, 0f,
		0f, 0f, factor, 0f, 0f,
		0f, 0f, 0f, 1f, 0f,
	))
}

fun transformHdrGuiColor(color: Int, brightnessFactor: Float): Int {
	val factor = brightnessFactor.coerceIn(0.1f, 1f)
	return Color.argb(
		Color.alpha(color),
		(Color.red(color) * factor).toInt(),
		(Color.green(color) * factor).toInt(),
		(Color.blue(color) * factor).toInt(),
	)
}

private var audioEffect: AudioEffect? = null

fun applyAudioNightmode(audioSessionId: Int) {
	Timber.i("Enabling audio night mode for session $audioSessionId")

	audioEffect?.release()

	audioEffect = when {
		// Use dynamics processinc on Android 9 (API 28) and newer
		AndroidVersion.isAtLeastP -> DynamicsProcessing(0, audioSessionId, null).apply {
			setLimiterAllChannelsTo(
				DynamicsProcessing.Limiter(
					true,
					true,
					1,
					30f,
					300f,
					10f,
					-24f,
					3f
				)
			)

			setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, true, 5).apply {
				getBand(0).gain = 0f
				getBand(1).gain = 0.02f
				getBand(2).gain = 0.03f
				getBand(3).gain = 0.02f
				getBand(4).gain = 0f
			})

			enabled = true
		}

		// Use more simple equalizer implementation on older versions
		else -> Equalizer(0, audioSessionId).apply {
			setBandLevel(0, 0)
			setBandLevel(1, 2)
			setBandLevel(2, 3)
			setBandLevel(3, 2)
			setBandLevel(4, 0)
			enabled = true
		}
	}
}
