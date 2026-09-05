# Dolby Vision and HLS Playback Specification

## Purpose

This document describes the Android TV playback implementation for:

- Dolby Vision profile 7 compatibility on devices that only advertise Dolby Vision profile 8 support.
- HLS direct streaming with the server-selected audio track and stream-copy video/audio.
- Text subtitle delivery and selection while using that HLS path.

The implementation is based on the Media3 player used by the legacy Android TV playback screen. It applies to both direct progressive playback and HLS fMP4 playback.

## Dolby Vision Profile 7 Compatibility

### Capability detection

At startup, `DoviCompatibility` checks the Android `MediaCodecList` for Dolby Vision decoder profiles:

1. If the device exposes `DolbyVisionProfileDvheDtb`, it supports profile 7 and playback runs in `NATIVE` mode.
2. Otherwise, if it exposes `DolbyVisionProfileDvheSt`, it supports profile 8 and playback runs in `CONVERT` mode.
3. If codec discovery fails, the Android version is below API 27, or neither profile is supported, compatibility handling is disabled with `OFF` mode.

The client device profile continues to describe the device's actual codec and HDR capabilities to Jellyfin. It does not falsely advertise full dual-layer profile 7 support.

### Conversion behavior

Profile 7 dual-layer Dolby Vision consists of an HDR10-compatible HEVC base layer plus Dolby Vision metadata and an enhancement layer:

- HEVC NAL unit type 62 contains the Dolby Vision RPU metadata.
- HEVC NAL unit type 63 contains the enhancement layer.

In `CONVERT` mode, the extractor chain:

1. Detects a profile 7 Dolby Vision format, such as `dvhe.07.06`.
2. Rewrites each type 62 RPU from profile 7 to profile 8.1 through the bundled `libdovi` native library.
3. Drops type 63 enhancement-layer NAL units.
4. Rewrites the exposed codec identifier from `dvhe.07` to `dvhe.08`.

The hardware decoder consequently receives an HDR10 base layer with profile 8.1 Dolby Vision metadata, which profile 8-capable devices can decode.

If RPU conversion becomes unavailable or fails for a sample, the extractor falls back to stripping Dolby Vision metadata and enhancement-layer data. The HDR10-compatible HEVC base layer remains playable where the device supports HEVC HDR10.

### Native bridge

`DoviRpu` loads the `moonfin_dovi` JNI shim, which dynamically opens the ABI-specific `libdovi.so` binary. A missing shim, missing library, unsupported ABI, or missing native symbol does not crash playback; conversion is reported unavailable and the extractor can downgrade to base-layer playback.

### Container paths

The same compatibility filter is installed in both Media3 extraction paths:

- Progressive/direct playback wraps the standard extractor factory with `DoviCompat.wrap`.
- HLS uses `DoviCompatHlsExtractorFactory` in the custom `HlsMediaSource.Factory`, so fMP4 HLS segments receive the same filtering.

For Matroska files, `MoonfinMatroskaExtractor` handles dual-layer profile 7 RPUs carried in `BlockAdditional` data in addition to in-band NAL units.

`DoviMediaSourceFactory` chooses the custom HLS media source for `.m3u8` URLs or media items identified as `application/x-mpegURL`; all other media items continue through the normal Media3 source factory.

### Diagnostics

The compatibility chain emits `DoviCompatReport` entries through Timber. Reports include the requested and applied mode, original and rewritten codec strings, RPU conversion counts, dropped enhancement-layer units, source location, and byte counts. HLS reports use the `Dolby Vision HLS compatibility` log prefix.

## Selected-Audio HLS Direct Stream

### User setting

The Advanced Playback setting `Direct stream selected audio` is disabled by default. When enabled, the client turns off direct play for that request while leaving direct streaming enabled. This asks Jellyfin for an HLS stream instead of a static original-file URL.

The objective is to receive only the selected audio track rather than downloading all audio tracks from a high-bitrate container, while retaining video and audio copy whenever the server and device profile permit it.

### PlaybackInfo request

`PlaybackManager` posts a `PlaybackInfoDto` containing:

- `enableDirectStream` from the playback options.
- `enableDirectPlay = false` when the selected-audio setting is enabled.
- `audioStreamIndex` for the selected audio stream.
- `subtitleStreamIndex` for the selected subtitle stream.
- `allowVideoStreamCopy = true`.
- `allowAudioStreamCopy = true`.

Jellyfin may return an HLS `master.m3u8` stream, normally with fMP4 segments. Video and selected audio remain stream copied when compatible. The server may still transcode or remux when required by source codec, bitrate limits, container constraints, or the device profile.

The device profile advertises HLS streaming profiles for MPEG-TS and fMP4. Both set `enableSubtitlesInManifest = true` and include supported video and audio codecs.

## Subtitle Handling for HLS

### Server delivery policy

The client sends the selected subtitle index in the initial playback-info request. This lets Jellyfin choose the correct subtitle delivery method before the HLS URL is created.

The Android TV device profile advertises:

- WebVTT/VTT as embedded, external, and HLS subtitle delivery.
- SRT, SubRip, and TTML as embedded or external delivery.

The server's HLS manifest can include text subtitle metadata. Non-WebVTT text subtitles are deliberately requested through Jellyfin's standalone external subtitle endpoint instead of forcing server conversion to WebVTT. PGS/PGSSUB is advertised as standalone external delivery when `pgsDirectPlay` is enabled; otherwise Jellyfin can use embedded or `ENCODE` delivery.

### Client composition

A custom direct HLS factory bypasses Media3's normal `DefaultMediaSourceFactory` subtitle composition. `DoviMediaSourceFactory` restores that required behavior:

1. It creates the Dolby Vision-compatible HLS media source.
2. It creates one progressive subtitle media source for each attached external subtitle configuration.
3. It parses that subtitle with the configured `SubtitleParser.Factory`.
4. `SubtitleExtractor` produces `application/x-media3-cues` samples.
5. It joins the HLS source and subtitle source with `MergingMediaSource`.

Using cue samples is required because legacy text decoding is disabled in the Media3 renderer. Passing raw `application/x-subrip` samples through `SingleSampleMediaSource` causes a runtime failure because the renderer expects `application/x-media3-cues`.

The Advanced Playback setting `Preload all external subtitles` controls attachment policy. It is disabled by default. In the default lazy mode, only the selected external subtitle is attached. When enabled, every compatible external subtitle returned by Jellyfin is attached before preparation, allowing Media3 to switch among prepared tracks without recreating the HLS stream. This may start simultaneous subtitle requests and can increase startup work.

### Selection behavior

External subtitle track IDs use `JF_EXTERNAL:<stream index>`. After Media3 merges sources, IDs are prefixed by the child-source index, for example `1:JF_EXTERNAL:18`.

When playback becomes ready, the controller force-enables the attached external cue track by matching the ID suffix. This forced selection does not restart playback.

In lazy mode, when the user changes to another external subtitle, the controller:

1. Stops the current playback session.
2. Updates `subtitleStreamIndex`.
3. Requests new playback information from Jellyfin at the current position.
4. Attaches only the newly selected external subtitle source.
5. Starts playback and force-enables that now-attached cue track after preparation.

This restart is intentional and avoids fetching every subtitle up front. In preload mode, the controller instead applies a `TrackSelectionOverride` to the already prepared external subtitle group. The HLS video source is not recreated and playback remains continuous. If the requested external track is not present in the prepared source graph, the controller falls back to the lazy restart path.

Embedded and HLS subtitle tracks continue to use Media3 track-group selection without a playback restart. Subtitles that Jellyfin negotiates as `ENCODE`, or which must be burned in, restart playback with subtitle baking enabled.

## Verification

A successful HLS profile 7 playback should show all of the following in Logcat:

- A `DoviCompatReport` converting `dvhe.07.*` to `dvhe.08.*`.
- A video input format using `video/dolby-vision` with a `dvhe.08.*` codec.
- In lazy mode, one selected external subtitle source when an external subtitle is selected; in preload mode, one source per compatible external subtitle.
- A text `TrackGroup` with `mimeType=application/x-media3-cues` and an ID ending in `JF_EXTERNAL:<stream index>`.
- `renderedFirstFrame` and player state `READY` without an `ExoPlaybackException`.

Relevant implementation files:

- `playback/media3/exoplayer/src/main/kotlin/DoviCompatibility.kt`
- `playback/media3/exoplayer/src/main/kotlin/DoviCompatExtractorsFactory.kt`
- `playback/media3/exoplayer/src/main/kotlin/DoviMediaSourceFactory.kt`
- `playback/media3/exoplayer/src/main/kotlin/DoviRpu.kt`
- `playback/media3/exoplayer/src/main/kotlin/MoonfinMatroskaExtractor.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackManager.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackControllerHelper.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java`
- `app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt`
