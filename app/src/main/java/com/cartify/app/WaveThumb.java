package com.cartify.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Seek head for the player: a small neon waveform that breathes with the song.
 *
 * The plain round handle used before hung halfway off the track at the very start of a
 * song, so its left edge was cut by the view. This drawable is a full bar wide and the
 * SeekBar is padded by half of it, which keeps the head whole at both ends of the song.
 * Inside the bar, three mint strokes ride a travelling wave while a soft green halo
 * behind them brightens and dims, like a neon tube cycling.
 */
public class WaveThumb extends Drawable {

    private static final long LOOP_MS = 1500L;
    private static final int BARS = 3;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float size;
    private final float barGap;

    /** 0..1 position in the wave loop, advanced by the animator while playing. */
    private float phase;
    private boolean playing;
    private ValueAnimator animator;

    public WaveThumb(Context context) {
        final float d = context.getResources().getDisplayMetrics().density;
        size = 22f * d;
        barGap = 4.2f * d;
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeWidth(2.2f * d);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setColor(context.getColor(R.color.accent_bright));
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(context.getColor(R.color.accent));
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) size;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) size;
    }

    /** Starts or stops the breathing. A paused player keeps a still, dimmer wave. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        if (playing) start();
        else stop();
        invalidateSelf();
    }

    private void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    private void stop() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
        phase = 0f;
    }

    /** Detaches the animator so a finished player does not keep leaking frames. */
    public void release() {
        playing = false;
        stop();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final float cx = b.exactCenterX();
        final float cy = b.exactCenterY();
        final float half = b.width() / 2f;

        // Neon halo: brightest at the crest of the wave, dim at the trough.
        final float pulse = playing
                ? 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2)
                : 0.22f;
        final float coreR = half * 0.6f;
        final int glow = (int) (50 + 100 * pulse);
        glowPaint.setAlpha(glow);
        canvas.drawCircle(cx, cy, coreR * (1.05f + 0.16f * pulse), glowPaint);
        glowPaint.setAlpha(glow / 2);
        canvas.drawCircle(cx, cy, coreR * (1.55f + 0.3f * pulse), glowPaint);

        // Three strokes riding a travelling wave; the middle one leads.
        final float maxLen = half * 1.3f;
        for (int i = 0; i < BARS; i++) {
            final float x = cx + (i - (BARS - 1) / 2f) * barGap;
            final float wave;
            if (playing) {
                wave = 0.5f + 0.5f * (float) Math.sin(Math.PI * 2 * (phase + i * 0.18f));
            } else {
                wave = i == 1 ? 0.5f : 0.24f;
            }
            float len = maxLen * (0.26f + 0.74f * wave);
            if (i == 1) len *= 1.12f;
            barPaint.setAlpha(playing ? 255 : 165);
            canvas.drawLine(x, cy - len / 2f, x, cy + len / 2f, barPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        barPaint.setAlpha(alpha);
        glowPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        barPaint.setColorFilter(colorFilter);
        glowPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
