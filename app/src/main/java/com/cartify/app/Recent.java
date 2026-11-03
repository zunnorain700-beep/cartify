package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The last songs actually played, newest first.
 *
 * Kept separate from the search history: this records what was listened to, not what was
 * looked for. It is the one piece of the home screen that reflects the user rather than a
 * public chart, and it is what makes reopening the app show something familiar.
 *
 * Stored on the device only, as a small JSON list of uris; the Track itself is re-read
 * from the library, so a deleted song simply disappears instead of leaving a dead row.
 */
public final class Recent {

    private static final String PREFS = "cartify_prefs";
    private static final String KEY = "recent_played";
    private static final int MAX = 12;

    private Recent() {
    }

    /** Records a play; a repeat moves the song to the front instead of duplicating it. */
    public static synchronized void remember(Context context, String uri) {
        if (context == null || uri == null || uri.isEmpty()) return;
        List<String> list = raw(context);
        list.remove(uri);
        list.add(0, uri);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(context, list);
    }

    /** The remembered uris, newest first. */
    public static synchronized List<String> all(Context context) {
        return raw(context);
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply();
    }

    private static List<String> raw(Context context) {
        List<String> out = new ArrayList<>();
        try {
            String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            if (json == null || json.isEmpty()) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String uri = arr.optString(i, "");
                if (!uri.isEmpty()) out.add(uri);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context context, List<String> list) {
        try {
            JSONArray arr = new JSONArray();
            for (String uri : list) arr.put(uri);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Convenience for a JSON payload kept by an older build; unused but harmless. */
    static JSONObject empty() {
        return new JSONObject();
    }
}
