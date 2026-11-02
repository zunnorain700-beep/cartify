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
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        for (final Track t : collection.tracks) {
            if (t.id == null || t.id.isEmpty()) continue;
            pool.execute(() -> {
                String url = oEmbedThumbnail(t.id);
                if (url.isEmpty()) return;
                t.coverUrl = url;
                if (onChanged != null) main.post(onChanged);
            });
        }
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

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
            int code = c.getResponseCode();
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
