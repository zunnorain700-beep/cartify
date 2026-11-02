package com.cartify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves a playlist's artwork.
 *
 * A custom cover wins; otherwise the first song that has embedded art is used, so a
 * playlist looks like its music without asking the user for a picture. Decoding happens
 * on a shared pool and the view tag guards against a recycled row showing the wrong cover.
 */
public final class PlaylistArt {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private PlaylistArt() {
    }

    public interface Loaded {
        void onArtwork(Bitmap bitmap);
    }

    public static String key(Playlist p) {
        if (p == null) return "";
        return p.id + ":" + (p.coverPath == null ? "" : p.coverPath)
                + ":" + (p.songs.isEmpty() ? "" : p.songs.get(0).uri);
    }

    /** Blocking decode; call off the main thread. */
    public static Bitmap resolve(Context context, Playlist p) {
        if (p == null) return null;
        if (p.coverPath != null && !p.coverPath.isEmpty()) {
            File f = new File(p.coverPath);
            if (f.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (b != null) return b;
            }
        }
        Context app = context.getApplicationContext();
        for (Playlist.Entry e : p.songs) {
            Track probe = e.toTrack();
            Bitmap b = Art.cached(probe);
            if (b == null) b = Art.full(app, probe);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * Loads into {@code cover} and hides {@code fallback} when art is found.
     * The tag is set immediately so a recycled view cannot receive stale art.
     */
    public static void load(Context context, Playlist p, ImageView cover, View fallback) {
        final String k = key(p);
        cover.setTag(k);
        cover.setImageDrawable(null);
        if (fallback != null) fallback.setVisibility(View.VISIBLE);
        POOL.execute(() -> {
            final Bitmap b = resolve(context, p);
            if (b == null) return;
            cover.post(() -> {
                if (!k.equals(cover.getTag())) return;
                cover.setImageBitmap(b);
                cover.setAlpha(0f);
                cover.animate().alpha(1f).setDuration(200).start();
                if (fallback != null) fallback.setVisibility(View.GONE);
            });
        });
    }
}
