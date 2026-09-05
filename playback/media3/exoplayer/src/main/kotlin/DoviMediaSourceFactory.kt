package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

@UnstableApi
class DoviMediaSourceFactory(
    private val delegate: MediaSourceFactory,
    private val hlsFactory: MediaSourceFactory,
    private val dataSourceFactory: DataSource.Factory,
    private val subtitleParserFactory: SubtitleParser.Factory,
) : MediaSourceFactory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val configuration = mediaItem.localConfiguration
        val isHls = configuration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            configuration?.uri?.path?.endsWith(".m3u8", ignoreCase = true) == true
        if (!isHls) return delegate.createMediaSource(mediaItem)

        val hlsMediaSource = hlsFactory.createMediaSource(mediaItem)
        val subtitles = configuration?.subtitleConfigurations.orEmpty()
        if (subtitles.isEmpty()) return hlsMediaSource

        val mediaSources = ArrayList<MediaSource>(subtitles.size + 1)
        mediaSources += hlsMediaSource
        subtitles.forEach { subtitle ->
            val format = Format.Builder()
                .setSampleMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language)
                .setSelectionFlags(subtitle.selectionFlags)
                .setRoleFlags(subtitle.roleFlags)
                .setLabel(subtitle.label)
                .setId(subtitle.id)
                .build()
            val extractorsFactory = {
                arrayOf<Extractor>(SubtitleExtractor(subtitleParserFactory.create(format), format))
            }
            mediaSources += ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        return MergingMediaSource(*mediaSources.toTypedArray())
    }
}
