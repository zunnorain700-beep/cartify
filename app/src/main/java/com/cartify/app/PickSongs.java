package com.cartify.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "add songs" sheet: the whole library, filtered live, with checkmarks.
 *
 * Songs are matched by their local uri, so the same song can never be added twice and
 * a re-opened sheet shows what is already in the playlist as ticked.
 */
public final class PickSongs {

    public interface OnPicked {
        void onPicked(List<Track> tracks);
    }

    private PickSongs() {
    }

    public static void show(Activity activity, List<Track> library, Playlist target, OnPicked done) {
        if (activity == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_add_songs, null, false);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        TextView head = content.findViewById(R.id.pickHead);
        EditText search = content.findViewById(R.id.pickSearch);
        RecyclerView list = content.findViewById(R.id.pickList);
        TextView empty = content.findViewById(R.id.pickEmpty);
        TextView count = content.findViewById(R.id.pickCount);
        TextView cancel = content.findViewById(R.id.pickCancel);
        TextView add = content.findViewById(R.id.pickAdd);

        head.setText("Add to " + (target == null ? "playlist" : target.name));

        final Set<String> selected = new HashSet<>();
        // Pre-tick what the playlist already holds, so the sheet tells the truth.
        if (target != null) {
            for (Playlist.Entry e : target.songs) selected.add(e.uri);
        }

        final List<Track> all = new ArrayList<>();
        if (library != null) all.addAll(library);

        final Picker adapter = new Picker(selected, count, add);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        adapter.submit(all);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        refreshCount(count, add, selected);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.US);
                List<Track> filtered = new ArrayList<>();
                for (Track t : all) {
                    if (q.isEmpty()
                            || (t.title != null && t.title.toLowerCase(Locale.US).contains(q))
                            || (t.artist != null && t.artist.toLowerCase(Locale.US).contains(q))) {
                        filtered.add(t);
                    }
                }
                adapter.submit(filtered);
                empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        cancel.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> {
            if (selected.isEmpty()) return;
            List<Track> picked = new ArrayList<>();
            for (Track t : all) if (t.localUri != null && selected.contains(t.localUri)) picked.add(t);
            dialog.dismiss();
            if (done != null) done.onPicked(picked);
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f);
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.66f;
            window.setAttributes(lp);
        }
        dialog.show();

        float lift = activity.getResources().getDisplayMetrics().density * 90f;
        content.setTranslationY(lift);
        content.setAlpha(0f);
        content.animate().translationY(0f).alpha(1f)
                .setDuration(270)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private static void refreshCount(TextView count, TextView add, Set<String> selected) {
        if (count != null) count.setText(selected.size() + " selected");
        if (add != null) {
            add.setAlpha(selected.isEmpty() ? 0.45f : 1f);
            add.setEnabled(!selected.isEmpty());
        }
    }

    /** Rows with a tap-to-toggle checkmark. */
    private static class Picker extends RecyclerView.Adapter<Picker.VH> {

        private final List<Track> items = new ArrayList<>();
        private final Set<String> selected;
        private final TextView count;
        private final TextView add;

        Picker(Set<String> selected, TextView count, TextView add) {
            this.selected = selected;
            this.count = count;
            this.add = add;
        }

        void submit(List<Track> tracks) {
            items.clear();
            if (tracks != null) items.addAll(tracks);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pick_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Track t = items.get(position);
            h.title.setText(t.title);
            h.subtitle.setText(t.artist == null || t.artist.isEmpty() ? "Unknown artist" : t.artist);
            Art.load(h.itemView.getContext(), t, h.art);
            final String uri = t.localUri == null ? "" : t.localUri;
            boolean on = selected.contains(uri);
            h.check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            h.itemView.setAlpha(on ? 1f : 0.92f);
            h.itemView.setOnClickListener(v -> {
                if (uri.isEmpty()) return;
                if (!selected.remove(uri)) selected.add(uri);
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
                refreshCount(count, add, selected);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView art;
            final ImageView check;
            final TextView title;
            final TextView subtitle;

            VH(@NonNull View v) {
                super(v);
                art = v.findViewById(R.id.pickArt);
                check = v.findViewById(R.id.pickCheck);
                title = v.findViewById(R.id.pickTitle);
                subtitle = v.findViewById(R.id.pickSubtitle);
            }
        }
    }
}
