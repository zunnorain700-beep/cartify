package com.cartify.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves public Spotify share links without any login or API key.
 *
 * Spotify's public embed page (open.spotify.com/embed/...) ships its own JSON payload in a
 * __NEXT_DATA__ script tag. For playlists that payload contains the full track list
 * (title / artist / duration / artwork), which is exactly the metadata Cartify needs.
 */
public final class SpotifyResolver {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:open\\.spotify\\.com/(?:intl-[a-z-]+/)?|spotify:)(track|playlist|album)(?:/|:)([A-Za-z0-9]{10,})",
            Pattern.CASE_INSENSITIVE);

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private SpotifyResolver() {
    }

    public static boolean looksLikeSpotify(String text) {
        return text != null && URL_PATTERN.matcher(text).find();
    }

    public static SpotifyCollection resolve(String input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Empty link");
        Matcher m = URL_PATTERN.matcher(input);
        if (!m.find()) {
            throw new IllegalArgumentException("That is not a Spotify track or playlist link");
        }
        String type = m.group(1).toLowerCase();
        String id = m.group(2);

        String html = httpGet("https://open.spotify.com/embed/" + type + "/" + id);
        JSONObject next = extractNextData(html);
        if (next == null) throw new IllegalStateException("Spotify page did not contain any data");

        JSONObject entity = next.getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONObject("state")
                .getJSONObject("data")
                .getJSONObject("entity");

        SpotifyCollection out = new SpotifyCollection();
        out.type = entity.optString("type", type);
        out.sourceUrl = "https://open.spotify.com/" + type + "/" + id;
        out.name = firstNonEmpty(entity.optString("title"), entity.optString("name"), "Spotify " + type);
        out.coverUrl = coverOf(entity);

        JSONArray list = entity.optJSONArray("trackList");
        if (list != null && list.length() > 0) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row == null) continue;
                Track t = new Track();
                t.title = firstNonEmpty(row.optString("title"), row.optString("name"));
                t.artist = firstNonEmpty(row.optString("subtitle"), artistsOf(row));
                t.durationMs = row.optLong("duration");
                String uri = row.optString("uri");
                if (uri.startsWith("spotify:track:")) t.id = uri.substring("spotify:track:".length());
                if (t.id.isEmpty()) t.id = row.optString("uid");
                t.coverUrl = firstNonEmpty(coverOf(row), out.coverUrl);
                if (!t.title.isEmpty()) out.tracks.add(t);
            }
        } else {
            Track t = new Track();
            t.id = entity.optString("id");
            t.title = out.name;
            t.artist = firstNonEmpty(artistsOf(entity), entity.optString("subtitle"));
            t.album = entity.optString("albumOfTrack");
            t.durationMs = entity.optLong("duration");
            t.coverUrl = out.coverUrl;
            t.pageUrl = out.sourceUrl;
            out.tracks.add(t);
        }

        if (out.tracks.isEmpty()) throw new IllegalStateException("No tracks found in that link");
        return out;
    }

    /**
     * The embed payload only carries collection-level artwork, so every song in an
     * album or playlist would show the same cover. Spotify's public oEmbed endpoint
     * returns the real cover for a single track id.
     *
     * This runs in the background and never blocks {@link #resolve}: a 50 track
     * playlist used to stall the whole screen on fifty sequential lookups before any
     * row appeared. The list now opens instantly on the collection cover and each row
     * upgrades itself as its own artwork arrives.
     */
    public static void attachTrackArtwork(SpotifyCollection collection, Runnable onChanged) {
        if (collection == null || collection.tracks.size() <= 1) return;
        // One thread, not eight. Eight parallel lookups is what got the app rate limited:
        // a 100-track playlist fired 100 requests in a couple of seconds. Artwork is
        // cosmetic, so it can arrive serially over a few seconds instead of all at once.
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            int failures = 0;
            for (Track t : collection.tracks) {
                if (t.id == null || t.id.isEmpty()) continue;
                // Once Spotify starts refusing, stop asking. Continuing would deepen the
                // block and the next resolve would fail too, which is far worse than
                // plain covers.
                if (failures >= 3) break;
                String url = oEmbedThumbnail(t.id);
                if (url.isEmpty()) {
                    failures++;
                    continue;
                }
                failures = 0;
                t.coverUrl = url;
                if (onChanged != null) main.post(onChanged);
            }
        });
        pool.shutdown();
    }

    /** @return the cover art URL for a track id, or "" when it cannot be resolved. */
    private static String oEmbedThumbnail(String trackId) {
        try {
            String body = httpGet("https://open.spotify.com/oembed?url=https://open.spotify.com/track/" + trackId);
            JSONObject o = new JSONObject(body);
            String thumb = o.optString("thumbnail_url");
            return thumb == null ? "" : thumb.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Artist names from either payload shape.
     *
     * A playlist row carries "artists" as a plain array, while a single track entity
     * carries it as an object with an "items" list of profiles. Reading only the array
     * form left single-track rows with a blank artist, which then downloaded the wrong
     * upload because the search query had no artist in it.
     */
    private static String artistsOf(JSONObject o) {
        JSONArray arr = o.optJSONArray("artists");
        if (arr == null) {
            // A single track entity uses an object here. It is either a list of
            // {profile:{name}} entries or, most often, one {name, uri} pair.
            JSONObject holder = o.optJSONObject("artists");
            if (holder == null) return "";
            JSONArray items = holder.optJSONArray("items");
            if (items == null) {
                String solo = firstNonEmpty(holder.optString("name"), holder.optString("title"));
                return solo;
            }
            arr = items;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            JSONObject profile = a.optJSONObject("profile");
            String n = profile == null
                    ? firstNonEmpty(a.optString("name"), a.optString("title"))
                    : firstNonEmpty(profile.optString("name"), a.optString("name"));
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    /** Walks the several shapes Spotify uses for artwork. */
    private static String coverOf(JSONObject o) {
        JSONObject coverArt = o.optJSONObject("coverArt");
        if (coverArt != null) {
            String u = biggestFromArray(coverArt.optJSONArray("sources"));
            if (!u.isEmpty()) return u;
        }
        JSONObject visual = o.optJSONObject("visualIdentity");
        if (visual != null) {
            String u = biggestFromArray(visual.optJSONArray("image"));
            if (!u.isEmpty()) return u;
        }
        String u = biggestFromArray(o.optJSONArray("images"));
        if (!u.isEmpty()) return u;
        JSONObject album = o.optJSONObject("albumOfTrack");
        if (album != null) {
            JSONObject albumCover = album.optJSONObject("coverArt");
            if (albumCover != null) {
                String fromAlbum = biggestFromArray(albumCover.optJSONArray("sources"));
                if (!fromAlbum.isEmpty()) return fromAlbum;
            }
        }
        return "";
    }

    private static String biggestFromArray(JSONArray arr) {
        if (arr == null) return "";
        String best = "";
        int bestW = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject im = arr.optJSONObject(i);
            if (im == null) continue;
            String url = im.optString("url");
            if (url.isEmpty()) continue;
            int w = im.optInt("maxWidth", im.optInt("width", 0));
            if (w >= bestW) {
                bestW = w;
                best = url;
            }
        }
        return best;
    }

    private static JSONObject extractNextData(String html) {
        int marker = html.indexOf("id=\"__NEXT_DATA__\"");
        if (marker < 0) return null;
        int start = html.indexOf('>', marker);
        int end = html.indexOf("</script>", start);
        if (start < 0 || end < 0) return null;
        try {
            return new JSONObject(html.substring(start + 1, end));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Minimum spacing between Spotify requests, process-wide.
     *
     * Spotify answers a burst with HTTP 429 and then keeps refusing for a while, which is
     * what made "paste a link" fail outright after a few resolves. Every request now waits
     * its turn behind this gate: roughly four per second is enough for the UI and far
     * below anything that looks like abuse.
     */
    private static final long MIN_GAP_MS = 250L;
    private static final Object GATE = new Object();
    private static long lastRequestAt = 0L;
    /** Set when Spotify has told us to back off; cleared by a successful request. */
    private static volatile long coolDownUntil = 0L;

    private static void awaitTurn() throws InterruptedException {
        synchronized (GATE) {
            long now = System.currentTimeMillis();
            long wakeAt = Math.max(now, Math.max(lastRequestAt + MIN_GAP_MS, coolDownUntil));
            long wait = wakeAt - now;
            if (wait > 0) Thread.sleep(Math.min(wait, 30000L));
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /**
     * Reads Retry-After when Spotify sends one, otherwise backs off for a few seconds.
     * The cooldown is shared by every caller so one 429 pauses the whole resolver instead
     * of each thread discovering the block separately.
     */
    private static void noteRateLimited(HttpURLConnection c) {
        long seconds = 5L;
        try {
            String retryAfter = c.getHeaderField("Retry-After");
            if (retryAfter != null) seconds = Math.max(1L, Long.parseLong(retryAfter.trim()));
        } catch (Exception ignored) {
        }
        // Cap it: a misreported header should not freeze the screen for an hour.
        seconds = Math.min(seconds, 60L);
        coolDownUntil = System.currentTimeMillis() + seconds * 1000L;
    }

    private static String httpGet(String url) throws Exception {
        return httpGet(url, true);
    }

    private static String httpGet(String url, boolean mayRetry) throws Exception {
        return httpGet(url, mayRetry, 0);
    }

    /**
     * Retries DNS resolution a few times before giving up.
     *
     * On shared networks the resolver intermittently answers NXDOMAIN for hosts that
     * resolve fine seconds later; that surfaced as "Could not read that link" for a link
     * the browser opened without trouble. A lookup failure says nothing about the link,
     * only about the moment, so it is worth two more tries after a short pause.
     */
    private static String httpGet(String url, boolean mayRetry, int dnsRetry) throws Exception {
        awaitTurn();
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
        } catch (java.net.UnknownHostException e) {
            if (dnsRetry >= 3) {
                throw new IllegalStateException(
                        "Network could not find Spotify. Check the connection and try again.", e);
            }
            Thread.sleep(700L * (dnsRetry + 1));
            return httpGet(url, mayRetry, dnsRetry + 1);
        }
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        // The embed page is ~1.5 MB of JSON; gzip cuts it to a fraction of that and is
        // the single biggest win when a long playlist is pasted.
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setInstanceFollowRedirects(true);
        try {
            c.connect();
        } catch (java.net.UnknownHostException e) {
            c.disconnect();
            if (dnsRetry >= 3) {
                throw new IllegalStateException(
                        "Network could not find Spotify. Check the connection and try again.", e);
            }
            Thread.sleep(700L * (dnsRetry + 1));
            return httpGet(url, mayRetry, dnsRetry + 1);
        }
        try {
            int code = c.getResponseCode();
            if (code == 429) {
                noteRateLimited(c);
                if (mayRetry) {
                    // One patient retry after the cooldown. This is the difference between
                    // "could not read that link" and the list simply appearing a few
                    // seconds later.
                    c.disconnect();
                    awaitTurn();
                    return httpGet(url, false);
                }
                throw new IllegalStateException(
                        "Spotify is rate limiting this device. Wait a moment and try again.");
            }
            coolDownUntil = 0L;
            InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (raw == null) throw new IllegalStateException("Spotify replied HTTP " + code);
            boolean gz = "gzip".equalsIgnoreCase(c.getContentEncoding());
            InputStream in = gz ? new java.util.zip.GZIPInputStream(raw) : raw;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder(gz ? 65536 : 262144);
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            if (code >= 400) throw new IllegalStateException("Spotify replied HTTP " + code);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty() && !"null".equals(v)) return v.trim();
        }
        return "";
    }
}
