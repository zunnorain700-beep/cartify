package com.cartify.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Playlists on disk, as one JSON document in the app's private storage.
 *
 * Private storage is deliberate: a playlist is app state, not a media file, and keeping
 * it out of MediaStore means a rescan can never duplicate or drop it. Writes go to a
 * temp file first and are then renamed, so a crash mid-save cannot leave a half-written
 * document that loses every playlist.
 */
public final class PlaylistStore {

    private static final String TAG = "Cartify";
    private static final String FILE_NAME = "playlists.json";
    private static final String COVER_DIR = "playlist_covers";

    private static List<Playlist> cache;

    private PlaylistStore() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static File coverDir(Context context) {
        File dir = new File(context.getFilesDir(), COVER_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** All playlists, newest activity first. Loaded once and kept in memory. */
    public static synchronized List<Playlist> all(Context context) {
        if (cache != null) return new ArrayList<>(cache);
        List<Playlist> out = new ArrayList<>();
        File f = file(context);
        if (!f.exists()) {
            cache = out;
            return out;
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Playlist.fromJson(o));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read playlists", e);
        }
        sort(out);
        cache = out;
        return new ArrayList<>(out);
    }

    private static void sort(List<Playlist> list) {
        Collections.sort(list, new Comparator<Playlist>() {
            @Override
            public int compare(Playlist a, Playlist b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
    }

    public static synchronized Playlist byId(Context context, String id) {
        for (Playlist p : all(context)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Saves one playlist, inserting it when new. Returns false when the write failed. */
    public static synchronized boolean save(Context context, Playlist playlist) {
        if (playlist == null) return false;
        List<Playlist> list = all(context);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(playlist.id)) {
                list.set(i, playlist);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(playlist);
        sort(list);
        return write(context, list);
    }

    public static synchronized boolean delete(Context context, String id) {
        List<Playlist> list = all(context);
        Playlist removed = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                removed = list.remove(i);
                break;
            }
        }
        if (removed == null) return false;
        // A custom cover belongs to the playlist, so it goes with it.
        if (removed.coverPath != null && !removed.coverPath.isEmpty()) {
            File cover = new File(removed.coverPath);
            if (cover.getParentFile() != null
                    && cover.getParentFile().getName().equals(COVER_DIR)) {
                cover.delete();
            }
        }
        return write(context, list);
    }

    /** Replaces the in-memory order with {@code list} and persists it. */
    public static synchronized boolean reorder(Context context, List<Playlist> list) {
        sort(list);
        return write(context, list);
    }

    private static boolean write(Context context, List<Playlist> list) {
        JSONArray arr = new JSONArray();
        for (Playlist p : list) arr.put(p.toJson());
        File target = file(context);
        File temp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "could not stage playlists", e);
            return false;
        }
        // Rename last: if this fails the previous good file is still in place.
        if (!temp.renameTo(target)) {
            try {
                target.delete();
                if (!temp.renameTo(target)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        cache = new ArrayList<>(list);
        sort(cache);
        return true;
    }

    /**
     * Copies a picked image into private storage and returns its path.
     *
     * The picked uri is only a temporary grant, so keeping the uri would show a broken
     * cover after the next process start. The image is re-encoded to a square JPEG at a
     * sane size instead of being stored at full camera resolution.
     */
    public static String importCover(Context context, Uri source) {
        if (source == null) return "";
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(source);
            if (in == null) return "";
            Bitmap raw = BitmapFactory.decodeStream(in);
            if (raw == null) return "";
            Bitmap square = centerSquare(raw, 640);
            File out = new File(coverDir(context), "cover_" + System.currentTimeMillis() + ".jpg");
            try (OutputStream os = new FileOutputStream(out)) {
                square.compress(Bitmap.CompressFormat.JPEG, 90, os);
                os.flush();
            }
            if (square != raw) square.recycle();
            raw.recycle();
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "cover import failed", e);
            return "";
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Crops to a centred square and scales it down, keeping the aspect ratio intact. */
    private static Bitmap centerSquare(Bitmap src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        Bitmap crop = Bitmap.createBitmap(src, x, y, side, side);
        if (side == size) return crop;
        Bitmap scaled = Bitmap.createScaledBitmap(crop, size, size, true);
        if (scaled != crop) crop.recycle();
        return scaled;
    }

    /** Removes every custom cover, used when clearing app data from the UI. */
    public static void clearCovers(Context context) {
        File[] files = coverDir(context).listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }
}
