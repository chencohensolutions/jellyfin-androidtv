package org.jellyfin.playback.media3.exoplayer

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

internal object DoviCompatibility {
	fun mode(): DoviCompatMode {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return DoviCompatMode.OFF

		val codecInfos = runCatching {
			MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
		}.getOrElse {
			return DoviCompatMode.OFF
		}

		if (supportsProfile(codecInfos, CodecProfileLevel.DolbyVisionProfileDvheDtb)) {
			return DoviCompatMode.NATIVE
		}
		return if (supportsProfile(codecInfos, CodecProfileLevel.DolbyVisionProfileDvheSt)) {
			DoviCompatMode.CONVERT
		} else {
			DoviCompatMode.OFF
		}
	}

	private fun supportsProfile(codecInfos: Array<MediaCodecInfo>, profile: Int): Boolean =
		codecInfos.any { codecInfo ->
			!codecInfo.isEncoder &&
				codecInfo.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) } &&
				runCatching {
					codecInfo.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels.any {
						it.profile == profile
					}
				}.getOrDefault(false)
		}
}
