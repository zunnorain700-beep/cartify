package com.cartify.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user-made playlist: a name, an optional custom cover and an ordered list of songs.
 *
 * Songs are stored by their own metadata rather than by a MediaStore id, so a playlist
 * keeps working across reinstalls and rescans. The local uri is what actually plays.
 */
public class Playlist {

    /** One song inside a playlist. */
    public static class Entry {
        public String uri = "";
        public String title = "";
        public String artist = "";
        public long durationMs = 0L;

        public Entry() {
        }

        public Entry(String uri, String title, String artist, long durationMs) {
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }

        public Track toTrack() {
            Track t = new Track(title, artist, "");
            t.localUri = uri;
            t.durationMs = durationMs;
            t.status = Track.DONE;
            return t;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("uri", uri);
                o.put("title", title);
                o.put("artist", artist);
                o.put("duration", durationMs);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.uri = o.optString("uri");
            e.title = o.optString("title");
            e.artist = o.optString("artist");
            e.durationMs = o.optLong("duration");
            return e;
        }
    }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    /** Absolute path to a user-picked cover, or "" to use the first song's artwork. */
    public String coverPath = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    public final List<Entry> songs = new ArrayList<>();

    public Playlist() {
    }

    public Playlist(String name) {
        this.name = name;
    }

    public boolean contains(String uri) {
        if (uri == null) return false;
        for (Entry e : songs) if (uri.equals(e.uri)) return true;
        return false;
    }

    /** Adds a song unless it is already there. Returns true when something changed. */
    public boolean add(Track t) {
        if (t == null || t.localUri == null || t.localUri.isEmpty()) return false;
        if (contains(t.localUri)) return false;
        songs.add(new Entry(t.localUri, t.title, t.artist, t.durationMs));
        updatedAt = System.currentTimeMillis();
        return true;
    }

    public boolean removeUri(String uri) {
        for (int i = songs.size() - 1; i >= 0; i--) {
            if (songs.get(i).uri.equals(uri)) {
                songs.remove(i);
                updatedAt = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    public void moveToTop(String uri) {
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).uri.equals(uri)) {
                Entry e = songs.remove(i);
                songs.add(0, e);
                updatedAt = System.currentTimeMillis();
                return;
            }
        }
    }

    public long totalDurationMs() {
        long sum = 0;
        for (Entry e : songs) sum += Math.max(0, e.durationMs);
        return sum;
    }

    /** "12 songs · 43 min" style summary for list rows and headers. */
    public String summary() {
        String count = songs.size() + (songs.size() == 1 ? " song" : " songs");
        long mins = totalDurationMs() / 60000L;
        if (mins <= 0) return count;
        if (mins < 60) return count + " \u00b7 " + mins + " min";
        return count + " \u00b7 " + (mins / 60) + " hr " + (mins % 60) + " min";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("cover", coverPath);
            o.put("created", createdAt);
            o.put("updated", updatedAt);
            JSONArray arr = new JSONArray();
            for (Entry e : songs) arr.put(e.toJson());
            o.put("songs", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Playlist fromJson(JSONObject o) {
        Playlist p = new Playlist();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "Playlist");
        p.coverPath = o.optString("cover", "");
        p.createdAt = o.optLong("created", System.currentTimeMillis());
        p.updatedAt = o.optLong("updated", p.createdAt);
        JSONArray arr = o.optJSONArray("songs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row != null) p.songs.add(Entry.fromJson(row));
            }
        }
        return p;
    }
}
