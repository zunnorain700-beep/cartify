# Cartify — engineering notes

Living document. Update it whenever the architecture changes.

## Pipeline

```
Spotify link ──SpotifyResolver──▶ SpotifyCollection(name, cover, Track[])
                                        │
                    DownloadService (foreground, dataSync, 4 workers)
                                        │  per row: DownloadEngine.Task (cancellable)
                                        ▼
   yt-dlp search (ytsearch6, metadata only) → pick best URL by duration/title
                                        ▼
   yt-dlp download that one URL (--concurrent-fragments 4) + ffmpeg → mp3
                                        ▼
   Id3Writer (title/artist/album/cover) → LibraryStore.importFile → MediaStore
                                        ▼
   ProgressBus ──▶ TrackAdapter (row state/progress) + notification
```

Playback is independent: `Playback` owns a `MediaPlayer` + queue; `PlaybackService` keeps it
alive in the foreground with a `mediaPlayback` notification.

## Track states

`IDLE → QUEUED → ACTIVE → DONE | ERROR`, and `QUEUED/ACTIVE → IDLE` on cancel
(`onTrackCancelled`, never `ERROR`). The row's action button is state-driven:
download / cancel(✕) / play / retry.

## Download cancellation contract

- `DownloadEngine.Task.cancel()` sets a flag **and** destroys the bound yt-dlp process id.
- `Task.check()` at each stage boundary throws `Failure("cancelled", …)`.
- `DownloadService` treats a cancelled/`cancelled` failure as `ProgressBus.cancelled(index)`.
- `DownloadService.ACTION_CANCEL_TRACK` (with `EXTRA_INDEX`) = one row;
  `ACTION_CANCEL` = the whole queue, stops the service.

## Build

- Gradle 8.5, JDK 17, compileSdk 34, minSdk 24, Java only.
- Native ABIs limited to `arm64-v8a` and `x86_64` (yt-dlp/ffmpeg), `extractNativeLibs=true`,
  `useLegacyPackaging true`.
- Kotlin stdlib stubs from `youtubedl-android` are excluded/forced (see `app/build.gradle`).

## Backlog

1. Clear `cache/cartify-work-<slot>` after every attempt (cancel leaves partials).
2. Stable task keys instead of list indices (queue re-resolve mis-targets cancels).
3. Library sort toggle + artist/album grouping.
4. Incremental MediaStore insert instead of full library rescan after a queue.
5. Retry-all failed rows; audio quality selector.
6. Consistent seek/prev in the playback notification.
7. Unit-test `SpotifyResolver` parsing and `DownloadEngine.pickCandidate` ranking with fixtures.
