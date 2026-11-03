package com.cartify.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The MP3 quality every download is encoded at.
 *
 * V0 was hardcoded because it is the least lossy option, but that is not always what is
 * wanted: a phone with little free space, or a user who genuinely cannot hear the
 * difference on cheap earbuds, should be able to trade detail for size. The choice is
 * stored on the device and read at encode time, so changing it affects the next download
 * without touching anything already saved.
 */
public final class DownloadQuality {

    private static final String PREFS = "cartify_prefs";
    private static final String KEY = "mp3_quality";

    /** One selectable level: what LAME is told, and what to show the user. */
    public static final class Level {
        public final String id;
        public final String label;
        public final String detail;

        Level(String id, String label, String detail) {
            this.id = id;
            this.label = label;
            this.detail = detail;
        }
    }

    /**
     * Ordered best to smallest.
     *
     * The LAME scale runs 0 (best VBR) to 9 (worst). 0 and 2 are variable bitrate, 320 and
     * 192 are constant: VBR spends bits where the music needs them, constant bitrate spends
     * the same everywhere, so 320 is bigger than V0 without necessarily being better.
     */
    public static final Level[] LEVELS = {
            new Level("0", "Best (V0)", "~245 kbps variable \u00b7 largest files"),
            new Level("2", "High (V2)", "~190 kbps variable \u00b7 recommended"),
            new Level("320", "320 kbps", "constant bitrate \u00b7 very large"),
            new Level("192", "192 kbps", "constant bitrate \u00b7 moderate"),
            new Level("128", "128 kbps", "smallest files \u00b7 noticeable loss"),
    };

    /** Best quality, the previous hardcoded behaviour, so an existing install is unchanged. */
    public static final String DEFAULT = "0";

    private DownloadQuality() {
    }

    public static String get(Context context) {
        if (context == null) return DEFAULT;
        String v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DEFAULT);
        return isValid(v) ? v : DEFAULT;
    }

    public static void set(Context context, String id) {
        if (context == null || !isValid(id)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, id).apply();
    }

    /** The label for an id, for menus and confirmation text. */
    public static String labelFor(String id) {
        for (Level l : LEVELS) if (l.id.equals(id)) return l.label;
        return LEVELS[0].label;
    }

    private static boolean isValid(String id) {
        if (id == null) return false;
        for (Level l : LEVELS) if (l.id.equals(id)) return true;
        return false;
    }
}
