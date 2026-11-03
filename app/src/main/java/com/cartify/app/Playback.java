package com.cartify.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single playback engine: queue, current track, MediaPlayer, seek, shuffle and repeat.
 *
 * Lives in the app process and outlives any single screen, so the mini player, the
 * full player and the notification all observe the same state.
 */
public final class Playback {

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    /** UI callbacks, always delivered on the main thread. */
    public interface Listener {
        void onPlaybackChanged();

        void onQueueChanged();
    }

    private static final String TAG = "Cartify";
    private static Playback instance;

    public static synchronized Playback get(Context context) {
        if (instance == null) instance = new Playback(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Track> queue = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();

    private MediaPlayer player;
    private int index = -1;
    private int shufflePos = 0;
    private boolean shuffle;
    private int repeat = REPEAT_OFF;
    private boolean loading;
    private boolean playWhenReady;
    private long pendingSeek = -1;
    private String source = "";

    /**
     * Audio focus, so a call or another player can take the speakers away.
     *
     * Nothing asked for focus before: music kept playing over an incoming call and
     * over a video started in another app, which is the single most obvious way a
     * player can behave badly. A loss pauses; a short duck lowers the volume and
     * restores it rather than stopping mid-song.
     */
    private android.media.AudioManager audioManager;
    private android.media.AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean resumeOnFocusGain;
    /** Set when we paused ourselves, so a focus gain does not fight the user. */
    private boolean pausedByFocus;
    private boolean hasFocus;

    private Playback(Context context) {
        this.app = context;
        // Shuffle and repeat are habits, not per-session choices: someone who listens on
        // shuffle should not have to turn it back on after every app restart.
        android.content.SharedPreferences prefs =
                app.getSharedPreferences("cartify_prefs", Context.MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle_on", false);
        int savedRepeat = prefs.getInt("repeat_mode", REPEAT_OFF);
        repeat = (savedRepeat >= REPEAT_OFF && savedRepeat <= REPEAT_ONE) ? savedRepeat : REPEAT_OFF;
    }

    /** Remembers the mode for the next launch; the queue itself is not persisted. */
    private void saveModes() {
        app.getSharedPreferences("cartify_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("shuffle_on", shuffle)
                .putInt("repeat_mode", repeat)
                .apply();
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyPlayback() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onPlaybackChanged();
        });
    }

    private void notifyQueue() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onQueueChanged();
        });
    }

    // ------------------------------------------------------------------ queue

    public List<Track> queue() {
        return queue;
    }

    public int index() {
        return index;
    }

    public String source() {
        return source;
    }

    public void setSource(String source) {
        this.source = source == null ? "" : source;
    }

    public Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    /** Replaces the queue and starts at {@code startIndex}. */
    public void playQueue(List<Track> tracks, int startIndex, String sourceName) {
        queue.clear();
        if (tracks != null) queue.addAll(tracks);
        source = sourceName == null ? "" : sourceName;
        rebuildOrder(-1);
        notifyQueue();
        playAt(Math.max(0, Math.min(startIndex, queue.size() - 1)), true);
    }

    public void playSingle(Track track, String sourceName) {
        List<Track> one = new ArrayList<>();
        one.add(track);
        playQueue(one, 0, sourceName);
    }

    public void append(Track track) {
        if (track == null) return;
        queue.add(track);
        rebuildOrder(index);
        notifyQueue();
    }

    /** Adds the track right after the current one (used by "play next"). */
    public void playNext(Track track) {
        if (track == null) return;
        if (index < 0 || index >= queue.size()) {
            queue.add(track);
        } else {
            queue.add(index + 1, track);
        }
        rebuildOrder(index);
        notifyQueue();
    }

    public void removeAt(int position) {
        if (position < 0 || position >= queue.size()) return;
        boolean wasCurrent = position == index;
        queue.remove(position);
        if (position < index) index--;
        rebuildOrder(index);
        notifyQueue();
        if (wasCurrent) {
            if (queue.isEmpty()) {
                stop();
            } else {
                playAt(Math.min(index, queue.size() - 1), isPlaying());
            }
        } else {
            notifyPlayback();
        }
    }

    public void clearQueue() {
        queue.clear();
        index = -1;
        stop();
        notifyQueue();
    }

    // ------------------------------------------------------------------ transport

    public void playAt(int i, boolean start) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        playWhenReady = start;
        loadCurrent();
    }

    private void loadCurrent() {
        Track t = current();
        release();
        pendingSeek = -1;
        if (t == null || t.localUri == null || t.localUri.isEmpty()) {
            notifyPlayback();
            return;
        }
        loading = true;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(app, Uri.parse(t.localUri));
            mp.setOnPreparedListener(m -> {
                loading = false;
                if (pendingSeek > 0) {
                    m.seekTo((int) pendingSeek);
                    pendingSeek = -1;
                }
                if (playWhenReady) {
                    // Focus is requested here, not only from the play button: a queue
                    // started from a playlist, an auto-advance or a resumed notification
                    // all reach playback through this listener and none of them used to
                    // ask for focus, so a call could not pause the music.
                    requestFocus();
                    m.start();
                    ensureService();
                }
                notifyPlayback();
            });
            mp.setOnCompletionListener(m -> onComplete());
            mp.setOnErrorListener((m, what, extra) -> {
                loading = false;
                Log.w(TAG, "player error " + what + "/" + extra + " on " + t.title);
                notifyPlayback();
                main.postDelayed(() -> next(true), 400);
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            loading = false;
            Log.w(TAG, "cannot open " + t.localUri, e);
        }
        notifyPlayback();
    }

    private void onComplete() {
        if (repeat == REPEAT_ONE) {
            seekTo(0);
            if (player != null) player.start();
            notifyPlayback();
            return;
        }
        next(true);
    }

    /**
     * Play/pause from any surface.
     *
     * A MediaPlayer that is still preparing throws if it is started or paused, which is
     * exactly the window the mini player is most likely to be tapped in. While the file
     * is loading the intent is recorded instead and applied the moment it is prepared,
     * so the button never throws and never appears to do nothing.
     */
    public void toggle() {
        if (player == null || loading) {
            if (player == null) {
                playAt(index < 0 ? 0 : index, true);
            } else {
                playWhenReady = !playWhenReady;
                notifyPlayback();
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                playWhenReady = false;
                abandonFocus();
            } else {
                if (!requestFocus()) {
                    // Refused, but playback still proceeds: focus is a courtesy to
                    // other apps, not a lock on the speakers.
                    Log.w(TAG, "audio focus refused");
                }
                player.start();
                playWhenReady = true;
                ensureService();
            }
        } catch (IllegalStateException e) {
            // The track changed under the tap; the next tick redraws the real state.
            Log.w(TAG, "toggle ignored while player was re-binding", e);
        }
        notifyPlayback();
    }

    /** True while the current file is still being prepared. */
    public boolean isLoading() {
        return loading;
    }

    /** Whether the current track is meant to be playing, even mid-load. */
    public boolean wantsPlay() {
        return playWhenReady;
    }

    public void pause() {
        if (player != null && isPlaying()) {
            player.pause();
            pausedByFocus = false;
            notifyPlayback();
        }
    }

    /** Pause without telling the focus logic that the user asked for it. */
    private void pauseInternal() {
        try {
            if (player != null && player.isPlaying()) {
                player.pause();
                playWhenReady = false;
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "focus pause ignored while re-binding", e);
        }
    }

    /** Resume after a transient focus loss, without asking for focus again. */
    private void startInternal() {
        try {
            if (player != null && !loading) {
                player.start();
                playWhenReady = true;
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "focus resume ignored while re-binding", e);
        }
    }

    // ------------------------------------------------------------------ sleep timer

    /**
     * Minutes left before playback stops itself, or 0 when no timer is armed.
     *
     * Lives here rather than in a screen so it survives leaving the player, and so the
     * background service and the notification see the same countdown.
     */
    private volatile long sleepAtMs = 0L;
    private Runnable sleepTick;

    /** Arms the timer for {@code minutes} from now; 0 cancels it. */
    public void setSleepTimer(int minutes) {
        cancelSleepTimer();
        if (minutes <= 0) {
            notifyPlayback();
            return;
        }
        sleepAtMs = System.currentTimeMillis() + minutes * 60_000L;
        sleepTick = () -> {
            if (sleepAtMs == 0L) return;
            if (System.currentTimeMillis() >= sleepAtMs) {
                // Pause rather than stop: the queue stays loaded, so the user wakes to the
                // same song list instead of an empty player.
                sleepAtMs = 0L;
                pause();
                notifyPlayback();
                return;
            }
            // Re-notify once a minute so the player and the notification both redraw the
            // remaining time without either one polling the clock on its own.
            notifyPlayback();
            main.postDelayed(sleepTick, 30_000L);
        };
        main.postDelayed(sleepTick, 30_000L);
        notifyPlayback();
    }

    public void cancelSleepTimer() {
        sleepAtMs = 0L;
        if (sleepTick != null) {
            main.removeCallbacks(sleepTick);
            sleepTick = null;
        }
    }

    /** Whole minutes remaining, rounded up; 0 when no timer is armed. */
    public int sleepMinutesLeft() {
        long at = sleepAtMs;
        if (at == 0L) return 0;
        long left = at - System.currentTimeMillis();
        if (left <= 0) return 0;
        return (int) ((left + 59_999L) / 60_000L);
    }

    public void stop() {
        playWhenReady = false;
        release();
        notifyPlayback();
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void next(boolean auto) {
        if (queue.isEmpty()) return;
        if (shuffle) {
            if (order.isEmpty()) rebuildOrder(index);
            int pos = order.indexOf(index);
            pos++;
            if (pos >= order.size()) {
                if (repeat == REPEAT_OFF && auto) {
                    pause();
                    return;
                }
                pos = 0;
            }
            shufflePos = pos;
            playAt(order.get(pos), true);
            return;
        }
        int n = index + 1;
        if (n >= queue.size()) {
            if (repeat == REPEAT_OFF) {
                if (auto) pause();
                else playAt(0, true);
                return;
            }
            n = 0;
        }
        playAt(n, true);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (position() > 3000) {
            seekTo(0);
            notifyPlayback();
            return;
        }
        if (shuffle) {
            if (order.isEmpty()) rebuildOrder(index);
            int pos = order.indexOf(index) - 1;
            if (pos < 0) pos = order.size() - 1;
            shufflePos = pos;
            playAt(order.get(pos), true);
            return;
        }
        int n = index - 1;
        if (n < 0) n = repeat == REPEAT_OFF ? 0 : queue.size() - 1;
        playAt(n, true);
    }

    public void seekTo(long ms) {
        if (player == null) {
            pendingSeek = ms;
            return;
        }
        try {
            player.seekTo((int) Math.max(0, ms));
        } catch (Exception ignored) {
        }
    }

    public long position() {
        try {
            return player == null ? 0 : player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    public long duration() {
        Track t = current();
        if (!loading) {
            try {
                if (player != null && player.getDuration() > 0) return player.getDuration();
            } catch (Exception ignored) {
            }
        }
        return t == null ? 0 : t.durationMs;
    }

    // ------------------------------------------------------------------ modes

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean on) {
        shuffle = on;
        rebuildOrder(index);
        saveModes();
        notifyPlayback();
    }

    public int repeatMode() {
        return repeat;
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        saveModes();
        notifyPlayback();
    }

    private void rebuildOrder(int keep) {
        order.clear();
        for (int i = 0; i < queue.size(); i++) if (i != keep) order.add(i);
        Collections.shuffle(order);
        if (keep >= 0 && keep < queue.size()) order.add(0, keep);
        shufflePos = 0;
    }

    // ------------------------------------------------------------------ focus

    private boolean requestFocus() {
        try {
            if (audioManager == null) {
                audioManager = (android.media.AudioManager)
                        app.getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return true;
            if (focusListener == null) {
                focusListener = change -> main.post(() -> onFocusChange(change));
            }
            int result = audioManager.requestAudioFocus(focusListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
            hasFocus = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            // Logged so a "music kept playing during a call" report can be answered:
            // a granted focus means the platform will deliver the loss, a refused one
            // means another app holds audio and nothing will arrive.
            Log.i(TAG, "audio focus " + (hasFocus ? "granted" : "refused"));
            // A refused request is not a reason to refuse to play: some devices and
            // some focus-holding apps never release it, and silently not playing at
            // all would be worse than playing over them.
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "audio focus request failed", e);
            return true;
        }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
            hasFocus = false;
        } catch (Throwable ignored) {
        }
    }

    private void onFocusChange(int change) {
        switch (change) {
            case android.media.AudioManager.AUDIOFOCUS_LOSS:
                // Permanent: another player owns audio now. Stop and do not resume
                // by ourselves when it comes back.
                resumeOnFocusGain = false;
                if (isPlaying()) {
                    pausedByFocus = true;
                    pauseInternal();
                }
                hasFocus = false;
                break;
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = isPlaying();
                if (isPlaying()) {
                    pausedByFocus = true;
                    pauseInternal();
                }
                break;
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // A notification sound should not stop the song, just lower it.
                if (player != null) {
                    try {
                        player.setVolume(0.2f, 0.2f);
                    } catch (Throwable ignored) {
                    }
                }
                break;
            case android.media.AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (player != null) {
                    try {
                        player.setVolume(1f, 1f);
                    } catch (Throwable ignored) {
                    }
                }
                if (resumeOnFocusGain && pausedByFocus) {
                    resumeOnFocusGain = false;
                    pausedByFocus = false;
                    startInternal();
                }
                break;
            default:
                break;
        }
        notifyPlayback();
    }

    // ------------------------------------------------------------------ service

    private void ensureService() {
        try {
            Intent i = new Intent(app, PlaybackService.class).setAction(PlaybackService.ACTION_SYNC);
            app.startForegroundService(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot start playback service", e);
        }
    }

    private void release() {
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        loading = false;
    }
}
