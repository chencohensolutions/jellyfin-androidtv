package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory

@UnstableApi
internal class DoviMediaSourceFactory(
    private val delegate: MediaSourceFactory,
    private val hlsFactory: MediaSourceFactory,
) : MediaSourceFactory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val configuration = mediaItem.localConfiguration
        val isHls = configuration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            configuration?.uri?.path?.endsWith(".m3u8", ignoreCase = true) == true
        return if (isHls) hlsFactory.createMediaSource(mediaItem) else delegate.createMediaSource(mediaItem)
    }
}
