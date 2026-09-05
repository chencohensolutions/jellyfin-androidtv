# Subtitle Playback Session Handoff

## Goal

Support Jellyfin external subtitle selection while using the Android TV Dolby
Vision-compatible HLS playback path:

- Keep text subtitles out of the HLS manifest/video stream path.
- Default to lazy loading of external subtitles.
- Offer opt-in preloading for seamless switching among prepared external
  subtitle tracks.
- Support standalone external PGS/PGSSUB without enabling Media3 legacy
  subtitle decoding.
- Preserve Dolby Vision profile 7-to-profile 8 HLS compatibility.

## Current Implementation

### External subtitle policy

`UserPreferences.preloadExternalSubtitles` is disabled by default and exposed
as **Preload all external subtitles** in Advanced Playback settings.

- Disabled: `VideoManager` attaches only the server-selected external subtitle
  to the Media3 `MediaItem`.
- Enabled: `VideoManager` attaches every compatible external subtitle returned
  in the playback response. `PlaybackControllerHelper` selects a prepared track
  using `TrackSelectionOverride`, so it does not recreate the HLS video source.
- If an external track is not part of the prepared Media3 source graph, the app
  uses the lazy fallback path.

Prepared external track IDs are `JF_EXTERNAL:<Jellyfin stream index>`. Media3
prefixes IDs after merging sources, so selection matches IDs ending in
`:JF_EXTERNAL:<stream index>`.

### Lazy external selection

When lazy mode needs a new external track, the app first opens the existing
authenticated Jellyfin subtitle URL on a background coroutine. The current
playback remains active during this request. A successful response means the
server has finished any required extraction; only then does the app restart
from the captured playback position with the selected subtitle.

The preflight uses `HttpURLConnection` and closes the response stream after the
server has responded. It does not intentionally download the full subtitle
payload to the device.

### Background cache preparation

When **Preload all external subtitles** is enabled, `VideoManager` also makes
one background request to an existing embedded subtitle delivery URL as HLS
playback starts. This is an Android-only change; no Jellyfin server endpoint or
server source modification is required.

Jellyfin's existing `ExtractAllExtractableSubtitles` implementation handles
that request by constructing one FFmpeg operation with one MKV input and one
`-map` output per extractable embedded subtitle stream. It creates all subtitle
cache files in a batch. This avoids starting an FFmpeg extraction process per
subtitle.

This request warms the server cache while HLS playback continues. The executor
is shut down when `VideoManager` is destroyed.

### PGS/PGSSUB delivery

The Android device profile advertises PGS and PGSSUB as external-capable when
`pgsDirectPlay` is enabled. Jellyfin delivers PGS through its standalone
`Stream.pgssub` endpoint.

That endpoint returns raw SUP data. Every record has this framing:

```text
PG + 4-byte PTS + 4-byte DTS + section type + 2-byte section length + payload
```

Media3's native `PgsParser` accepts only:

```text
section type + 2-byte section length + payload
```

`SupPgsParser` in `DoviMediaSourceFactory` removes the SUP framing, groups PGS
sections through each `0x80` end marker, converts 90 kHz PTS values to
microseconds, and passes each display set to the Media3 parser. Parsed bitmap
cues are emitted via `SubtitleExtractor` as `application/x-media3-cues`.

Raw `application/pgs` must not be attached with `SingleSampleMediaSource`:
the app disables legacy subtitle decoding and Media3 fails with
`Legacy decoding is disabled, can't handle application/pgs samples`.

### PGS startup and switching behavior

The tested PGS stream was a roughly 42 MB SUP file with 4,148 display sets. On
the Google TV Streamer, initial full parsing took about 55 seconds after the
server cache was already warm. This is local client parsing, not a restart of
the HLS stream.

Once parsing has completed, a PGS track switch applies immediately through a
track override. A subtitle image may still not be visible until the next timed
PGS display event. PGS consists of individual bitmap events, unlike text
subtitles whose active state can appear less delayed.

Temporary per-display-set PGS logs were removed. They produced thousands of
synchronous Logcat writes and slowed full PGS parsing substantially.

## Confirmed Findings

- The explicitly selected subtitle stream index is preserved across a fresh
  Jellyfin playback response. This fixed an earlier issue where stream `20`
  was replaced with a different subtitle sharing the same language.
- The external PGS parser produces valid bitmap cues; this was verified before
  temporary logging was removed.
- The original PGS track contains full subtitles when played locally.
- A previously cached Jellyfin extracted PGS file was truncated at `00:12:35`.
  It was created while the server disk was full. Clearing the Jellyfin subtitle
  and transcoding cache allowed a complete roughly 42 MB PGS cache file to be
  regenerated.
- Jellyfin's subtitle cache accepts any nonempty extracted output whose source
  size and modification timestamp match. It does not validate that the PGS
  timeline reaches the source media duration. This is a server behavior that
  was observed but intentionally not changed in this session.
- A cold PGS endpoint request took about 14 seconds while Jellyfin regenerated
  the cache. Media3's default four-second stuck-buffering detector caused
  failures for cold external subtitles, including SubRip. The client raises the
  buffering detector threshold to 30 seconds.
- No Jellyfin server source changes are part of the final worktree.

## Important Limitation

Standard Media3 can switch among text tracks already attached to its prepared
`MediaItem`. It cannot dynamically append an external subtitle source to an
already prepared `MergingMediaSource`.

Therefore:

- Lazy mode can keep video playing while the server prepares a subtitle, but
  must rebuild the MediaItem to add a previously unattached track.
- Preload mode attaches tracks before initial preparation and permits seamless
  HLS/video switching after each source has downloaded and parsed.
- Server cache preparation reduces server extraction time but does not remove
  the one-time local parsing cost of a large bitmap PGS stream.

## Files Changed

- `app/src/main/java/org/jellyfin/androidtv/preference/UserPreferences.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/settings/screen/playback/SettingsPlaybackAdvancedScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackControllerHelper.kt`
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/PlaybackController.java`
- `app/src/main/java/org/jellyfin/androidtv/util/profile/deviceProfile.kt`
- `playback/media3/exoplayer/src/main/kotlin/DoviMediaSourceFactory.kt`
- `DolbyVision.SPEC.md`

## Validation Completed

- `:playback:media3:exoplayer:compileReleaseKotlin` succeeded after PGS parser
  changes.
- `:app:compileReleaseDebugJavaWithJavac` and
  `:app:compileReleaseDebugKotlin` succeeded after client-side prewarm changes.
- `:app:assembleReleaseDebug -x lintVitalAnalyzeReleaseDebug` succeeded.
- The release-debug APK was installed and launched on the Google TV Streamer
  through the active TLS ADB connection.
- `git diff --check` passed apart from the normal CRLF-to-LF warning for
  `DoviMediaSourceFactory.kt`.

## Follow-up Work

- Add unit tests for SUP record parsing, display-set boundaries, timestamp
  conversion, and clear display sets.
- Decide whether large PGS parsing should have a user-visible prepared state,
  so the setting does not imply that a source is immediately ready.
- Consider a Media3 source implementation with an indexed PGS cache if instant
  recovery of the currently active bitmap subtitle after a track switch is a
  requirement. This is more involved than a `MergingMediaSource` override.
- Consider a Jellyfin server-side cache-integrity improvement that rejects a
  truncated but nonempty extracted subtitle cache. This was explicitly outside
  the scope of the final client-only implementation.
