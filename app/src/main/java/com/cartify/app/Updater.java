package com.cartify.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updates driven by GitHub releases.
 *
 * The app publishes its builds to the repository's release section, so the newest release
 * tag is the source of truth for "what version exists". A build older than the newest tag
 * shows an update sheet with the release notes and a download button; the APK is fetched
 * into the private cache and handed to the system installer through a FileProvider, which
 * is the only way the installer can read a file the app owns.
 *
 * The check is deliberately quiet: a failed request, an unreachable network or a missing
 * release all resolve to "no update", never to an error dialog on launch.
 */
public final class Updater {

    private static final String TAG = "Cartify";
    /** Replace with the published repository when it changes. */
    public static final String REPO = "zunnorain700-beep/cartify";

    private static final String API_LATEST = "https://api.github.com/repos/%s/releases/latest";
    private static final String KEY_LAST_PROMPT = "update_prompted_tag";
    private static final String KEY_LAST_CHECK = "update_checked_at";
    /** One check per six hours is plenty and keeps the API well inside its rate limit. */
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** What a release looks like once it has been read off the API. */
    public static final class Release {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String apkUrl = "";
        public long size = 0;

        /** Numeric form of the tag, so 1.2.10 sorts above 1.2.9. */
        public int[] numbers() {
            String digits = tag.replaceAll("[^0-9.]", "");
            String[] parts = digits.isEmpty() ? new String[0] : digits.split("\\.");
            int[] out = new int[Math.max(3, parts.length)];
            for (int i = 0; i < parts.length && i < out.length; i++) {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ignored) {
                    out[i] = 0;
                }
            }
            return out;
        }
    }

    /** Called on the main thread with the newer release, or with null when up to date. */
    public interface OnResult {
        void onResult(Release newer);
    }

    private Updater() {
    }

    /** Current version as {major, minor, patch}. */
    public static int[] currentVersion(Context context) {
        String name = appVersionName(context);
        String digits = name.replaceAll("[^0-9.]", "");
        String[] parts = digits.isEmpty() ? new String[0] : digits.split("\\.");
        int[] out = new int[Math.max(3, parts.length)];
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String appVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private static boolean isNewer(int[] remote, int[] local) {
        for (int i = 0; i < Math.min(remote.length, local.length); i++) {
            if (remote[i] != local[i]) return remote[i] > local[i];
        }
        return false;
    }

    /**
     * Checks for a newer release and reports it, at most once per {@code CHECK_INTERVAL_MS}
     * unless {@code force} is set.
     */
    public static void check(Context context, boolean force, OnResult callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            if (callback != null) MAIN.post(() -> callback.onResult(null));
            return;
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

        POOL.execute(() -> {
            Release newer = null;
            try {
                JSONObject o = fetchLatest();
                if (o != null) {
                    Release r = parse(o);
                    if (!r.tag.isEmpty() && !r.apkUrl.isEmpty()
                            && isNewer(r.numbers(), currentVersion(app))) {
                        newer = r;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
            }
            final Release result = newer;
            if (callback != null) MAIN.post(() -> callback.onResult(result));
        });
    }

    /** True when this exact release has already been offered, so relaunching is quiet. */
    public static boolean alreadyOffered(Context context, String tag) {
        return tag != null && tag.equals(context
                .getSharedPreferences("updater", Context.MODE_PRIVATE)
                .getString(KEY_LAST_PROMPT, ""));
    }

    public static void markOffered(Context context, String tag) {
        context.getSharedPreferences("updater", Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_PROMPT, tag == null ? "" : tag).apply();
    }

    private static JSONObject fetchLatest() throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(String.format(Locale.US, API_LATEST, REPO))
                    .openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Cartify-Updater");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Picks the release's first .apk asset. */
    private static Release parse(JSONObject o) {
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets == null) return r;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                r.apkUrl = a.optString("browser_download_url", "");
                r.size = a.optLong("size", 0L);
                break;
            }
        }
        return r;
    }

    /** Progress of the download, delivered on the main thread. */
    public interface DownloadCallback {
        void onProgress(int percent);

        void onReady(File apk);

        void onFailed(String message);
    }

    /** Downloads the release APK into cache/updates and reports when it can be installed. */
    public static void download(Context context, Release release, DownloadCallback cb) {
        final Context app = context.getApplicationContext();
        POOL.execute(() -> {
            HttpURLConnection c = null;
            File out = null;
            try {
                File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update folder");
                out = new File(dir, "cartify-" + release.tag + ".apk");

                c = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Cartify-Updater");
                // GitHub serves release assets through a redirect to its CDN.
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("Server returned " + code);
                long total = c.getContentLengthLong();
                if (total <= 0) total = release.size;

                try (InputStream in = c.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long read = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        read += n;
                        if (total > 0) {
                            int pct = (int) (read * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                MAIN.post(() -> cb.onProgress(p));
                            }
                        }
                    }
                    fos.flush();
                }
                final File ready = out;
                MAIN.post(() -> cb.onReady(ready));
            } catch (Exception e) {
                if (out != null) out.delete();
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage();
                MAIN.post(() -> cb.onFailed(msg));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Android 8+ gates this behind "install unknown apps" per-app consent, so the first
     * attempt sends the user to that settings page instead of silently doing nothing.
     */
    public static void install(Activity activity, File apk) {
        if (activity == null || apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settingsIntent);
            } catch (Exception ignored) {
            }
            android.widget.Toast.makeText(activity, "Allow Cartify to install apps, then tap Update again", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, "Could not open the installer", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
