# Cartify feature backlog

Working list for the 3 November 2026 session. Ordered by value, not by effort.

## Done in this session

- [x] Score-based track matching that rejects a wrong recording instead of guessing
- [x] Audio quality V0 with highest-bitrate stream selection
- [x] Android player client: per-track extraction 16.5s to 4.0s
- [x] Honest progress during extraction (creeping 6-14 percent, "Fetching audio")
- [x] Adaptive parallel fragments and worker pool from measured link speed
- [x] Format-miss, dropped-connection and zero-data retries on a different client
- [x] Spotify request gating, 429 backoff, single-threaded artwork, response cache
- [x] DNS retry before declaring a link unreadable
- [x] Thread-pool shutdown in all three activities
- [x] Bounded Art.MISSING and DownloadEngine pick cache
- [x] Four-part version comparison in the updater
- [x] Refuse re-resolve while a queue runs (index-cancel safety)
- [x] App menu: manual update check, library size, rescan
- [x] Retry button for failed tracks
- [x] Sleep timer (15/30/45/60 minutes)
- [x] Clear play queue from the app menu

## Next, highest value first

- [x] Per-track download quality choice (V0 / 320 / 192 / 128)
- [x] Save the shuffle and repeat choice across app restarts
- [x] In-app search (Deezer + iTunes) with ranked results
- [x] Recommended music shelf on the search home
- [x] Recent searches stored on device
- [x] Search becomes the home tab; library renamed Offline
- [ ] Search inside playlists, not just the library
- [x] Sort playlists by name or song count
- [x] Show download progress percentage on the notification title, not just text
- [x] Export a playlist as an .m3u file next to the MP3s
- [ ] Undo for a single-song delete (keep the file for ten seconds)
- [ ] Warn before deleting a song that is inside playlists
- [ ] Remember the last scroll position per tab
- [ ] Artist grouping in the library

## Ideas worth evaluating

- Replace the whole-library MediaStore rescan with an incremental insert per finished track
- Cache lyrics to disk so a replayed song does not hit the network again
- Detect a track that is already playing and offer to jump to it instead of re-adding
- Show total listening time in Library info
