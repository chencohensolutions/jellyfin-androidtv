package org.jellyfin.androidtv.ui.playback.dovi

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * A [DefaultRenderersFactory] that substitutes the stock [MediaCodecVideoRenderer] with
 * [DoviMediaCodecVideoRenderer], which rewrites Dolby Vision profile 7 (dual-layer) streams to
 * profile 8 (single-layer) on-device during Direct Play. Every other renderer (audio, text,
 * metadata, any extension/software video renderers) is left untouched.
 *
 * Only intended to be used when the user has opted into the experimental
 * `UserPreferences.convertDolbyVisionProfile7to8` setting - see `VideoManager.java`.
 */
@UnstableApi
class DoviRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: ArrayList<Renderer>,
	) {
		super.buildVideoRenderers(
			context,
			extensionRendererMode,
			mediaCodecSelector,
			enableDecoderFallback,
			eventHandler,
			eventListener,
			allowedVideoJoiningTimeMs,
			out
		)

		// Replace the stock renderer only - any extension/software video renderers that the
		// default factory may have added (e.g. for the FFmpeg video extension) use different
		// concrete classes and are left as-is.
		val stockRendererIndex = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
		if (stockRendererIndex < 0) return

		out[stockRendererIndex] = DoviMediaCodecVideoRenderer(
			context,
			MediaCodecAdapter.Factory.getDefault(context),
			mediaCodecSelector,
			allowedVideoJoiningTimeMs,
			enableDecoderFallback,
			eventHandler,
			eventListener,
			MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
		)
	}
}
