package com.cartify.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A flowing wave that doubles as the download progress bar.
 *
 * A plain horizontal bar sits motionless between yt-dlp updates, which reads as a stall.
 * This draws a filled wave whose crest keeps travelling left to right while the fill line
 * advances, so the row always looks busy even when the percentage has not moved. The wave
 * amplitude breathes with the same clock, which makes a stalled download look like it is
 * waiting rather than broken.
 */
public class WaveProgressView extends View {

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final RectF body = new RectF();

    private float progress;
    private float shownProgress;
    private float phase;
    private ValueAnimator anim;
    private boolean running;
    private int accent = 0xFF4CE87F;

    public WaveProgressView(Context c) {
        this(c, null);
    }

    public WaveProgressView(Context c, AttributeSet a) {
        super(c, a);
        wavePaint.setStyle(Paint.Style.FILL);
        crestPaint.setStyle(Paint.Style.STROKE);
        crestPaint.setStrokeWidth(dp(1.4f));
        crestPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setAccent(int color) {
        accent = color;
        rebuildShader();
        invalidate();
    }

    /** 0..100. The drawn level eases toward this so a jump never snaps. */
    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(100f, value));
        invalidate();
    }

    public float getProgressValue() {
        return progress;
    }

    public void start() {
        if (running) return;
        running = true;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2600);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase += 0.055f;
            if (phase > (float) (Math.PI * 2)) phase -= (float) (Math.PI * 2);
            shownProgress += (progress - shownProgress) * 0.16f;
            invalidate();
        });
        anim.start();
    }

    public void stop() {
        running = false;
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        shownProgress = progress;
        start();
    }

    private void rebuildShader() {
        if (getWidth() <= 0) return;
        wavePaint.setShader(new LinearGradient(0, 0, getWidth(), 0,
                new int[]{0xCC38FF8C, 0xFF4CE87F, 0xCC2FD873},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildShader();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float radius = h / 2f;
        float fill = w * (shownProgress / 100f);

        // The empty track. It sits *behind* the fill and is never visible through it,
        // because the fill below is a solid rect rather than a wavy silhouette.
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(0x22FFFFFF);
        RectF trackRect = new RectF(0, 0, w, h);
        canvas.drawRoundRect(trackRect, radius, radius, track);

        if (fill <= 0) return;

        int save = canvas.save();
        // Everything below is clipped to the downloaded width, so the level reads at a
        // glance while the decoration inside can animate freely.
        canvas.clipRect(0f, 0f, fill, h);

        RectF bodyRect = new RectF(0, 0, w, h);
        canvas.drawRoundRect(bodyRect, radius, radius, wavePaint);

        // A constant-amplitude wave travels horizontally inside the fill.
        // The amplitude never changes, so the bar cannot appear to bob up and down;
        // only the crests move, which reads as flow.
        float amp = h * 0.26f;
        float step = Math.max(2f, w / 80f);
        Path ribbon = new Path();
        boolean first = true;
        for (float x = 0; x <= w; x += step) {
            double y = Math.sin((x / w) * Math.PI * 3.2f + phase) * amp;
            float py = (h / 2f) + (float) y;
            if (first) {
                ribbon.moveTo(x, py);
                first = false;
            } else {
                ribbon.lineTo(x, py);
            }
        }
        crestPaint.setColor(0x59EAFFF2);
        crestPaint.setStrokeWidth(dp(2.2f));
        canvas.drawPath(ribbon, crestPaint);

        // A second crest, offset and fainter, gives the surface some depth.
        Path ribbon2 = new Path();
        first = true;
        for (float x = 0; x <= w; x += step) {
            double y = Math.sin((x / w) * Math.PI * 3.2f + phase + 1.9f) * amp * 0.8f;
            float py = (h / 2f) + (float) y;
            if (first) {
                ribbon2.moveTo(x, py);
                first = false;
            } else {
                ribbon2.lineTo(x, py);
            }
        }
        crestPaint.setColor(0x2EFFFFFF);
        canvas.drawPath(ribbon2, crestPaint);

        canvas.restoreToCount(save);

        // Rounded cap on the leading edge so a partial bar still looks finished.
        if (shownProgress > 0.5f && shownProgress < 99.5f) {
            Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
            cap.setColor(0xFF6CF59A);
            canvas.drawCircle(fill - radius * 0.4f, h / 2f, radius * 0.82f, cap);
        }
    }
}
