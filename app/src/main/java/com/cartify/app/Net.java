package com.cartify.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny bitmap downloader with an in-memory cache (no image library needed). */
public final class Net {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Net() {
    }

    public static byte[] bytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Cartify/1.0 (Android)");
            // Covers are JPEGs; gzip does not help images, but keep-alive reuses the
            // TLS connection for the next cover instead of a fresh handshake each time.
            c.setRequestProperty("Connection", "keep-alive");
            if (c.getResponseCode() >= 400) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static Bitmap bitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap cached = CACHE.get(url);
        if (cached != null) return cached;
        byte[] raw = bytes(url);
        if (raw == null) return null;
        // Full resolution: Spotify covers are ~640px and are drawn full width in the player.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b != null) CACHE.put(url, b);
        return b;
    }
}
