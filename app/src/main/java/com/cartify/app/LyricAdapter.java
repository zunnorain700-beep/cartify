package com.cartify.app;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Karaoke lyric sheet.
 *
 * The line being sung is bright, bold and full size; the line above and below stay
 * visible but fade back, so the eye always has context for what is coming next.
 */
public class LyricAdapter extends RecyclerView.Adapter<LyricAdapter.VH> {

    /** Tapping a line jumps playback to that moment. */
    public interface OnLineTap {
        void onLineTap(long timeMs);
    }

    private final List<Lyrics.Line> lines;
    private final OnLineTap onLineTap;
    private int active = -1;

    public LyricAdapter(List<Lyrics.Line> lines) {
        this(lines, null);
    }

    public LyricAdapter(List<Lyrics.Line> lines, OnLineTap onLineTap) {
        this.lines = lines;
        this.onLineTap = onLineTap;
    }

    /** Moves the highlight; the previous line fades back to a neighbour. */
    public void setActive(int index) {
        if (index == active) return;
        int old = active;
        active = index;
        // Neighbours on both sides change appearance too, so repaint the whole window.
        int from = Math.min(old, active);
        int to = Math.max(old, active);
        if (from < 0) from = 0;
        if (to >= lines.size()) to = lines.size() - 1;
        for (int i = Math.max(0, from - 1); i <= Math.min(lines.size() - 1, to + 1); i++) {
            notifyItemChanged(i);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lyric, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Lyrics.Line line = lines.get(position);
        int distance = active < 0 ? Integer.MAX_VALUE : Math.abs(position - active);
        boolean current = position == active;
        boolean neighbour = distance == 1;

        h.text.setText(line.text);
        // Every line is a jump target: the timestamp is exactly where the line starts,
        // so tapping it moves the song to the moment that line is sung.
        h.itemView.setOnClickListener(v -> {
            if (onLineTap != null) onLineTap.onLineTap(line.timeMs);
        });

        if (current) {
            h.text.setTextColor(h.itemView.getContext().getColor(R.color.accent));
            h.text.setTypeface(null, Typeface.BOLD);
            h.text.setAlpha(1f);
            h.text.setTextSize(23f);
        } else if (neighbour) {
            // One line above and below remain readable so the song keeps its context.
            h.text.setTextColor(h.itemView.getContext().getColor(R.color.text_secondary));
            h.text.setTypeface(null, Typeface.BOLD);
            h.text.setAlpha(0.75f);
            h.text.setTextSize(20f);
        } else {
            h.text.setTextColor(h.itemView.getContext().getColor(R.color.text_tertiary));
            h.text.setTypeface(null, Typeface.NORMAL);
            h.text.setAlpha(0.38f);
            h.text.setTextSize(19f);
        }
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(@NonNull View v) {
            super(v);
            text = (TextView) v;
        }
    }
}
