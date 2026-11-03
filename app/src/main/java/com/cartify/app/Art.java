package com.cartify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album artwork for both Spotify-resolved tracks (remote URL) and local files
 * (embedded ID3 picture). Shared by every list, the mini player and the full player.
 */
public final class Art {

    private static final String TAG = "Cartify";
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    /**
     * Artwork keys that produced no bitmap.
     *
     * Bounded: this used to grow for the life of the process, and every song without
     * embedded art (or with a dead cover URL) added an entry that was never removed. On a
     * device with a large library that is a slow, permanent leak. The cap is far above the
     * number of covers on screen, so a miss is still remembered for as long as it matters.
     */
    private static final int MISSING_MAX = 600;
    private static final Set<String> MISSING = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > MISSING_MAX;
                }
            });
    private static final Map<String, List<ImageView>> WAITING = new HashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Art() {
    }

    public static String key(Track t) {
        if (t == null) return "";
        if (t.coverUrl != null && !t.coverUrl.isEmpty()) return "url:" + t.coverUrl;
        if (t.localUri != null && !t.localUri.isEmpty()) return "file:" + t.localUri;
        return "";
    }

    public static Bitmap cached(Track t) {
        String k = key(t);
        return k.isEmpty() ? null : CACHE.get(k);
    }

    /** Synchronously returns a previously decoded bitmap, or null. */
    public static Bitmap peek(Track t) {
        return cached(t);
    }

    /**
     * Blocking full-resolution fetch used by the player backdrop blur.
     * Must be called off the main thread.
     */
    public static Bitmap full(Context context, Track t) {
        String k = key(t);
        if (k.isEmpty()) return null;
        Bitmap hit = CACHE.get(k);
        if (hit != null) return hit;
        Context app = context.getApplicationContext();
        Bitmap b = null;
        if (t.coverUrl != null && !t.coverUrl.isEmpty()) b = Net.bitmap(t.coverUrl);
        if (b == null && t.localUri != null && !t.localUri.isEmpty()) b = embedded(app, t.localUri);
        if (b != null) CACHE.put(k, b);
        return b;
    }

    public interface Loaded {
        void onArtwork(boolean found);
    }

    public static void load(Context context, Track track, ImageView view) {
        load(context, track, view, null);
    }

    public static void load(Context context, Track track, ImageView view, Loaded callback) {
        String k = key(track);
        // A recycled row that is re-bound to the same track must not blank first. The old
        // code cleared unconditionally, so every notifyDataSetChanged (and the library
        // list gets one on every playback callback) wiped the cover and put it back on the
        // next frame. That is the blink: the row never changed, but its artwork was redrawn
        // from scratch each time.
        if (k.equals(view.getTag()) && view.getDrawable() != null) {
            if (callback != null) callback.onArtwork(!MISSING.contains(k));
            return;
        }
        view.setTag(k);
        view.setImageDrawable(null);
        if (k.isEmpty()) {
            if (callback != null) callback.onArtwork(false);
            return;
        }
        Bitmap hit = CACHE.get(k);
        if (hit != null) {
            view.setImageBitmap(hit);
            if (callback != null) callback.onArtwork(true);
            return;
        }
        if (MISSING.contains(k)) {
            if (callback != null) callback.onArtwork(false);
            return;
        }
        synchronized (WAITING) {
            List<ImageView> list = WAITING.get(k);
            if (list == null) {
                list = new ArrayList<>();
                WAITING.put(k, list);
            }
            if (!list.contains(view)) list.add(view);
        }
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(k)) return;
        }
        final Context app = context.getApplicationContext();
        final String url = track.coverUrl;
        final String uri = track.localUri;
        POOL.execute(() -> {
            Bitmap b = null;
            if (url != null && !url.isEmpty()) b = Net.bitmap(url);
            if (b == null && uri != null && !uri.isEmpty()) b = embedded(app, uri);
            final Bitmap result = b;
            List<ImageView> targets;
            synchronized (WAITING) {
                targets = WAITING.remove(k);
            }
            synchronized (IN_FLIGHT) {
                IN_FLIGHT.remove(k);
            }
            if (result == null) {
                MISSING.add(k);
                if (callback != null) MAIN.post(() -> callback.onArtwork(false));
                return;
            }
            CACHE.put(k, result);
            if (callback != null) MAIN.post(() -> callback.onArtwork(true));
            if (targets == null) return;
            for (final ImageView v : targets) {
                MAIN.post(() -> {
                    if (k.equals(v.getTag())) v.setImageBitmap(result);
                });
            }
        });
    }

    /**
     * Reads the artwork of an MP3 on the device.
     *
     * MediaMetadataRetriever hands back the APIC payload on some Android builds in a
     * form BitmapFactory cannot decode, so the ID3 frame is parsed directly first.
     */
    private static Bitmap embedded(Context context, String uri) {
        byte[] pic = apicFromId3(context, uri);
        if (pic == null) pic = fromRetriever(context, uri);
        if (pic == null) return null;
        // Decode at full resolution: artwork is shown full-bleed in the player, and
        // subsampling it here is what made covers look soft and blocky.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
    }

    /** Walks the ID3v2 tag and returns the front-cover image bytes. */
    private static byte[] apicFromId3(Context context, String uri) {
        java.io.InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) return null;
            byte[] header = new byte[10];
            if (readFully(in, header) != 10) return null;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return null;
            int major = header[3] & 0xFF;
            int flags = header[5] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            if ((flags & 0x80) != 0) return null; // unsynchronised tag: not produced by us
            byte[] tag = new byte[tagSize];
            if (readFully(in, tag) != tagSize) return null;

            int idLen = major == 2 ? 3 : 4;
            int headLen = major == 2 ? 6 : 10;
            int pos = 0;
            while (pos + headLen <= tag.length) {
                String id = new String(tag, pos, idLen, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == '\u0000') break;
                int size;
                if (major == 2) {
                    size = ((tag[pos + 3] & 0xFF) << 16) | ((tag[pos + 4] & 0xFF) << 8)
                            | (tag[pos + 5] & 0xFF);
                } else if (major == 4) {
                    size = ((tag[pos + 4] & 0x7F) << 21) | ((tag[pos + 5] & 0x7F) << 14)
                            | ((tag[pos + 6] & 0x7F) << 7) | (tag[pos + 7] & 0x7F);
                } else {
                    size = ((tag[pos + 4] & 0xFF) << 24) | ((tag[pos + 5] & 0xFF) << 16)
                            | ((tag[pos + 6] & 0xFF) << 8) | (tag[pos + 7] & 0xFF);
                }
                int dataStart = pos + headLen;
                if (size <= 0 || dataStart + size > tag.length) break;
                if (id.equals("APIC") || id.equals("PIC")) {
                    return pictureBytes(tag, dataStart, size, major == 2);
                }
                pos = dataStart + size;
            }
        } catch (Exception e) {
            Log.w(TAG, "id3 art parse failed for " + uri, e);
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Strips the APIC text-encoding/mime/type/description header off the frame. */
    private static byte[] pictureBytes(byte[] tag, int start, int size, boolean v22) {
        int p = start;
        int end = start + size;
        if (p >= end) return null;
        p++; // text encoding
        if (v22) {
            if (p + 3 > end) return null;
            p += 3; // image format, 3 chars
        } else {
            while (p < end && tag[p] != 0) p++; // mime, null terminated
            p++;
        }
        p++; // picture type
        while (p < end && tag[p] != 0) p++; // description, null terminated
        p++;
        // Older Cartify builds wrote a stray null here; skip padding before the image.
        while (p < end && tag[p] == 0) p++;
        if (p >= end) return null;
        byte[] image = new byte[end - p];
        System.arraycopy(tag, p, image, 0, image.length);
        return image;
    }

    private static int readFully(java.io.InputStream in, byte[] buf) throws java.io.IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    private static byte[] fromRetriever(Context context, String uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, Uri.parse(uri));
            return r.getEmbeddedPicture();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
