package com.cartify.app;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Track list with artwork loading, animated progress and per-row actions. */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {

    public interface Actions {
        void onDownloadClicked(Track track, int index);

        void onCancelClicked(Track track, int index);

        void onPlayClicked(Track track, int index);
    }

    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final java.util.Map<String, List<ImageView>> WAITING = new java.util.HashMap<>();

    private final List<Track> items = new ArrayList<>();
    private final Actions actions;

    public TrackAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Track> tracks) {
        items.clear();
        if (tracks != null) items.addAll(tracks);
        notifyDataSetChanged();
    }

    public void update(int index, int status, int percent, String detail, String uri, String error) {
        if (index < 0 || index >= items.size()) return;
        Track t = items.get(index);
        t.status = status;
        t.progress = percent;
        if (uri != null) t.localUri = uri;
        if (error != null) t.error = error;
        // A full rebind re-ran the artwork fade on every progress tick, which read as a
        // shimmer over the cover mid-download. Progress carries a payload now, and that
        // path only touches the bar and the label.
        notifyItemChanged(index, PAYLOAD_PROGRESS);
    }

    /** Puts a cancelled row back to idle without flashing an error. */
    public void reset(int index) {
        if (index < 0 || index >= items.size()) return;
        Track t = items.get(index);
        t.status = Track.IDLE;
        t.progress = 0;
        t.error = "";
        notifyItemChanged(index);
    }

    public List<Track> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false);
        return new VH(v);
    }

    /** Marks a partial update that only repaints progress, never the cover. */
    private static final Object PAYLOAD_PROGRESS = new Object();

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgress(h, items.get(position));
            return;
        }
        onBindViewHolder(h, position);
    }

    /** Repaints only the progress widgets, leaving artwork and text untouched. */
    private void bindProgress(VH h, Track t) {
        boolean busy = t.status == Track.QUEUED || t.status == Track.ACTIVE;
        h.progress.setVisibility(busy || t.status == Track.DONE ? View.VISIBLE : View.INVISIBLE);
        h.ring.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.progress.setProgress(t.progress);
        if (busy) h.progress.start(); else h.progress.stop();
        animate(h.ring, t.progress);
        if (t.status == Track.ACTIVE) h.percent.setText(t.progress + "%");
        else if (t.status == Track.QUEUED) h.percent.setText("queued");
        else if (t.status == Track.DONE) h.percent.setText("saved");
        else if (t.status == Track.ERROR) h.percent.setText(t.error == null || t.error.isEmpty() ? "failed" : t.error);
        else h.percent.setText("");
        h.action.setImageResource(t.status == Track.QUEUED || t.status == Track.ACTIVE
                ? R.drawable.ic_close : (t.status == Track.DONE ? R.drawable.ic_play : R.drawable.ic_download));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Track t = items.get(position);
        h.title.setText(t.title);
        String sub = t.artist;
        if (t.durationText().length() > 0) sub = sub + "  ·  " + t.durationText();
        h.subtitle.setText(sub);
        // Clear the artwork only when the URL really changed. Blanking it on every
        // rebind made an already-visible cover blink back in.
        String wantedCover = t.coverUrl == null ? "" : t.coverUrl;
        if (!wantedCover.equals(h.cover.getTag(R.id.cover))) {
            h.cover.setTag(R.id.cover, wantedCover);
            h.cover.setImageDrawable(null);
            h.cover.setAlpha(0f);
            loadCover(h.cover, wantedCover);
        }

        boolean busy = t.status == Track.QUEUED || t.status == Track.ACTIVE;
        h.progress.setVisibility(busy || t.status == Track.DONE ? View.VISIBLE : View.INVISIBLE);
        h.ring.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.progress.setProgress(t.progress);
        if (t.status == Track.ACTIVE) h.progress.start(); else h.progress.stop();
        animate(h.ring, t.progress);

        switch (t.status) {
            case Track.QUEUED:
                h.percent.setText("queued");
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription("Cancel download");
                h.action.setEnabled(true);
                break;
            case Track.ACTIVE:
                h.percent.setText(t.progress + "%");
                h.action.setImageResource(R.drawable.ic_close);
                h.action.setContentDescription("Cancel download");
                h.action.setEnabled(true);
                break;
            case Track.DONE:
                h.percent.setText("saved");
                h.action.setImageResource(R.drawable.ic_play);
                h.action.setContentDescription("Play");
                h.action.setEnabled(true);
                break;
            case Track.ERROR:
                h.percent.setText("failed");
                h.subtitle.setText(t.error == null || t.error.isEmpty()
                        ? "Download failed — tap to retry"
                        : t.error + " · tap to retry");
                h.action.setImageResource(R.drawable.ic_download);
                h.action.setContentDescription("Retry");
                h.action.setEnabled(true);
                break;
            default:
                h.percent.setText("");
                h.action.setImageResource(R.drawable.ic_download);
                h.action.setContentDescription("Download");
                h.action.setEnabled(true);
                break;
        }

        h.action.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (t.status == Track.DONE) actions.onPlayClicked(t, pos);
            else if (t.status == Track.QUEUED || t.status == Track.ACTIVE) actions.onCancelClicked(t, pos);
            else actions.onDownloadClicked(t, pos);
        });
    }

    /**
     * Slowly inches a bar that is waiting on a silent stage.
     *
     * The conversion step reports no progress at all, so a bar parked at 92% looks like a
     * hang. Creeping toward 99% keeps it visibly alive; a real update retargets the same
     * animator, so the creep never fights the true value.
     */
    private static void creep(ProgressBar bar, int value) {
        ObjectAnimator old = (ObjectAnimator) bar.getTag(R.id.progress);
        if (old != null && old.isRunning()) return;
        ObjectAnimator anim = ObjectAnimator.ofInt(bar, "progress", value, 99);
        anim.setDuration(9000);
        anim.setInterpolator(new LinearInterpolator());
        bar.setTag(R.id.progress, anim);
        anim.start();
    }

    /**
     * Progress glides to its new value instead of snapping between yt-dlp updates.
     *
     * The previous version bailed out whenever an animation was still running, which
     * silently dropped every update that arrived mid-flight: the bar sat at one number
     * and then leapt when the next animation finally got through. Now the running
     * animation is retargeted from wherever it currently is, so no update is ever lost.
     */
    private static void animate(ProgressBar bar, int value) {
        ObjectAnimator old = (ObjectAnimator) bar.getTag(R.id.progress);
        int current = bar.getProgress();
        if (old != null) {
            if (old.isRunning()) {
                current = (int) Math.round(((Number) old.getAnimatedValue()).floatValue());
            }
            old.cancel();
        }
        if (current == value) {
            bar.setProgress(value);
            return;
        }
        ObjectAnimator anim = ObjectAnimator.ofInt(bar, "progress", current, value);
        // A linear, slightly longer glide reads as steady motion rather than a stall.
        anim.setDuration(420);
        anim.setInterpolator(new LinearInterpolator());
        bar.setTag(R.id.progress, anim);
        anim.start();
    }

    private void loadCover(ImageView view, String url) {
        view.setTag(url);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = Net.bitmap(url);
        if (cached != null) {
            reveal(view, cached);
            return;
        }
        synchronized (WAITING) {
            List<ImageView> list = WAITING.get(url);
            if (list == null) {
                list = new ArrayList<>();
                WAITING.put(url, list);
            }
            if (!list.contains(view)) list.add(view);
        }
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(url)) return;   // already fetching this artwork
        }
        IMAGES.execute(() -> {
            final Bitmap b = Net.bitmap(url);
            List<ImageView> targets;
            synchronized (WAITING) {
                targets = WAITING.remove(url);
                IN_FLIGHT.remove(url);
            }
            if (b != null && targets != null) {
                for (final ImageView target : targets) {
                    MAIN.post(() -> {
                        if (url.equals(target.getTag())) reveal(target, b);
                    });
                }
            }
        });
    }

    /** Fades artwork in so the list does not flicker while scrolling. */
    private static void reveal(ImageView view, Bitmap bitmap) {
        view.setImageBitmap(bitmap);
        view.animate().alpha(1f).setDuration(180).start();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView subtitle;
        final TextView percent;
        final WaveProgressView progress;
        final ProgressBar ring;
        final ImageButton action;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            percent = v.findViewById(R.id.percent);
            progress = v.findViewById(R.id.progress);
            ring = v.findViewById(R.id.ring);
            action = v.findViewById(R.id.action);
        }
    }
}
