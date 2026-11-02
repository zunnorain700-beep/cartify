package com.cartify.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the download queue in the foreground so long playlists survive screen-off.
 * Each row owns a cancellable {@link DownloadEngine.Task}; cancelling one row kills
 * its yt-dlp process instead of letting the transfer run to completion.
 */
public class DownloadService extends Service implements ProgressBus.Listener {

    public static final String ACTION_START = "com.cartify.app.START";
    public static final String ACTION_CANCEL = "com.cartify.app.CANCEL";
    public static final String ACTION_CANCEL_TRACK = "com.cartify.app.CANCEL_TRACK";
    public static final String EXTRA_TRACKS = "tracks";
    public static final String EXTRA_ALBUM = "album";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_INDEX = "index";

    private static final String CHANNEL_ID = "cartify_downloads";
    private static final int NOTIFICATION_ID = 4211;
    private static final String TAG = "Cartify";

    /**
     * How many tracks are fetched at the same time.
     *
     * Six streams of an audio-only transfer is still modest for a phone connection and
     * roughly halves the wall time of a playlist against the previous four.
     */
    private static final int PARALLEL = 6;

    private volatile boolean cancelled = false;
    private Thread coordinator;
    private NotificationManager notificationManager;
    private final Map<Integer, DownloadEngine.Task> running = new ConcurrentHashMap<>();
    /**
     * Rows the user cancelled that have not been picked up by a worker yet.
     *
     * Without this a cancel on a still-queued track did nothing at all: the row went
     * idle in the UI, but the worker that reached it later started the transfer anyway.
     */
    private final java.util.Set<Integer> cancelledBeforeStart =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final AtomicInteger active = new AtomicInteger(0);
    private final List<Track> queueRef = new ArrayList<>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        ProgressBus.register(this);
    }

    @Override
    public void onDestroy() {
        ProgressBus.unregister(this);
        cancelAll();
        if (coordinator != null) coordinator.interrupt();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelAll();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_TRACK.equals(action)) {
            cancelTrack(intent.getIntExtra(EXTRA_INDEX, -1));
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String payload = intent.getStringExtra(EXTRA_TRACKS);
        String album = intent.getStringExtra(EXTRA_ALBUM);
        int total = intent.getIntExtra(EXTRA_TOTAL, 0);
        List<Track> tracks = parse(payload);
        if (tracks.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (total <= 0) total = tracks.size();

        startForeground(NOTIFICATION_ID, buildNotification(0, total, "Preparing downloader"));

        final List<Track> queue = new ArrayList<>(tracks);
        queueRef.clear();
        queueRef.addAll(queue);
        final String albumName = album;
        final int expected = total;
        cancelled = false;
        ProgressBus.resetState();
        running.clear();
        cancelledBeforeStart.clear();
        coordinator = new Thread(() -> {
            try {
                runQueue(queue, albumName, expected);
            } catch (Throwable t) {
                ProgressBus.queueDone(0, queue.size());
                stopForeground(true);
                stopSelf();
            }
        }, "cartify-downloader");
        coordinator.start();
        return START_NOT_STICKY;
    }

    private void cancelTrack(int index) {
        if (index < 0) return;
        // A queued row has no process to kill yet, so remember it was cancelled; the
        // worker skips it when it reaches that slot instead of downloading it anyway.
        cancelledBeforeStart.add(index);
        DownloadEngine.Task task = running.remove(index);
        if (task != null) task.cancel();
        ProgressBus.cancelled(index);
        int total = Math.max(queueRef.size(), 1);
        updateNotification(Math.max(0, total - active.get()), total, "Cancelled a track");
    }

    private void cancelAll() {
        cancelled = true;
        for (DownloadEngine.Task task : running.values()) task.cancel();
        running.clear();
        if (coordinator != null) coordinator.interrupt();
    }

    /**
     * Runs the queue across a small pool of workers so a playlist downloads
     * concurrently instead of one song at a time.
     */
    private volatile long lastNote;

    private void runQueue(List<Track> tracks, String album, int expectedTotal) {
        final long queueStarted = SystemClock.elapsedRealtime();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm == null ? null
                : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cartify:download");
        if (lock != null) lock.acquire(60 * 60 * 1000L);

        final int total = expectedTotal;
        final AtomicInteger next = new AtomicInteger(0);
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);

        int workers = Math.max(1, Math.min(PARALLEL, tracks.size()));
        List<Thread> pool = new ArrayList<>();

        for (int slot = 0; slot < workers; slot++) {
            final int workerSlot = slot;
            Thread worker = new Thread(() -> {
                while (!cancelled) {
                    int index = next.getAndIncrement();
                    if (index >= tracks.size()) return;
                    Track t = tracks.get(index);
                    if (!album.isEmpty()) t.album = album;

                    // The row may have been cancelled while it was still waiting here.
                    if (cancelledBeforeStart.remove(index)) {
                        ProgressBus.cancelled(index);
                        continue;
                    }

                    DownloadEngine.Task task = new DownloadEngine.Task();
                    running.put(index, task);
                    active.incrementAndGet();
                    ProgressBus.progress(index, 0, "Queued");
                    final long started = SystemClock.elapsedRealtime();
                    try {
                        String uri = DownloadEngine.download(this, t, (percent, detail) -> {
                            ProgressBus.progress(index, percent, detail);
                            // Notification re-posts are not free and yt-dlp fires many per
                            // second; one update per 400 ms still reads as live.
                            long now = System.currentTimeMillis();
                            if (now - lastNote >= 400 || percent >= 100) {
                                lastNote = now;
                                updateNotification(Math.min(total, active.get()), total,
                                        t.title + " - " + detail);
                            }
                            return cancelled;
                        }, workerSlot, task);
                        ProgressBus.finished(index, uri, null);
                        completed.incrementAndGet();
                    } catch (Exception e) {
                        String message = friendly(e);
                        if (task.isCancelled() || cancelled) {
                            ProgressBus.cancelled(index);
                        } else {
                            ProgressBus.finished(index, null, message);
                            failed.incrementAndGet();
                        }
                    } finally {
                        // Per-track wall time is the only number that shows whether the
                        // parallel fan-out actually helps; without it a playlist run is
                        // just "it finished at some point".
                        Log.i(TAG, "track " + (index + 1) + "/" + total + " slot=" + workerSlot
                                + " took " + (SystemClock.elapsedRealtime() - started) + " ms");
                        running.remove(index);
                        active.decrementAndGet();
                    }
                }
            }, "cartify-worker-" + slot);
            pool.add(worker);
            worker.start();
        }

        for (Thread w : pool) {
            try {
                w.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (lock != null && lock.isHeld()) lock.release();
        long queueMs = SystemClock.elapsedRealtime() - queueStarted;
        Log.i(TAG, "queue done: " + completed.get() + " saved, " + failed.get() + " failed, "
                + tracks.size() + " tracks, workers=" + workers + ", " + queueMs + " ms total"
                + (tracks.size() > 0 ? ", avg " + (queueMs / Math.max(1, tracks.size())) + " ms/track" : ""));
        ProgressBus.queueDone(completed.get(), failed.get());
        updateNotification(total, total, "Finished — " + completed.get() + " saved");
        stopForeground(false);
        stopSelf();
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private Notification buildNotification(int done, int total, String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | pendingFlag());
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, DownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | pendingFlag());
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Cartify — " + Math.min(done, total) + " of " + total)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(0, "Cancel all", cancel)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(Math.max(total, 1), Math.min(done, total), false)
                .build();
    }

    private void updateNotification(int done, int total, String text) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(done, total, text));
        }
    }

    private static int pendingFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Cartify download progress");
            notificationManager.createNotificationChannel(ch);
        }
    }

    private static List<Track> parse(String payload) {
        List<Track> out = new ArrayList<>();
        if (payload == null) return out;
        try {
            JSONArray arr = new JSONArray(payload);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Track.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public void onTrackProgress(int index, int percent, String detail) {
    }

    @Override
    public void onTrackFinished(int index, String uri, String error) {
    }

    @Override
    public void onTrackCancelled(int index) {
    }

    @Override
    public void onQueueFinished(int completed, int failed) {
    }
}
