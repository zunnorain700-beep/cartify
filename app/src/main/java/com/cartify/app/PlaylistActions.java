package com.cartify.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Rename / cover / delete for a playlist, shared by the shelf and the detail screen.
 *
 * The cover picker hands back a temporary uri grant, so the image is copied into private
 * storage the moment it arrives; the playlist then points at a real file path.
 */
public final class PlaylistActions {

    public static final int REQ_COVER = 7331;

    private static Playlist pendingCover;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PlaylistActions() {
    }

    public static void rename(Activity activity, Playlist playlist, Runnable done) {
        if (activity == null || playlist == null) return;
        InputSheet.show(activity, "Rename playlist", null, playlist.name, "Save", value -> {
            playlist.name = value;
            playlist.updatedAt = System.currentTimeMillis();
            PlaylistStore.save(activity, playlist);
            if (done != null) done.run();
        });
    }

    public static void pickCover(Activity activity, Playlist playlist) {
        if (activity == null || playlist == null) return;
        pendingCover = playlist;
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(Intent.createChooser(pick, "Choose a cover"), REQ_COVER);
        } catch (Exception e) {
            pendingCover = null;
            Toast.makeText(activity, "No gallery app available", Toast.LENGTH_SHORT).show();
        }
    }

    /** Returns true when the result was a cover pick and has been handled here. */
    public static boolean onActivityResult(Activity activity, int requestCode, int resultCode,
                                           Intent data, Runnable done) {
        if (requestCode != REQ_COVER) return false;
        Playlist playlist = pendingCover;
        pendingCover = null;
        if (playlist == null || activity == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;

        final Uri picked = data.getData();
        final android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            String path = PlaylistStore.importCover(app, picked);
            MAIN.post(() -> {
                if (path == null || path.isEmpty()) {
                    Toast.makeText(app, "Could not read that image", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Delete the previous custom cover so covers do not pile up on disk.
                String old = playlist.coverPath;
                if (old != null && !old.isEmpty() && !old.equals(path)) {
                    new java.io.File(old).delete();
                }
                playlist.coverPath = path;
                playlist.updatedAt = System.currentTimeMillis();
                PlaylistStore.save(app, playlist);
                if (done != null) done.run();
            });
        }, "cartify-cover").start();
        return true;
    }

    public static void confirmDelete(Activity activity, Playlist playlist, Runnable done) {
        if (activity == null || playlist == null) return;
        MenuSheet.show(activity, "Delete \"" + playlist.name + "\"?",
                "The playlist is removed. The songs stay on your device.",
                new MenuSheet.Item(R.drawable.ic_delete, "Delete playlist", true, () -> {
                    PlaylistStore.delete(activity, playlist.id);
                    if (done != null) done.run();
                }),
                new MenuSheet.Item(R.drawable.ic_close, "Cancel", () -> {
                }));
    }
}
