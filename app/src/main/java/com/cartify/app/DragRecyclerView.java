package com.cartify.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView that can report a drag across its rows, and keeps scrolling while the
 * finger is held near an edge.
 *
 * Drag-to-select has to see every move of a gesture, including moves that happen while the
 * gesture is already being handled. {@link RecyclerView.OnItemTouchListener} cannot do that:
 * as soon as its {@code onInterceptTouchEvent} returns true the framework stops calling it,
 * so the drag died after the first move. {@link #dispatchTouchEvent} is the one place
 * guaranteed to see every event, so the observation happens here.
 *
 * Auto-scroll exists because a drag cannot otherwise reach past the visible rows: holding a
 * finger at the bottom edge scrolls the list and re-extends the selection on every step, so
 * a run of songs taller than the screen can still be picked in one gesture.
 */
public class DragRecyclerView extends RecyclerView {

    /** Receives the raw gesture before the list acts on it. */
    public interface DragObserver {
        /**
         * @return true when the gesture was consumed as a drag, so the list must not scroll
         *         and no row may react to it.
         */
        boolean onDragTouch(MotionEvent event);

        /**
         * The list scrolled itself during a drag; the row under the finger has changed and
         * the selection range should be extended to it.
         */
        void onDragAutoScrolled(float rawY);
    }

    private DragObserver observer;

    /** How close to an edge the finger has to be before the list starts scrolling. */
    private final float edgeZone;
    /** Pixels per tick at the very edge; shallower holds scroll proportionally slower. */
    private final float maxStep;
    private static final long TICK_MS = 16L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float lastRawY;
    private boolean autoScrolling;

    private final Runnable scroller = new Runnable() {
        @Override
        public void run() {
            if (!autoScrolling) return;
            int step = stepFor(lastRawY);
            if (step != 0) {
                scrollBy(0, step);
                if (observer != null) observer.onDragAutoScrolled(lastRawY);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public DragRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public DragRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DragRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        float density = getResources().getDisplayMetrics().density;
        this.edgeZone = 72f * density;
        this.maxStep = 14f * density;
    }

    public void setDragObserver(DragObserver observer) {
        this.observer = observer;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (observer != null && observer.onDragTouch(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                lastRawY = event.getRawY();
                updateAutoScroll();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopAutoScroll();
            }
            // The gesture belongs to the drag: no scrolling, no row reaction.
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            stopAutoScroll();
        }
        return super.dispatchTouchEvent(event);
    }

    private void updateAutoScroll() {
        boolean shouldScroll = stepFor(lastRawY) != 0;
        if (shouldScroll && !autoScrolling) {
            autoScrolling = true;
            handler.post(scroller);
        } else if (!shouldScroll && autoScrolling) {
            stopAutoScroll();
        }
    }

    private void stopAutoScroll() {
        autoScrolling = false;
        handler.removeCallbacks(scroller);
    }

    /** Pixels to scroll this tick, signed; 0 when the finger is away from both edges. */
    private int stepFor(float rawY) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        float y = rawY - loc[1];
        float height = getHeight();
        if (height <= 0) return 0;

        if (y > height - edgeZone) {
            if (!canScrollVertically(1)) return 0;
            float depth = Math.min(edgeZone, y - (height - edgeZone));
            return (int) Math.max(1f, maxStep * (depth / edgeZone));
        }
        if (y < edgeZone) {
            if (!canScrollVertically(-1)) return 0;
            float depth = Math.min(edgeZone, edgeZone - y);
            return -(int) Math.max(1f, maxStep * (depth / edgeZone));
        }
        return 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAutoScroll();
        super.onDetachedFromWindow();
    }

    /** The child row under {@code rawY}, or -1 when the finger is past the last row. */
    public int positionUnder(float rawY) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) continue;
            int[] loc = new int[2];
            child.getLocationOnScreen(loc);
            if (rawY >= loc[1] && rawY <= loc[1] + child.getHeight()) {
                return getChildAdapterPosition(child);
            }
        }
        return -1;
    }
}
