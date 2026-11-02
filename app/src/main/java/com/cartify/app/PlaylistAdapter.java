package com.cartify.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The playlist shelf: each row opens a playlist, its overflow opens options. */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {

    public interface Actions {
        void onOpen(Playlist playlist);

        void onMore(View anchor, Playlist playlist);
    }

    private final List<Playlist> items = new ArrayList<>();
    private final Actions actions;

    public PlaylistAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<Playlist> playlists) {
        items.clear();
        if (playlists != null) items.addAll(playlists);
        notifyDataSetChanged();
    }

    public List<Playlist> items() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Playlist p = items.get(position);
        h.name.setText(p.name);
        h.meta.setText(p.summary());
        PlaylistArt.load(h.itemView.getContext(), p, h.cover, h.fallback);
        h.backdrop.setImageDrawable(null);
        h.itemView.setOnClickListener(v -> actions.onOpen(p));
        h.more.setOnClickListener(v -> actions.onMore(v, p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final ImageView backdrop;
        final ImageView fallback;
        final TextView name;
        final TextView meta;
        final ImageButton more;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.plCover);
            backdrop = v.findViewById(R.id.plBackdrop);
            fallback = v.findViewById(R.id.plCoverFallback);
            name = v.findViewById(R.id.plName);
            meta = v.findViewById(R.id.plMeta);
            more = v.findViewById(R.id.plMore);
        }
    }
}
