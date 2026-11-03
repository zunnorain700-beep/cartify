package com.cartify.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Writes a playlist out as an .m3u file next to the music.
 *
 * Playlists currently exist only inside Cartify, so a list of songs cannot be opened by
 * another player or backed up off the device. M3U is the format nearly every music player
 * reads, and it is plain text, so the file stays useful even if this app is uninstalled.
 *
 * Paths are written relative to the file's own folder where possible, because an absolute
 * path from one phone will not exist on another. The #EXTM3U header with EXTINF lines is
 * included so players show track names and lengths instead of bare filenames.
 */
public final class PlaylistExport {

    private static final String TAG = "Cartify";
    private static final String FOLDER = "Cartify";

    public interface Callback {
        void onDone(String displayPath);

        void onFailed(String message);
    }

    private PlaylistExport() {
    }

    /**
     * Exports {@code playlist} on a background thread.
     *
     * @param relative whether to write paths relative to the export file's folder. True
     *                 produces a portable file; false produces absolute paths, which some
     *                 desktop players prefer when the file sits outside the music folder.
     */
    public static void export(final Context context, final Playlist playlist,
                              final boolean relative, final Callback callback) {
        if (context == null || playlist == null) {
            if (callback != null) callback.onFailed("Nothing to export");
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String result = null;
            String error = null;
            try {
                result = write(app, playlist, relative);
            } catch (Exception e) {
                Log.w(TAG, "playlist export failed", e);
                error = e.getMessage() == null ? "Could not write the playlist" : e.getMessage();
            }
            final String ok = result;
            final String bad = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (callback == null) return;
                if (ok != null) callback.onDone(ok);
                else callback.onFailed(bad == null ? "Export failed" : bad);
            });
        }, "playlist-export").start();
    }

    private static String write(Context context, Playlist playlist, boolean relative)
            throws Exception {
        String safeName = sanitize(playlist.name);
        if (safeName.isEmpty()) safeName = "Playlist";
        String fileName = safeName + ".m3u";

        StringBuilder body = new StringBuilder();
        body.append("#EXTM3U\n");
        for (Playlist.Entry e : playlist.songs) {
            if (e == null || e.uri == null || e.uri.isEmpty()) continue;
            long secs = e.durationMs > 0 ? e.durationMs / 1000L : -1L;
            body.append("#EXTINF:").append(secs).append(',')
                    .append(safe(e.artist)).append(" - ").append(safe(e.title)).append('\n');
            body.append(pathFor(context, e.uri, relative)).append('\n');
        }
        byte[] bytes = body.toString().getBytes(Charset.forName("UTF-8"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + File.separator + FOLDER);
            // An .m3u is a text file, not audio, so the audio collection rejects it.
            // The generic files collection accepts any type and still honours RELATIVE_PATH.
            Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri target = context.getContentResolver().insert(collection, values);
            if (target == null) {
                Log.w(TAG, "export: insert returned null for " + fileName);
                throw new IllegalStateException("Could not create the file");
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(target)) {
                if (out == null) throw new IllegalStateException("Could not open the file");
                out.write(bytes);
            }
            return "Music/" + FOLDER + "/" + fileName;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), FOLDER);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        return "Music/" + FOLDER + "/" + fileName;
    }

    /**
     * A path a player can follow.
     *
     * Songs are stored as content:// uris, and the last segment of one is a MediaStore id
     * such as 39966, not a filename. Writing that into an .m3u produced a file every player
     * would ignore. The display name is looked up instead, which is the actual file on disk.
     *
     * Relative is the portable choice: the export and the songs sit in the same folder, so
     * "song.mp3" keeps working after the whole folder is copied elsewhere.
     */
    private static String pathFor(Context context, String uri, boolean relative) {
        String name = displayNameFor(context, uri);
        if (!relative) {
            // Absolute paths only help when the real location is known; the music folder is
            // the one place these files live.
            return Environment.DIRECTORY_MUSIC + File.separator + FOLDER + File.separator + name;
        }
        return name;
    }

    /**
     * The file name behind a song uri.
     *
     * Falls back to the last path segment for plain file paths and for any uri the media
     * store refuses to describe, so one unreadable row cannot fail the whole export.
     */
    private static String displayNameFor(Context context, String uri) {
        if (uri == null) return "unknown.mp3";
        if (uri.startsWith("file://")) {
            String p = uri.substring(7);
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
        if (!uri.startsWith("content://")) {
            int slash = uri.lastIndexOf('/');
            return slash >= 0 && slash + 1 < uri.length() ? uri.substring(slash + 1) : uri;
        }
        android.database.Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    Uri.parse(uri),
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception e) {
            Log.w(TAG, "export: could not read name for " + uri, e);
        } finally {
            if (c != null) c.close();
        }
        int slash = uri.lastIndexOf('/');
        return slash >= 0 && slash + 1 < uri.length() ? uri.substring(slash + 1) : "unknown.mp3";
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
                    c == '"' || c == '<' || c == '>' || c == '|') sb.append('_');
            else sb.append(c);
        }
        return sb.toString().trim();
    }
}
