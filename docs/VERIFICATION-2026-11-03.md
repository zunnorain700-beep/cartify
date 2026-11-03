# Verification log — 3 November 2026

Evidence collected on the connected phone (29021FDH300434) and from the build,
recorded so the claims in the handover can be checked.

## Build

- `BUILD SUCCESSFUL` from a clean working tree, repeated at 10:55.
- APK produced at `app/build/outputs/apk/debug/app-debug.apk`, installed with `adb install -r`.

## On device

- Home screen: greeting, search box, "Recommended for you" shelf, download rows, 0 crashes.
- Offline tab: **332 songs**, shuffle, sort, search field present.
- Playlists tab: **2 playlists**, "Newest" sort, a playlist showing *23 songs · 1 hr 26 min*.
- Download quality on the device: `mp3_quality` = **0** (Best / V0) — nothing downgraded.
- Audio focus: log line `Cartify : audio focus granted` while playing; media session reports
  `state=PLAYING`.
- No `FATAL EXCEPTION` in logcat across the tab sweep.

## Download and matching (10:32–10:34)

- `matched "Everybody's Changing Keane" -> "Keane - Everybody's Changing (Official Music Video)" score=100 identity=50`
- `matched "Sovereign Light Café Keane" -> "Keane - Sovereign Light Café (Official Music Video)" score=107 identity=50`
- `matched "With Or Without You (BBC Jo Whiley Session / 2004) Keane" -> same, score=95 identity=50`
  — the session-specific title was kept rather than swapped for the studio version, which is
  the correct behaviour: the user asked for that version.
- `track 1/1 slot=0 took 25360 ms`, then the file count on disk rose (333 -> 334), confirming
  the file actually landed.

## Source invariants (checked by script, all PASS)

variant guard · identity shortcut · no-downgrade quality · lyrics timing fit ·
lyrics artist fit · audio focus · recent history · spotify throttle backoff ·
spotify collection cache · readable row errors · friendly offline text ·
spotify paste/resolve path.

## Document coverage

All **57** commits made today are described in the handover PDF body: an automated
coverage check reported `TOTAL 57 UNCOVERED 0`.

## Repo

`HEAD` matches `origin/master`; working tree clean; 57 commits today.
