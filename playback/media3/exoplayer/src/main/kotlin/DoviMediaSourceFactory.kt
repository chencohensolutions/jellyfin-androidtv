package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.ByteArrayOutputStream

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
            val parserFactory = if (subtitle.mimeType == MimeTypes.APPLICATION_PGS) {
                object : SubtitleParser.Factory {
                    private val delegate = DefaultSubtitleParserFactory()

                    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

                    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

                    override fun create(format: Format): SubtitleParser {
                        return SupPgsParser(delegate.create(format))
                    }
                }
            } else {
                subtitleParserFactory
            }
            val extractorsFactory = {
                arrayOf<Extractor>(SubtitleExtractor(parserFactory.create(format), format))
            }
            mediaSources += ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        return MergingMediaSource(*mediaSources.toTypedArray())
    }
}

private class SupPgsParser(
    private val delegate: SubtitleParser,
) : SubtitleParser by delegate {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: androidx.media3.common.util.Consumer<CuesWithTiming>,
    ) {
        val displaySets = mutableListOf<Pair<Long, ByteArray>>()
        var position = offset
        val end = offset + length
        var sections = ByteArrayOutputStream()
        var presentationTimestamp = 0L

        while (position + 13 <= end) {
            if (data[position] != 'P'.code.toByte() || data[position + 1] != 'G'.code.toByte()) {
                position++
                continue
            }

            val pts = readUnsignedInt(data, position + 2)
            val sectionType = data[position + 10].toInt() and 0xFF
            val sectionLength = readUnsignedShort(data, position + 11)
            val sectionEnd = position + 13 + sectionLength
            if (sectionEnd > end) break

            sections.write(data, position + 10, sectionLength + 3)
            presentationTimestamp = pts
            position = sectionEnd

            if (sectionType == 0x80) {
                displaySets += presentationTimestamp * 1_000_000L / 90_000L to sections.toByteArray()
                sections = ByteArrayOutputStream()
            }
        }

        displaySets.forEachIndexed { index, (startTimeUs, displaySet) ->
            val nextStartTimeUs = displaySets.getOrNull(index + 1)?.first ?: C.TIME_UNSET
            delegate.parse(displaySet, 0, displaySet.size, SubtitleParser.OutputOptions.allCues()) { cues ->
                if (cues.cues.isNotEmpty()) {
                    val durationUs = if (nextStartTimeUs == C.TIME_UNSET) C.TIME_UNSET else nextStartTimeUs - startTimeUs
                    output.accept(CuesWithTiming(cues.cues, startTimeUs, durationUs))
                }
            }
        }
    }

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUnsignedInt(data: ByteArray, offset: Int): Long =
        (readUnsignedShort(data, offset).toLong() shl 16) or readUnsignedShort(data, offset + 2).toLong()
}
