package com.cartify.app;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * In-process progress fan-out between the download service and the UI.
 * The service lives in the same process, so no IPC or third-party bus is needed.
 */
public final class ProgressBus {

    public interface Listener {
        void onTrackProgress(int index, int percent, String detail);

        void onTrackFinished(int index, String uri, String error);

        /** The user stopped this row; it returns to the idle state, not to "failed". */
        void onTrackCancelled(int index);

        void onQueueFinished(int completed, int failed);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();

    /**
     * The latest state of every row, kept here rather than only in the activity.
     *
     * The service outlives the screen, so a download that keeps running while the app is
     * backgrounded must still be able to paint itself when the activity comes back. On
     * registration the bus replays this snapshot, which is why returning to a running
     * download shows live numbers instead of a row that looks idle.
     */
    private static final java.util.Map<Integer, int[]> PCT = new java.util.HashMap<>();
    private static final java.util.Map<Integer, String> DETAIL = new java.util.HashMap<>();
    /** 0 = idle, 1 = running, 2 = done, 3 = failed. */
    private static final java.util.Map<Integer, Integer> STATUS = new java.util.HashMap<>();

    private ProgressBus() {
    }

    public static void register(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    static void progress(final int index, int percent, final String detail) {
        synchronized (PCT) {
            int[] prev = PCT.get(index);
            // A running row is strictly monotonic. The old guard only clamped below 90, so a
            // late stage report (tagging says 90 after the conversion band had already
            // reached 91 or 92) moved the number backwards above that line and the row
            // visibly alternated between the two values.
            Integer status = STATUS.get(index);
            if (prev != null && status != null && status == 1 && percent < prev[0]) {
                percent = prev[0];
            }
            PCT.put(index, new int[]{percent});
            DETAIL.put(index, detail);
            STATUS.put(index, percent >= 100 ? 2 : 1);
        }
        final int pct = percent;
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onTrackProgress(index, pct, detail);
        });
    }

    /** Re-sends everything the bus knows to one late listener. */
    public static void replay(final Listener l) {
        if (l == null) return;
        java.util.List<Integer> keys = new ArrayList<>();
        synchronized (PCT) {
            keys.addAll(PCT.keySet());
            java.util.Collections.sort(keys);
        }
        MAIN.post(() -> {
            for (Integer key : keys) {
                int status;
                int percent;
                String detail;
                synchronized (PCT) {
                    Integer st = STATUS.get(key);
                    status = st == null ? 1 : st;
                    int[] v = PCT.get(key);
                    percent = v == null ? 0 : v[0];
                    detail = DETAIL.get(key);
                }
                if (status == 2) continue;          // finished rows re-read from the library
                l.onTrackProgress(key, percent, detail == null ? "Working" : detail);
            }
        });
    }

    /** Clears the snapshot for a fresh queue, so old indices cannot leak into a new list. */
    public static void resetState() {
        synchronized (PCT) {
            PCT.clear();
            DETAIL.clear();
            STATUS.clear();
        }
    }

    static void finished(final int index, final String uri, final String error) {
        synchronized (PCT) {
            STATUS.put(index, error == null ? 2 : 3);
            if (error == null) PCT.put(index, new int[]{100});
        }
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onTrackFinished(index, uri, error);
        });
    }

    static void cancelled(final int index) {
        // Drop the row's snapshot: a cancelled row is idle, and leaving its old percentage
        // behind meant a later replay could repaint a row the user had already reset.
        synchronized (PCT) {
            PCT.remove(index);
            DETAIL.remove(index);
            STATUS.remove(index);
        }
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onTrackCancelled(index);
        });
    }

    static void queueDone(final int completed, final int failed) {
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onQueueFinished(completed, failed);
        });
    }
}
