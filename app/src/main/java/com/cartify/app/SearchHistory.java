package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The last few search terms, kept on the device only.
 *
 * A search app that forgets what you just typed makes you retype it, which is the single
 * most common annoyance in a search box. This stores nothing but the words themselves — no
 * results, no timing, no identifiers — and caps the list so it cannot grow.
 */
public final class SearchHistory {

    private static final String PREFS = "cartify_search";
    private static final String KEY = "history";
    private static final String SEP = "\u001f";
    private static final int MAX = 8;

    private SearchHistory() {
    }

    public static List<String> all(Context context) {
        List<String> out = new ArrayList<>();
        if (context == null) return out;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(SEP)) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Moves {@code query} to the front, dropping case-insensitive duplicates. */
    public static void remember(Context context, String query) {
        if (context == null) return;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;

        List<String> list = all(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equalsIgnoreCase(q)) list.remove(i);
        }
        list.add(0, q);
        while (list.size() > MAX) list.remove(list.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (String v : list) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(v);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply();
    }

    public static void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
