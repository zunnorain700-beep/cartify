package com.cartify.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * The sound that plays as the brand word is written in.
 *
 * Drop a file at {@code app/src/main/res/raw/brand.mp3} (or .ogg/.wav, updating the
 * reference below) and it is used automatically. Until then this falls back to
 * Android's own text-to-speech saying "Cartify", so the launch is never silent and
 * no placeholder recording ships in the APK.
 */
public final class BrandSound {

    private static final String TAG = "Cartify";
    private static final String UTTERANCE = "cartify-brand";

    private static MediaPlayer player;
    private static TextToSpeech tts;
    private static volatile boolean ttsReady;
    private static boolean spoken;
    private static boolean releaseAfterPlay;

    private BrandSound() {
    }

    /** True when a recorded brand sound has been added to the project. */
    private static boolean hasRecording(Context context) {
        try {
            return context.getResources().getIdentifier("brand", "raw", context.getPackageName()) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Warms up whichever source will be used, during the splash. */
    public static synchronized void prepare(Context context) {
        if (spoken) return;
        if (hasRecording(context)) return;
        if (tts != null) return;
        final Context app = context.getApplicationContext();
        tts = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.d(TAG, "no speech engine available");
                return;
            }
            try {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.92f);
                tts.setPitch(0.95f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsReady = true;
            } catch (Throwable e) {
                Log.d(TAG, "speech setup failed: " + e);
            }
        });
    }

    /** Plays the brand sound exactly once per process launch. */
    public static void play(Context context) {
        if (spoken) return;
        spoken = true;
        if (hasRecording(context)) {
            playRecording(context);
        } else {
            speak(context);
        }
    }

    private static void playRecording(Context context) {
        try {
            int id = context.getResources().getIdentifier("brand", "raw", context.getPackageName());
            player = MediaPlayer.create(context.getApplicationContext(), id);
            if (player == null) {
                speak(context);
                return;
            }
            player.setVolume(1f, 1f);
            // A short file must not outlive the splash, but should not be cut short either.
            player.setOnCompletionListener(mp -> releasePlayer());
            player.start();
        } catch (Throwable e) {
            Log.d(TAG, "brand sound failed: " + e);
            speak(context);
        }
    }

    private static void speak(Context context) {
        if (tts == null) prepare(context);
        if (ttsReady) {
            speakNow();
            return;
        }
        // The engine may still be constructing; wait for it off the main thread.
        new Thread(() -> {
            for (int i = 0; i < 40 && !ttsReady; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (ttsReady) speakNow();
        }, "cartify-voice").start();
    }

    private static void speakNow() {
        try {
            tts.speak("Cartify", TextToSpeech.QUEUE_FLUSH, null, UTTERANCE);
        } catch (Throwable e) {
            Log.d(TAG, "speak failed: " + e);
        }
    }

    /**
     * Marks the intro as finished. A recording that is still playing is allowed to run
     * to its end; only the idle resources are freed here.
     */
    public static synchronized void finish() {
        if (player != null && !player.isPlaying()) releasePlayer();
        if (tts != null && (player == null)) releaseTts();
    }

    /** Hard stop, used when the activity is destroyed mid-intro. */
    public static synchronized void release() {
        releasePlayer();
        releaseTts();
    }

    private static void releasePlayer() {
        if (player == null) return;
        try {
            player.reset();
            player.release();
        } catch (Throwable ignored) {
        }
        player = null;
        releaseTts();
    }

    private static void releaseTts() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        ttsReady = false;
    }
}
