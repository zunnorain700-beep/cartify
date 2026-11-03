package com.cartify.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app music search, so a song can be found and downloaded without leaving Cartify.
 *
 * Spotify's own search endpoint requires an authenticated token and answers 401 to anyone
 * else, and the anonymous token the web player used to hand out now returns 403. Rather
 * than ship a client id and secret inside the APK (which anyone can extract, and which
 * would break every user at once when it is revoked), search uses Deezer's public catalog
 * with iTunes as a fallback. Both are keyless, return the same fields Cartify needs —
 * title, artist, album, length, large artwork — and are far less likely to rate limit a
 * phone doing a handful of searches.
 *
 * Results are converted into the same {@link Track} objects a resolved Spotify link
 * produces, so everything downstream (matching, downloading, tagging) is unchanged.
 */
public final class MusicSearch {

    private static final String TAG = "Cartify";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    /** Deezer and iTunes both cap a page at 25 for this endpoint. */
    private static final int LIMIT = 25;

    public interface Callback {
        void onResults(List<Track> tracks);

        void onFailed(String message);
    }

    private MusicSearch() {
    }

    /** Runs {@code query} off the main thread and always calls back exactly once. */
    public static void search(final String query, final Callback callback) {
        if (query == null || query.trim().isEmpty()) {
            if (callback != null) callback.onFailed("Type something to search for");
            return;
        }
        POOL.execute(() -> {
            List<Track> out = null;
            String failure = "Search failed";
            try {
                out = searchArtistFirst(query);
            } catch (Exception e) {
                Log.w(TAG, "artist search failed", e);
                failure = describe(e);
            }
            if (out == null || out.isEmpty()) {
                try {
                    out = searchDeezer(query);
                } catch (Exception e) {
                    Log.w(TAG, "track search failed", e);
                    failure = describe(e);
                }
            }
            if (out == null || out.isEmpty()) {
                try {
                    List<Track> alt = searchItunes(query);
                    if (alt != null && !alt.isEmpty()) out = alt;
                } catch (Exception e) {
                    Log.w(TAG, "itunes search failed", e);
                }
            }
            final List<Track> result = out;
            final String message = failure;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result == null || result.isEmpty()) callback.onFailed(message);
                else callback.onResults(result);
            });
        });
    }

    /**
     * Fresh suggestions for the empty search screen.
     *
     * Uses Deezer's public chart, the closest keyless equivalent to Spotify's home feed.
     * Cached for half an hour so relaunching does not hammer the endpoint, and failures
     * stay silent: an empty home screen is better than an error dialog on launch.
     */
    public static void recommendations(final Callback callback) {
        POOL.execute(() -> {
            List<Track> out = new ArrayList<>();
            try {
                long now = System.currentTimeMillis();
                if (chartCache != null && now - chartAt < CHART_TTL_MS) {
                    out.addAll(chartCache);
                } else {
                    JSONObject root = new JSONObject(
                            httpGet("https://api.deezer.com/chart/0/tracks?limit=" + LIMIT));
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            Track t = deezerTrack(data.optJSONObject(i));
                            if (t != null) out.add(t);
                        }
                    }
                    chartCache = new ArrayList<>(out);
                    chartAt = now;
                }
            } catch (Exception e) {
                Log.w(TAG, "chart fetch failed", e);
            }
            final List<Track> result = out;
            if (callback == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (!result.isEmpty()) callback.onResults(result);
            });
        });
    }

    private static List<Track> chartCache;
    private static long chartAt = 0L;
    private static final long CHART_TTL_MS = 30 * 60 * 1000L;

    /**
     * Searches Deezer tracks and ranks them for the query the user actually typed.
     *
     * Deezer's own ordering is title-relevance, so "coldplay" surfaced songs literally
     * called "Cold Play" by unrelated artists. Asking for the artist's top tracks was
     * worse: that endpoint returned collaborations rather than the band's own hits.
     *
     * Ranking the 25 search hits locally is both cheap and far more accurate: a row by an
     * artist whose name matches the query jumps to the front, then rows whose title or
     * album matches, then everything else in Deezer's order.
     */
    private static List<Track> searchArtistFirst(String query) throws Exception {
        String url = "https://api.deezer.com/search?q="
                + URLEncoder.encode(query, "UTF-8") + "&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray data = root.optJSONArray("data");
        List<Track> out = new ArrayList<>();
        if (data == null) return out;

        String want = query.toLowerCase(Locale.US).trim();
        List<Track> exactArtist = new ArrayList<>();
        List<Track> partialArtist = new ArrayList<>();
        List<Track> rest = new ArrayList<>();

        for (int i = 0; i < data.length(); i++) {
            Track t = deezerTrack(data.optJSONObject(i));
            if (t == null) continue;
            String artist = t.artist == null ? "" : t.artist.toLowerCase(Locale.US);
            if (artist.equals(want)) exactArtist.add(t);
            else if (artist.contains(want)) partialArtist.add(t);
            else if (t.title != null && t.title.toLowerCase(Locale.US).contains(want)) partialArtist.add(t);
            else rest.add(t);
        }

        out.addAll(exactArtist);
        out.addAll(partialArtist);
        out.addAll(rest);
        return out;
    }

    // ------------------------------------------------------------------ deezer

    private static List<Track> searchDeezer(String query) throws Exception {
        String url = "https://api.deezer.com/search?q="
                + URLEncoder.encode(query, "UTF-8") + "&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray data = root.optJSONArray("data");
        List<Track> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            Track t = deezerTrack(row);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Maps one Deezer track object onto a Track, or null when it has no title. */
    private static Track deezerTrack(JSONObject row) {
        if (row == null) return null;
        String title = row.optString("title");
        if (title.isEmpty()) return null;
        JSONObject artist = row.optJSONObject("artist");
        JSONObject album = row.optJSONObject("album");

        Track t = new Track();
        t.title = title;
        t.artist = artist == null ? "" : artist.optString("name");
        t.album = album == null ? "" : album.optString("title");
        // Deezer reports whole seconds.
        t.durationMs = row.optLong("duration") * 1000L;
        if (album != null) {
            // Prefer the largest cover: rows are small but the player is full-bleed.
            String big = album.optString("cover_xl");
            if (big.isEmpty()) big = album.optString("cover_big");
            if (big.isEmpty()) big = album.optString("cover_medium");
            t.coverUrl = big;
        }
        // Deezer's own track page is a stable public reference for the song.
        t.pageUrl = row.optString("link");
        t.id = row.optString("isrc");
        return t;
    }

    // ------------------------------------------------------------------ itunes

    private static List<Track> searchItunes(String query) throws Exception {
        String url = "https://itunes.apple.com/search?term="
                + URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=" + LIMIT;
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray results = root.optJSONArray("results");
        List<Track> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("trackName");
            if (title.isEmpty()) continue;

            Track t = new Track();
            t.title = title;
            t.artist = row.optString("artistName");
            t.album = row.optString("collectionName");
            t.durationMs = row.optLong("trackTimeMillis");
            String art = row.optString("artworkUrl100");
            // iTunes hands back a 100px thumbnail; the same URL at 600px is the real cover.
            t.coverUrl = art.isEmpty() ? "" : art.replace("100x100", "600x600");
            t.pageUrl = row.optString("trackViewUrl");
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ http

        /**
     * Turns a network exception into something a person can act on.
     *
     * The raw text was shown verbatim: "unable to resolve host api.deezer.com: no
     * address associated with hostname" is true, and useless to anyone holding a
     * phone with no signal. The common cases are named; anything else keeps the
     * original wording so a real bug is still reportable.
     */
    private static String explain(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        String low = msg.toLowerCase(java.util.Locale.US);
        if (low.contains("unable to resolve host") || low.contains("unknownhost")
                || low.contains("no address associated")) {
            return "No internet connection";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "The connection timed out";
        }
        if (low.contains("econnrefused") || low.contains("connection refused")) {
            return "The music service refused the request";
        }
        return msg.isEmpty() ? "Could not reach the music service" : msg;
    }

private static String httpGet(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Cartify/1.0 (Android)");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("Server replied " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        // explain() names the cases a person can act on (no signal, timeout) and
        // passes anything else through untouched, so a genuine bug is still legible.
        return explain(e);
    }
}
