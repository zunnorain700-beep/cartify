package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics.
 *
 * Source is LRCLIB (lrclib.net): public, keyless, and returns LRC lines with real
 * timestamps, which is what lets the player highlight each line as the song plays.
 *
 * Matching is deliberately strict. A title-only lookup will happily return a different
 * song that shares a name ("Real Bad Man" has a dozen unrelated versions on the
 * database), and a wrong lyric sheet is worse than none, so a result is only accepted
 * when its artist agrees with ours and its length fits the file we are playing.
 */
public final class Lyrics {

    private static final String TAG = "Cartify";
    // v3: v2 cached a French rap sheet under a Punjabi track, because the old matcher
    // accepted any same-titled song whose length happened to be close. The poisoned
    // entries are simply left behind in the old preference file.
    private static final String PREFS = "cartify_lyrics_v3";
    private static final String UA = "Cartify/1.0 (Android music player)";
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** Words that carry no identifying weight when comparing artist names. */
    private static final Set<String> ARTIST_NOISE = new HashSet<>(Arrays.asList(
            "the", "and", "feat", "ft", "featuring", "with", "vs", "x", "music",
            "official", "records", "record", "prod", "by"));

    /** One lyric line with its start time. */
    public static class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private Lyrics() {
    }

    private static String key(String artist, String title) {
        return (artist == null ? "" : artist.trim().toLowerCase(Locale.US))
                + "|" + (title == null ? "" : title.trim().toLowerCase(Locale.US));
    }

    /** @return cached LRC text, "" when nothing was found, or null when never looked up. */
    public static String cached(Context context, String artist, String title) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(artist, title), null);
    }

    /** Drops a cached answer so the next lookup asks the service again. */
    public static void forget(Context context, String artist, String title) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key(artist, title)).apply();
    }

    /**
     * Fetches and caches synced lyrics; returns the LRC text or null.
     *
     * Only a definitive answer is cached. A temporary failure (server busy, no network)
     * must not be remembered, otherwise one 503 would hide the lyrics for that song
     * forever.
     */
    public static String fetch(Context context, Track track) {
        if (track == null) return null;
        String existing = cached(context, track.artist, track.title);
        if (existing != null) return existing.isEmpty() ? null : existing;

        Result result = request(track);
        if (!result.definitive) return result.lrc;

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key(track.artist, track.title), result.lrc == null ? "" : result.lrc)
                .apply();
        return result.lrc;
    }

    /** A lookup outcome: the lyrics, and whether it is worth remembering. */
    private static final class Result {
        final String lrc;
        final boolean definitive;

        Result(String lrc, boolean definitive) {
            this.lrc = lrc;
            this.definitive = definitive;
        }
    }

    /** Parses LRC text into ordered, timestamped lines. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return out;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TIME.matcher(raw);
            long time = -1;
            int end = 0;
            while (m.find()) {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                long fraction = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    while (f.length() < 3) f = f + "0";
                    fraction = Long.parseLong(f.substring(0, 3));
                }
                long t = minutes * 60000L + seconds * 1000L + fraction;
                if (time < 0) time = t;
                end = m.end();
            }
            if (time < 0) continue;
            String text = raw.substring(end).trim();
            if (text.isEmpty()) continue;
            out.add(new Line(time, text));
        }
        out.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    /** Index of the line that should be highlighted at {@code positionMs}, or -1. */
    public static int activeLine(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs + 120) active = i;
            else break;
        }
        return active;
    }

    private static Result request(Track track) {
        // Artist-scoped first: it is the only query that can tell same-titled songs apart.
        Result byArtist = search(track, true);
        if (byArtist.lrc != null) return byArtist;
        if (!byArtist.definitive) return byArtist;   // transient failure: do not cache
        // A title-only pass catches featured-artist strings that never match the
        // database, but it still has to pass the artist check inside search().
        Result byTitle = search(track, false);
        if (byTitle.lrc != null) return byTitle;
        return new Result(null, byTitle.definitive);
    }

    /**
     * Queries LRCLIB and keeps the synced result that really is this song.
     *
     * @param withArtist when false only the title is sent, which catches featured-artist
     *                   strings that never match the database; the results are still
     *                   filtered on artist and length locally.
     */
    private static Result search(Track track, boolean withArtist) {
        StringBuilder url = new StringBuilder("https://lrclib.net/api/search?track_name=")
                .append(enc(cleanTitle(track.title)));
        if (withArtist && track.artist != null && !track.artist.isEmpty()) {
            url.append("&artist_name=").append(enc(track.artist.split(",")[0].trim()));
        }

        // LRCLIB sheds load with 503s; a couple of retries turns most of those into hits.
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpURLConnection c = null;
            try {
                if (attempt > 0) Thread.sleep(1500L * attempt);
                c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 503 || code == 429 || code >= 500) {
                    continue;   // transient: try again, never cache
                }
                if (code >= 400) {
                    return new Result(null, true);   // real "nothing here" answer
                }

                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream();
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONArray results = new JSONArray(sb.toString());

                String best = null;
                long bestCost = Long.MAX_VALUE;
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.optJSONObject(i);
                    if (o == null) continue;
                    String synced = o.optString("syncedLyrics", "");
                    if (synced.trim().isEmpty()) continue;
                    if (!isRealLyrics(synced)) continue;
                    // The decisive filter: a different artist means a different song.
                    if (!sameArtist(track.artist, o.optString("artistName", ""))) continue;
                    long durMs = Math.round(o.optDouble("duration", 0) * 1000d);
                    if (!lengthFits(track.durationMs, durMs)) continue;
                    if (!timingFits(synced, track.durationMs)) continue;

                    long cost = track.durationMs > 0 && durMs > 0
                            ? Math.abs(durMs - track.durationMs)
                            : 0L;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = synced;
                    }
                }
                return new Result(best, true);
            } catch (Exception e) {
                Log.d(TAG, "lyrics lookup failed for " + track.title + " (attempt " + attempt + ")");
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return new Result(null, false);   // could not reach the service: do not cache
    }

    /**
     * True when the database entry is by this artist.
     *
     * Compared as word sets so "Karan Aujla, DIVINE" still matches "Karan Aujla" and
     * "Ed Sheeran" matches "Ed Sheeran feat. Beyoncé", while "L2B" never matches a
     * Punjabi track that merely shares its title.
     */
    private static boolean sameArtist(String ours, String theirs) {
        Set<String> a = artistWords(ours);
        if (a.isEmpty()) return true;   // no artist metadata: nothing to check against
        Set<String> b = artistWords(theirs);
        if (b.isEmpty()) return false;
        for (String w : a) {
            if (b.contains(w)) return true;
        }
        return false;
    }

    private static Set<String> artistWords(String name) {
        Set<String> out = new HashSet<>();
        if (name == null) return out;
        String cleaned = name.toLowerCase(Locale.US)
                .replaceAll("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        for (String w : cleaned.split(" ")) {
            if (w.length() < 2) continue;
            if (ARTIST_NOISE.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    /** Rejects a version whose length is nowhere near the file we are playing. */
    private static boolean lengthFits(long trackMs, long resultMs) {
        if (trackMs <= 0 || resultMs <= 0) return true;   // nothing to compare
        return Math.abs(resultMs - trackMs) <= Math.max(25000L, trackMs / 5L);
    }

    /**
     * Rejects a sheet whose timestamps cannot belong to this file.
     *
     * A live or extended cut of the same song drifts by a few seconds, but a sheet that
     * starts after the song is nearly over, or runs far past its end, is another
     * recording entirely.
     */
    private static boolean timingFits(String lrc, long trackMs) {
        if (trackMs <= 0) return true;
        List<Line> lines = parse(lrc);
        if (lines.isEmpty()) return false;
        long first = lines.get(0).timeMs;
        long last = lines.get(lines.size() - 1).timeMs;
        if (first > Math.max(45000L, trackMs / 2L)) return false;
        return last <= trackMs + 60000L;
    }

    /** Filters placeholder rows such as the single line "[00:00.00]probe". */
    private static boolean isRealLyrics(String lrc) {
        List<Line> lines = parse(lrc);
        if (lines.isEmpty()) return false;
        int real = 0;
        for (Line l : lines) {
            if (l.text.replaceAll("[^A-Za-z]", "").length() > 1) real++;
        }
        return real >= 4;
    }

    /** Strips the bracketed suffixes Spotify adds, which lyrics sites do not use. */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title;
        t = t.replaceAll("(?i)\\s*[\\-\\u2013]\\s*(remaster(ed)?|remix|live|version|edit|mix|mono|stereo|acoustic)\\b.*$", "");
        t = t.replaceAll("\\([^)]*\\)", " ");
        t = t.replaceAll("\\[[^]]*]", " ");
        t = t.replaceAll("(?i)\\s*(feat\\.?|ft\\.?|featuring)\\s+.*$", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return v;
        }
    }
}
