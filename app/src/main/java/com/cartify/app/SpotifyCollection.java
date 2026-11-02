package com.cartify.app;

import java.util.ArrayList;
import java.util.List;

/** A resolved Spotify link: one track, or a playlist/album with its tracks. */
public class SpotifyCollection {

    public String type = "track";
    public String name = "";
    public String owner = "";
    public String coverUrl = "";
    public String sourceUrl = "";
    public final List<Track> tracks = new ArrayList<>();

    public boolean isPlaylist() {
        return "playlist".equals(type) || "album".equals(type);
    }
}
