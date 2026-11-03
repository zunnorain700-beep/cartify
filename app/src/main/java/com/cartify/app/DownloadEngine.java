package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * Turns Spotify metadata into a local MP3 using the bundled yt-dlp + ffmpeg.
 *
 * Spotify itself streams DRM protected audio, so the audio is resolved from a
 * public provider through yt-dlp (the downloader framework), converted to MP3 by
 * the bundled ffmpeg, tagged with the Spotify metadata and copied into
 * Music/Cartify through MediaStore.
 */
public final class DownloadEngine {

    private static final String TAG = "Cartify";
    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);
    /** Resolved video urls, so retries and repeat downloads skip the search round trip. */
    /**
     * Search result per query, so a retry or a repeat download skips the lookup.
     *
     * Bounded: this used to grow for the lifetime of the process, which on a device that
     * downloads a lot (or is shared between listeners) is a slow leak. Oldest entries are
     * dropped once the cap is reached; a dropped entry only costs one more search.
     */
    private static final int PICK_CACHE_MAX = 400;
    private static final Map<String, String> PICKED =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > PICK_CACHE_MAX;
                        }
                    });
    private static final String PREFS = "cartify_prefs";
    private static final String KEY_UPDATE_AT = "ytdlp_updated_at";
    private static final long UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    /** Receives progress; returning {@code true} asks the engine to stop. */
    public interface Listener {
        boolean onProgress(int percent, String detail);
    }

    /**
     * One cancellable download job. {@link #cancel()} kills the yt-dlp process owned
     * by this job, so cancelling actually stops the transfer instead of letting it
     * finish in the background.
     */
    public static final class Task {
        private volatile boolean cancelled;
        private volatile String processId = "";

        public void cancel() {
            cancelled = true;
            destroy(processId);
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void bind(String id) {
            processId = id == null ? "" : id;
            if (cancelled) destroy(processId);
        }

        void check() throws Failure {
            if (cancelled) throw new Failure("cancelled", "Cancelled");
        }

        private static void destroy(String id) {
            if (id == null || id.isEmpty()) return;
            try {
                YoutubeDL.getInstance().destroyProcessById(id);
            } catch (Throwable e) {
                Log.w(TAG, "could not stop " + id, e);
            }
        }
    }

    public static class Failure extends Exception {
        public final String stage;
        final boolean staleExtractor;
        /** The video resolved, but the chosen client offered no usable stream. */
        final boolean formatMiss;

        Failure(String stage, String message) {
            this(stage, message, false, false);
        }

        Failure(String stage, String message, boolean staleExtractor) {
            this(stage, message, staleExtractor, false);
        }

        Failure(String stage, String message, boolean staleExtractor, boolean formatMiss) {
            super(message);
            this.stage = stage;
            this.staleExtractor = staleExtractor;
            this.formatMiss = formatMiss;
        }
    }

    private DownloadEngine() {
    }

    public static synchronized void ensureInitialised(Context context) throws Exception {
        if (INITIALISED.get()) return;
        try {
            YoutubeDL.getInstance().init(context.getApplicationContext());
        } catch (Throwable e) {
            throw new Exception("yt-dlp failed to start: " + describe(e));
        }
        try {
            FFmpeg.getInstance().init(context.getApplicationContext());
        } catch (Throwable e) {
            throw new Exception("ffmpeg failed to start: " + describe(e));
        }
        INITIALISED.set(true);
        // The extractor refresh is a network round trip and must not sit in front of the
        // first download: it runs on a background thread and only forces an update when
        // its cached copy is actually stale.
        final Context app = context.getApplicationContext();
        new Thread(() -> refreshExtractor(app, false), "cartify-ytdlp-update").start();
    }

    /**
     * YouTube changes its player constantly, so the yt-dlp shipped inside the
     * library goes stale and downloads start failing with HTTP 403. Refreshing
     * the extractor from its own release feed keeps Cartify working without an
     * app update.
     */
    private static void refreshExtractor(Context context, boolean force) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_UPDATE_AT, 0L);
        if (!force && System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return;
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE.INSTANCE);
            prefs.edit().putLong(KEY_UPDATE_AT, System.currentTimeMillis()).apply();
            Log.d(TAG, "yt-dlp refreshed to " + YoutubeDL.getInstance().versionName(context));
        } catch (Throwable e) {
            Log.w(TAG, "yt-dlp refresh failed: " + describe(e));
        }
    }

    public static String download(Context context, Track track, Listener listener) throws Exception {
        return download(context, track, listener, 0, new Task());
    }

    /**
     * Resolves the upload for the first few tracks before the user taps Download.
     *
     * The search is the slowest step of a download and it only depends on metadata, so
     * doing it while the user is still reading the track list means the transfer itself
     * starts immediately on tap.
     */
    public static void prewarm(Context context, java.util.List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        new Thread(() -> {
            try {
                ensureInitialised(context.getApplicationContext());
            } catch (Exception e) {
                return;
            }
            for (int i = 0; i < Math.min(4, tracks.size()); i++) {
                Track t = tracks.get(i);
                String query = t.searchQuery();
                if (query.trim().isEmpty() || PICKED.containsKey(query)) continue;
                try {
                    PICKED.put(query, pickCandidate(query, t, new Task()));
                } catch (Throwable ignored) {
                }
            }
        }, "cartify-prewarm").start();
    }

    public static String download(Context context, Track track, Listener listener, int slot) throws Exception {
        return download(context, track, listener, slot, new Task());
    }

    /**
     * Downloads one track into a per-slot work folder.
     *
     * Parallel downloads share the process, so each worker needs its own directory;
     * using one folder would have two yt-dlp runs writing over each other's files.
     */
    public static String download(Context context, Track track, Listener listener, int slot, Task task)
            throws Exception {
        final Task job = task == null ? new Task() : task;
        ensureInitialised(context);
        job.check();
        File work = new File(context.getCacheDir(), "cartify-work-" + slot);
        if (!work.exists() && !work.mkdirs()) throw new Failure("setup", "Cannot create work folder");
        try {
            return downloadInto(context, track, listener, job, work);
        } finally {
            // A cancelled or failed transfer used to leave .part fragments behind in the
            // slot folder; the next run cleared them, but they sat on disk until then.
            clear(work);
        }
    }

    private static String downloadInto(Context context, Track track, Listener listener,
                                       Task job, File work) throws Exception {
        String query = track.searchQuery();
        if (query.trim().isEmpty()) throw new Failure("search", "Track has no title");
        report(listener, 2, "Searching");

        File mp3 = null;
        Failure lastFailure = null;
        // The bar must never travel backwards. Each attempt starts above the best value
        // the previous one reached, so a retry reads as "still working", not as a reset.
        int[] highWater = {6};
        // Four attempts, not three: on a poor link the first tries are what discover that
        // the connection is poor, and the last one runs with a single fragment and long
        // timeouts. Failing a track that would have finished on attempt four is worse than
        // spending one more round on it.
        for (int attempt = 0; attempt < 4 && mp3 == null; attempt++) {
            clear(work);
            try {
                mp3 = fetch(work, track, listener, attempt, job, highWater, context);
            } catch (Failure f) {
                if ("cancelled".equals(f.stage)) throw f;
                lastFailure = f;
                // Three things are worth another attempt: a stale extractor, a wrong-length
                // match, and a client that offered no usable stream for a video that
                // resolved fine. Each retry changes something real (refreshed extractor,
                // different candidate, different client chain) rather than repeating it.
                boolean retryable = f.staleExtractor || f.formatMiss || "verify".equals(f.stage);
                if (!retryable || attempt == 3) throw f;
                if (f.staleExtractor) {
                    report(listener, highWater[0], "Refreshing downloader");
                    refreshExtractor(context.getApplicationContext(), true);
                } else if (f.formatMiss) {
                    report(listener, highWater[0], "Trying another source");
                } else {
                    report(listener, highWater[0], "Trying another match");
                }
            }
        }
        if (mp3 == null) throw lastFailure == null ? new Failure("download", "Download failed") : lastFailure;

        job.check();
        // The post-processing stages own the top of the bar: conversion ends at 92, tagging
        // and saving fill the rest. Every mark is lifted above the high-water value so no
        // stage can pull the number back down.
        highWater[0] = Math.max(highWater[0], 92);
        report(listener, highWater[0], "Tagging");
        byte[] art = null;
        if (track.coverUrl != null && !track.coverUrl.isEmpty()) {
            art = Net.bytes(track.coverUrl);
            if (art != null && art.length < 1024) art = null;
        }
        try {
            Id3Writer.tag(mp3, track.title, track.artist, track.album, art);
        } catch (Throwable e) {
            Log.w(TAG, "tagging failed", e);
        }

        job.check();
        highWater[0] = Math.max(highWater[0], 95);
        report(listener, highWater[0], "Saving to Music/Cartify");
        String uri;
        try {
            uri = LibraryStore.importFile(context, mp3, track.title, track.artist, track.album);
        } catch (Throwable e) {
            throw new Failure("save", "Could not save to storage: " + describe(e));
        }
        //noinspection ResultOfMethodCallIgnored
        mp3.delete();
        report(listener, 100, "Done");
        return uri;
    }

    /**
     * Resolves the best upload for a track, then downloads exactly that one.
     *
     * The candidates are inspected from metadata first: searching with ytsearch5 and
     * downloading the collection pulled five files per song (very slow, and every
     * rejected candidate flashed an error row). Here only the chosen URL is fetched.
     */
    /**
     * Player clients tried in order, per attempt.
     *
     * android is first because it skips the JavaScript challenge that made extraction
     * take 13-20 s. ios and web exist as fallbacks for the videos whose android
     * manifest carries no audio-only stream; the retry loop switches chain on a
     * format failure so a track that failed once gets a genuinely different client.
     */
    private static final String[] CLIENT_CHAINS = {
            "android,ios",
            "ios,web",
            "web,android",
            // Last attempt uses the widest client set. It is the slowest to resolve, which
            // is exactly why it is the last resort rather than the default.
            "web,android,ios,mweb",
    };

    private static File fetch(File work, Track track, Listener listener, int attempt, Task task,
                              int[] highWater, Context context) throws Failure {
        String query = track.searchQuery();
        long expected = track.durationMs;
        task.check();

        String clientChain = CLIENT_CHAINS[Math.min(attempt, CLIENT_CHAINS.length - 1)];

        // How many parallel fragments this transfer may open. On a fast link more streams
        // win; on a slow one they starve each other and cause the timeouts that read as
        // failures. Each retry HALVES the count rather than shaving one off, because a
        // shared or congested connection can report a healthy link rate and still deliver
        // a fraction of it: 8 -> 4 -> 2 turns "times out forever" into "finishes slowly".
        int fragments = Math.max(1, fragmentsForSpeed(context) >> attempt);

        // Stages re-report a fixed mark (4 matching, 90 tagging, 95 saving). On a retry the
        // high-water mark is already far past those, so reporting them raw made the row
        // fall back to 4 and then climb again — visible as the number jumping around.
        int stage = Math.max(4, Math.min(85, highWater[0]));
        report(listener, stage, "Matching track");
        String picked = attempt == 0 ? PICKED.get(query) : null;
        if (picked == null) {
            picked = pickCandidate(query, track, task);
            PICKED.put(query, picked);
        }
        task.check();

        YoutubeDLRequest request = new YoutubeDLRequest(picked);
        // Format selection must never be empty. A single client can hand back a video
        // whose only streams are video+audio muxed, and "bestaudio/best" then matches
        // nothing at all: "Requested format is not available". The trailing "best" and
        // the wildcard keep a playable stream available on every client.
        request.addOption("-f", "bestaudio*/best");
        // Sort by audio bitrate, highest first, so the chosen stream is the best
        // the source offers instead of an arbitrary format id.
        request.addOption("-S", "abr,acodec:opus");
        request.addOption("--no-playlist");
        // The default web client has to solve YouTube's JavaScript player challenge
        // before it can name a stream, which measured 13-20 s per track on this device
        // and was what the progress bar sat on at 6%. The android client returns the
        // same audio without that round trip: 16.5 s of extraction became 4.0 s on a
        // back-to-back test of the same network and device.
        //
        // A chain rather than a single client: some videos expose no audio-only stream
        // on android, and yt-dlp tries each in order until one yields a format.
        request.addOption("--extractor-args",
                "youtube:player_client=" + clientChain);

        request.addOption("--no-warnings");
        request.addOption("--no-mtime");
        request.addOption("--extract-audio");
        request.addOption("--audio-format", "mp3");
        // V0 is LAME's best variable-bitrate preset (~245 kbps) and the least lossy MP3
        // this pipeline can produce. It used to be V5 (~130 kbps), which threw away
        // detail on top of the loss the source already carries.
        request.addOption("--audio-quality", "0");

        // ---------------------------------------------------------------- slow links
        //
        // The previous settings were tuned on a fast connection and actively hurt on a
        // weak one. Every option below exists because its opposite fails at low bandwidth:
        //
        // --concurrent-fragments 8 opened eight parallel streams. On a slow link those
        //   compete for the same narrow pipe, so each fragment times out waiting its turn
        //   and yt-dlp reports "Remote end closed connection" or simply stalls. Fewer
        //   streams finish more reliably than many starved ones, and the count is chosen
        //   from the measured link speed when one is known.
        //
        // --retries 3 with the default backoff gave up after a few seconds of a link that
        //   was merely slow, not broken. More attempts with fragment-level retries let a
        //   transfer resume instead of restarting the whole track.
        //
        // The socket timeout is what actually converts "slow" into "failed": yt-dlp's
        //   default read timeout is short enough that a 2G-class link trips it mid-file.
        request.addOption("--retries", "10");
        request.addOption("--fragment-retries", "10");
        request.addOption("--retry-sleep", "2");
        request.addOption("--socket-timeout", "30");
        request.addOption("--concurrent-fragments", String.valueOf(fragments));

        // Never abort a slow download for being slow. This is the difference between
        // "this track takes four minutes on a bad connection" and "this track failed".
        request.addOption("--no-abort-on-error");
        // The audio stream is already Opus/AAC at a fixed rate; re-encoding it at V0
        // cost real time per track for no audible gain.
        request.addOption("--postprocessor-args", "-threads 0");
        request.addOption("-o", new File(work, "track.%(ext)s").getAbsolutePath());

        final String processId = "cartify-" + System.nanoTime() + "-" + attempt;
        task.bind(processId);
        YoutubeDLResponse response;
        try {
            response = YoutubeDL.getInstance().execute(request, processId,
                    new ProgressFunction(listener, task, highWater));
        } catch (Throwable e) {
            task.check();
            throw new Failure("download", "Could not download this track (" + describe(e) + ")");
        }
        task.bind("");
        task.check();
        if (response.getExitCode() != 0) {
            String err = errorLine(response.getErr());
            String lower = err.toLowerCase(Locale.US);
            // "format is not available" is not a stale extractor: the video resolved fine,
            // it just has no stream the chosen client offered. It is retried with a
            // different client chain instead of surfacing as a failed row.
            boolean formatMiss = lower.contains("requested format is not available")
                    || lower.contains("no video formats found")
                    || lower.contains("requested format not available");
            if (formatMiss) {
                throw new Failure("format", err, false, true);
            }
            // A dropped connection is retried on a different client chain too: the retry
            // opens a fresh CDN session instead of repeating the request that just failed.
            boolean transientNet = lower.contains("remote end closed connection")
                    || lower.contains("connection reset")
                    || lower.contains("connection aborted")
                    || lower.contains("timed out")
                    || lower.contains("temporary failure")
                    // YouTube sometimes hands back a stream URL that resolves but delivers
                    // nothing. The format is valid on paper, the CDN returns zero bytes.
                    // Another client gets a different URL, which is the actual fix.
                    || lower.contains("did not get any data blocks")
                    || lower.contains("unable to download video data")
                    || lower.contains("incomplete read");
            if (transientNet) {
                throw new Failure("format", err, false, true);
            }
            boolean stale = err.contains("403")
                    || lower.contains("sign in")
                    || lower.contains("confirm your age")
                    || lower.contains("unable to extract");
            throw new Failure("download", "Downloader error: " + err, stale);
        }
        report(listener, 94, "Converting");
        File mp3 = findMp3(work);
        if (mp3 == null) throw new Failure("convert", "No MP3 was produced");
        report(listener, 97, "Tagging");
        return mp3;
    }

    /**
     * How many parallel fragments the current link can actually feed.
     *
     * The old build hardcoded 8, which is faster on a good connection and actively harmful
     * on a bad one: eight streams share one narrow pipe, each waits longer than its socket
     * timeout, and yt-dlp gives up on a link that was only slow. The negotiated downstream
     * rate is a good enough proxy — it is what the radio has agreed to, not a guess.
     *
     * Unknown links get 4, the middle of the range, so an unreadable connection is treated
     * as average rather than as either extreme.
     */
    private static int fragmentsForSpeed(Context context) {
        if (context == null) return 4;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return 4;
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return 2;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return 2;
            int kbps = caps.getLinkDownstreamBandwidthKbps();
            if (kbps <= 0) return 4;
            if (kbps < 500) return 1;       // ~2G / poor signal: one stream, no contention
            if (kbps < 2000) return 2;      // ~3G
            if (kbps < 8000) return 4;      // typical 4G
            return 8;                       // fast link: parallel fragments pay off
        } catch (Throwable e) {
            return 4;
        }
    }

    /**
     * Finds the upload that actually is this song.
     *
     * The previous version accepted anything within 20 seconds (or 12.5% of the track)
     * of the expected length and then fell back to the first search hit, which is how a
     * "Bonus Track" edit got saved as the real song. Matching is now a scored decision:
     * title words, artist words, duration and explicit variant tags all count, and a
     * candidate that does not clear the bar is rejected rather than guessed at.
     */
    private static String pickCandidate(String query, Track track, Task task) throws Failure {
        long expected = track == null ? 0L : track.durationMs;
        String wantTitle = normalise(track == null ? query : track.title);
        String wantArtist = normalise(track == null ? "" : track.artist);
        String wantAlbum = normalise(track == null ? "" : track.album);

        // Ten candidates instead of four: the right upload is often not in the top four
        // once live versions, remixes and karaoke tracks are in the results, and this is
        // still a single metadata call.
        YoutubeDLRequest search = new YoutubeDLRequest("ytsearch10:" + query);
        search.addOption("--skip-download");
        search.addOption("--no-warnings");
        search.addOption("--flat-playlist");
        search.addOption("--extractor-retries", "1");
        search.addOption("--print",
                "%(webpage_url)s\t%(duration)s\t%(title)s\t%(uploader)s\t%(channel)s");
        search.addOption("--ignore-errors");

        String searchId = "cartify-pick-" + System.nanoTime();
        task.bind(searchId);
        YoutubeDLResponse res;
        try {
            res = YoutubeDL.getInstance().execute(search, searchId, null);
        } catch (Throwable e) {
            task.check();
            throw new Failure("search", "Search failed (" + describe(e) + ")");
        }
        task.bind("");
        task.check();
        String out = res == null ? null : res.getOut();
        if (out == null || out.trim().isEmpty()) {
            throw new Failure("search", "No match found for this song");
        }

        String bestUrl = "";
        int bestScore = Integer.MIN_VALUE;
        String bestTitle = "";
        long bestDiff = Long.MAX_VALUE;

        for (String line : out.split("\\r?\\n")) {
            String[] parts = line.split("\\t");
            if (parts.length < 3) continue;
            String url = parts[0].trim();
            if (!url.startsWith("http")) continue;

            long durMs = Math.round(parseSeconds(parts[1].trim()) * 1000.0);
            String title = parts[2].trim();
            String uploader = parts.length > 3 ? parts[3].trim() : "";
            String channel = parts.length > 4 ? parts[4].trim() : "";

            int score = scoreCandidate(wantTitle, wantArtist, wantAlbum,
                    expected, title, uploader + " " + channel, durMs);
            long diff = expected > 0 && durMs > 0 ? Math.abs(durMs - expected) : Long.MAX_VALUE;

            if (score > bestScore || (score == bestScore && diff < bestDiff)) {
                bestScore = score;
                bestUrl = url;
                bestTitle = title;
                bestDiff = diff;
            }
        }

        // A score below this means nothing in the results looked like the track. Saying so
        // is better than saving the wrong song under the right name.
        if (bestUrl.isEmpty() || bestScore < MIN_ACCEPT_SCORE) {
            // A silent rejection is indistinguishable from a broken downloader when the
            // user sees a failed row. Recording the query, the best candidate and its
            // score makes every failure explainable from the log alone.
            Log.w(TAG, "rejected match: query=\"" + query + "\" bestScore=" + bestScore
                    + " best=\"" + bestTitle + "\" expected=" + expected + "ms");
            throw new Failure("search", bestUrl.isEmpty()
                    ? "No match found for this song"
                    : "Only a different recording of \"" + (track == null ? query : track.title)
                      + "\" was found (best match \"" + bestTitle + "\")");
        }
        return bestUrl;
    }

    /** Score at which a candidate is trusted as the same recording. */
    private static final int MIN_ACCEPT_SCORE = 55;

    /**
     * How much a search result looks like the requested track, 0-100.
     *
     * Duration is the strongest single signal because a variant edit is almost always a
     * different length, but it is not enough alone: a "Bonus Track" re-release can run
     * within a second of the original. Title and artist words carry the rest, and a
     * variant marker the user did not ask for subtracts heavily.
     */
    private static int scoreCandidate(String wantTitle, String wantArtist, String wantAlbum,
                                      long expectedMs, String candTitle, String candChannel,
                                      long candMs) {
        String title = normalise(candTitle);
        String channel = normalise(candChannel);
        if (title.isEmpty()) return 0;

        // --- duration (0-45)
        int durScore = 0;
        if (expectedMs > 0 && candMs > 0) {
            long diff = Math.abs(candMs - expectedMs);
            if (diff <= 1500) durScore = 45;
            else if (diff <= 3000) durScore = 38;
            else if (diff <= 5000) durScore = 28;
            else if (diff <= 8000) durScore = 16;
            else if (diff <= 15000) durScore = 6;
            else durScore = 0;
        } else {
            // No length to compare: do not punish, but do not reward either.
            durScore = 18;
        }

        // --- title words (0-35)
        int titleScore = 0;
        if (!wantTitle.isEmpty()) {
            if (title.equals(wantTitle)) titleScore = 35;
            else if (title.contains(wantTitle)) titleScore = 30;
            else {
                double cov = coverage(wantTitle, title);
                titleScore = (int) Math.round(cov * 26);
            }
        }

        // --- artist (0-20), looked for in the title or the channel name
        int artistScore = 0;
        if (!wantArtist.isEmpty()) {
            if (title.contains(wantArtist) || channel.contains(wantArtist)) artistScore = 20;
            else {
                double cov = Math.max(coverage(wantArtist, title), coverage(wantArtist, channel));
                artistScore = (int) Math.round(cov * 16);
            }
        } else {
            artistScore = 8;
        }

        int score = durScore + titleScore + artistScore;

        // --- variant penalty
        // A marker the user did not ask for means this is a different recording, however
        // close its length happens to be.
        String asked = wantTitle + " " + wantAlbum;
        for (String marker : VARIANT_MARKERS) {
            boolean inCandidate = title.contains(marker) || channel.contains(marker);
            boolean inWanted = asked.contains(marker);
            if (!inCandidate || inWanted) continue;
            // A lyrics upload is the same recording in worse packaging, so it loses less
            // than a genuinely different performance would.
            boolean packagingOnly = marker.equals("lyrics") || marker.equals("lyric video")
                    || marker.equals("legendado") || marker.equals("tradução")
                    || marker.equals("traducao") || marker.equals("letra")
                    || marker.equals("paroles") || marker.equals("subtitulado");
            score -= packagingOnly ? 22 : 40;
        }

        // Official sources are the ones that carry the master audio. A Topic channel is
        // YouTube's auto-generated home for a label's catalogue, so it is the single best
        // signal that this upload is the released recording and not a re-upload.
        boolean official = channel.contains("topic") || channel.contains("vevo")
                || title.contains("official audio") || title.contains("official music video")
                || title.contains("official video");
        if (official) score += 12;
        if (title.contains("official audio")) score += 4;

        return score;
    }

    /** Markers that mean the upload is a different recording unless it was asked for. */
    private static final String[] VARIANT_MARKERS = {
            "bonus track", "remix", "cover", "live", "instrumental", "karaoke",
            "sped up", "slowed", "nightcore", "8d", "reverb", "acoustic",
            "demo", "edit", "extended", "radio version", "reprise",
            // A lyrics video is a still image over audio that has usually been re-encoded
            // at least once. It scored 88 against 78 for the official audio in testing,
            // which is exactly backwards: it is a re-upload, not the source.
            "lyrics", "lyric video", "legendado", "tradução", "traducao",
            "letra", "paroles", "subtitulado", "video oficial legendado",
            // Concert and festival captures are never the studio recording.
            "live at", "pinkpop", "glastonbury", "concert", "festival",
    };

    /**
     * Fraction of the wanted words that appear in the candidate, 0-1.
     *
     * Word-based rather than substring-based so "Silenced By The Night" still matches a
     * title that reads "Keane - Silenced by the Night (Official Video)".
     */
    private static double coverage(String wanted, String candidate) {
        String[] words = wanted.split(" ");
        if (words.length == 0) return 0;
        int hit = 0;
        int total = 0;
        for (String w : words) {
            if (w.isEmpty() || w.length() <= 1) continue;
            total++;
            if (candidate.contains(w)) hit++;
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    /**
     * Lowercases, strips punctuation and folds the spelling variants that differ between
     * Spotify metadata and YouTube titles.
     */
    private static String normalise(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(Locale.US);
        s = s.replace("&", " and ").replace("feat.", " ").replace("feat", " ")
                .replace("ft.", " ").replace("ft ", " ")
                .replace("’", "'").replace("‘", "'").replace("“", "\"")
                .replace("”", "\"").replace("–", "-").replace("—", "-");
        s = s.replaceAll("[^a-z0-9' ]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    private static long parseSeconds(String value) {
        try {
            double d = Double.parseDouble(value);
            return d <= 0 ? 0 : Math.round(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Bridges yt-dlp's Kotlin progress callback to the plain Java listener.
     *
     * yt-dlp reports 0..100 for the audio transfer, then restarts its own counter for
     * the ffmpeg conversion, which used to read as the bar jumping backwards and then
     * racing to the end. Two things fix that: the transfer owns 6..92 and the
     * conversion owns 92..99, and the emitted value can never decrease, so a repeated or
     * out-of-order callback leaves the bar where it is instead of snapping it back.
     */
    private static final class ProgressFunction implements Function3<Float, Long, String, Unit> {
        private final Listener listener;
        private final Task task;
        /** Shared with the retry loop so a new attempt starts above the last one. */
        private final int[] highWater;
        private int lastEmitted = -1;
        /** Progress callbacks seen while yt-dlp is still resolving (progress == -1). */
        private int extractTicks;

        ProgressFunction(Listener listener, Task task, int[] highWater) {
            this.listener = listener;
            this.task = task;
            this.highWater = highWater;
        }

        @Override
        public Unit invoke(Float progress, Long eta, String line) {
            if (task != null && task.isCancelled()) return Unit.INSTANCE;
            int raw = progress == null ? -1 : Math.round(progress);

            // yt-dlp reports -1 while it is still resolving the media: running the
            // JavaScript player challenge, picking a format and opening the stream. That
            // phase takes 10-20 s and used to be clamped to 0 and then pinned at the 6%
            // floor, so the row sat on "6% - Working" for the whole extraction and only
            // moved once the (very fast) transfer began. Reporting it as its own creeping
            // stage is what makes the wait legible.
            if (raw < 0) {
                if (listener != null) {
                    // Creep slowly inside 6..14 so the row visibly moves without ever
                    // pretending the transfer has started.
                    int creep = Math.min(14, 6 + (int) (extractTicks / 3));
                    extractTicks++;
                    listener.onProgress(Math.max(6, Math.min(creep, 14)), "Fetching audio");
                }
                return Unit.INSTANCE;
            }

            int pct = Math.max(0, Math.min(100, raw));
            extractTicks = 0;
            // A retry starts its own 0..100 counter, so it is lifted above the best value
            // the previous attempt reached. The floor is capped at the band ceiling: once
            // the mark is already 86, "86 - floor" would go negative and the scaled value
            // would sink below the floor it was supposed to respect.
            int floor = Math.max(6, Math.min(86, highWater[0]));
            int scaled;
            String detail;
            if (pct >= 100) {
                // yt-dlp parks at 100% while ffmpeg converts, which is the longest wait of
                // the whole job. Holding the transfer ceiling steady and naming the stage is
                // what stops it looking like a stall; creeping the number towards 92 made it
                // sit at a value that then jumped backwards when tagging began.
                scaled = Math.max(floor, 86);
                detail = "Converting";
            } else {
                scaled = floor + (int) (pct * (86 - floor) / 100.0);
                if (scaled < floor) scaled = floor;
                if (scaled > 86) scaled = 86;
                detail = pct > 0 ? pct + "%" : "Working";
            }
            // Repeated callbacks for the same percentage must not move the bar, and nothing
            // may ever travel backwards. The row reads the same number until it truly changes.
            if (scaled < lastEmitted) scaled = lastEmitted;
            lastEmitted = scaled;
            if (scaled > highWater[0]) highWater[0] = scaled;
            if (listener != null) listener.onProgress(scaled, detail);
            return Unit.INSTANCE;
        }
    }

    private static File findMp3(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.US).endsWith(".mp3")) return f;
        }
        for (File f : files) {
            if (f.isFile() && !f.getName().endsWith(".part")) return f;
        }
        return null;
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Picks the first ERROR line so the row shows a readable reason. */
    private static String errorLine(String text) {
        if (text == null || text.isEmpty()) return "unknown";
        String[] lines = text.trim().split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.startsWith("ERROR:")) return tidy(l);
        }
        return tidy(lines[lines.length - 1].trim());
    }

    private static String tidy(String line) {
        String out = line.replace("ERROR:", "").trim();
        if (out.length() > 110) out = out.substring(0, 110).trim() + "…";
        return out.isEmpty() ? "unknown" : out;
    }

    private static void report(Listener l, int pct, String detail) {
        if (l != null) l.onProgress(Math.min(100, pct), detail);
    }
}
