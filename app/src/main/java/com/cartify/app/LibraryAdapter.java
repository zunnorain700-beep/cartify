package com.cartify.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saved MP3s from Music/Cartify. */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.VH> {

    public interface Actions {
        void onPlay(LibraryStore.Item item, int index);

        void onDelete(LibraryStore.Item item, int index);
    }

    private final List<LibraryStore.Item> items = new ArrayList<>();
    private final Actions actions;

    public LibraryAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<LibraryStore.Item> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<LibraryStore.Item> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_library, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LibraryStore.Item it = items.get(position);
        h.title.setText(it.title == null || it.title.isEmpty() ? it.displayName : it.title);
        String artist = it.artist == null || it.artist.isEmpty() ? "Unknown artist" : it.artist;
        long secs = it.duration / 1000;
        h.subtitle.setText(String.format(Locale.US, "%s  ·  %d:%02d  ·  MP3", artist, secs / 60, secs % 60));
        h.play.setOnClickListener(v -> actions.onPlay(it, h.getAdapterPosition()));
        h.delete.setOnClickListener(v -> actions.onDelete(it, h.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageButton play;
        final ImageButton delete;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.libTitle);
            subtitle = v.findViewById(R.id.libSubtitle);
            play = v.findViewById(R.id.libPlay);
            delete = v.findViewById(R.id.libDelete);
        }
    }
}
