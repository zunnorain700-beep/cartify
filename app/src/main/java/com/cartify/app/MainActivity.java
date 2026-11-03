package com.cartify.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cartify home: paste a Spotify link, download, browse the library, play. */
public class MainActivity extends AppCompatActivity implements ProgressBus.Listener, Playback.Listener {

    private static final int REQ_NOTIFICATIONS = 91;
    private static final int REQ_DELETE = 92;
    /** A bulk delete that has to go through the system consent dialog. */
    private static final int REQ_DELETE_BULK = 93;

    private Track pendingDeleteTrack;
    private String pendingDeleteUri = "";
    /** Uris waiting on the Android 11+ delete consent dialog for a multi-row delete. */
    private final List<String> pendingBulkDelete = new ArrayList<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText linkInput;
    private ImageButton clearButton;
    private Button resolveButton;
    private TextView statusText;
    private LinearLayout collectionBar;
    private ImageView collectionCover;
    private TextView collectionName;
    private TextView collectionMeta;
    private Button downloadAllButton;
    private RecyclerView list;
    private LinearLayout emptyState;

    private LinearLayout homeView;
    private LinearLayout libraryView;
    private LinearLayout playlistsView;
    private TextView playlistsStatus;
    private RecyclerView playlistsList;
    private LinearLayout playlistsEmpty;
    private PlaylistAdapter playlistAdapter;
    private TextView libraryStatus;
    private DragRecyclerView songsList;
    private LinearLayout libraryEmpty;
    private Button grantAccessButton;
    private TextView cancelAllButton;
    private TextView shuffleAllButton;
    private EditText librarySearch;

    /** Every saved song, before the library search filter is applied. */
    private final List<Track> libraryAll = new ArrayList<>();
    private String libraryQuery = "";

    /** Library ordering: 0 = newest first, 1 = title, 2 = artist. */
    private int librarySort = 0;
    private TextView librarySortButton;

    /** Bulk-selection chrome, shown in place of the library header while songs are picked. */
    private View selectionBar;
    private TextView selectionCount;
    private LinearLayout libraryHeader;

    private View miniPlayer;
    private ImageView miniArt;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlayPause;
    private ProgressBar miniProgress;
    private LinearLayout navHome;
    private LinearLayout navLibrary;
    private ImageView navHomeIcon;
    private TextView navHomeLabel;
    private ImageView navLibraryIcon;
    private TextView navLibraryLabel;
    private LinearLayout navPlaylists;
    private ImageView navPlaylistsIcon;
    private TextView navPlaylistsLabel;

    private TrackAdapter adapter;
    private SongAdapter songAdapter;
    private SpotifyCollection collection;
    private Playback playback;

    /** The launch animation runs once per process, not on every return from the player. */
    private static boolean SPLASH_SHOWN = false;
    /** Unused placeholder kept for source compatibility. */
    private SplashView splash;

    private final Runnable miniTicker = new Runnable() {
        @Override
        public void run() {
            updateMiniProgress();
            main.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        playback = Playback.get(this);
        bindViews();
        wireActions();
        setupLists();

        ProgressBus.register(this);
        // A download may have started before this activity existed (or kept running while
        // the app was in the background), so pick up its live state immediately.
        ProgressBus.replay(this);
        playback.addListener(this);
        warmUpDownloader();
        requestNotificationPermission();
        handleShareIntent(getIntent());
        refreshLibrary();
        renderMini();
        main.post(miniTicker);
        playIntro();
        checkForUpdates();
    }

    // ---------------------------------------------------------------- updates

    /**
     * Looks for a newer GitHub release and offers it.
     *
     * Runs after the intro so it never competes with the first paint, and stays silent for
     * the common case: no network, no release, or a release the user has already dismissed.
     */
    private void checkForUpdates() {
        Updater.check(this, false, release -> {
            if (release == null || isFinishing()) return;
            if (Updater.alreadyOffered(this, release.tag)) return;
            showUpdateSheet(release);
        });
    }

    /**
     * App-wide actions, reachable without opening a song first.
     *
     * Everything here was previously buried: the update check only existed inside a
     * single song's overflow menu, and there was no way at all to see how much space the
     * library occupies or how many songs are on the device.
     */
    private void showAppMenu(View anchor) {
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_download, "Check for updates",
                this::checkForUpdatesManually));
        items.add(new MenuSheet.Item(R.drawable.ic_info, "Library info",
                this::showLibraryInfo));
        items.add(new MenuSheet.Item(R.drawable.ic_library, "Rescan music folder",
                () -> {
                    refreshLibrary();
                    toast("Rescanning Music/Cartify");
                }));
        MenuSheet.show(this, "Cartify", "Version " + appVersionName(),
                items.toArray(new MenuSheet.Item[0]));
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * How many songs are saved and how much room they take.
     *
     * The size is summed on a worker thread: a large library is thousands of stat calls,
     * and doing that on the main thread is exactly the kind of thing that produces a
     * visible freeze for no reason.
     */
    private void showLibraryInfo() {
        final int count = libraryAll.size();
        io.execute(() -> {
            long bytes = 0L;
            for (Track t : new ArrayList<>(libraryAll)) {
                if (t.localUri == null || t.localUri.isEmpty()) continue;
                try (android.content.res.AssetFileDescriptor fd =
                             getContentResolver().openAssetFileDescriptor(
                                     android.net.Uri.parse(t.localUri), "r")) {
                    if (fd != null) bytes += Math.max(0L, fd.getLength());
                } catch (Exception ignored) {
                }
            }
            final long total = bytes;
            main.post(() -> {
                String size = total >= 1024L * 1024L * 1024L
                        ? String.format(Locale.US, "%.1f GB", total / (1024.0 * 1024 * 1024))
                        : String.format(Locale.US, "%.0f MB", total / (1024.0 * 1024));
                MenuSheet.show(this, "Library info",
                        count + (count == 1 ? " song" : " songs") + " \u00b7 " + size
                                + " in Music/Cartify",
                        new MenuSheet.Item(R.drawable.ic_close, "Close", () -> {
                        }));
            });
        });
    }

    /**
     * The user asked for an update check explicitly.
     *
     * The automatic check is deliberately silent, which is right on launch but wrong when
     * someone taps the item: saying nothing looks broken. This one always answers, either
     * with the update sheet or with "you are up to date".
     */
    private void checkForUpdatesManually() {
        toast("Checking for updates\u2026");
        Updater.check(this, true, release -> {
            if (isFinishing()) return;
            if (release == null) {
                toast("Cartify is up to date");
            } else {
                // A manual check ignores the \"already offered\" flag: asking again is a
                // request to see it again.
                showUpdateSheet(release);
            }
        });
    }

    /** Builds the "update available" sheet with the release notes and a download button. */
    private void showUpdateSheet(final Updater.Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 300) notes = notes.substring(0, 300).trim() + "\u2026";
        String subtitle = notes.isEmpty()
                ? "A newer version is available."
                : notes;
        MenuSheet.show(this, "Update available \u00b7 " + release.tag, subtitle,
                new MenuSheet.Item(R.drawable.ic_download, "Download and install",
                        () -> downloadUpdate(release)),
                new MenuSheet.Item(R.drawable.ic_close, "Later",
                        () -> Updater.markOffered(this, release.tag)));
    }

    /** Downloads the release APK, showing progress in the status line, then installs it. */
    private void downloadUpdate(final Updater.Release release) {
        Updater.markOffered(this, release.tag);
        statusText.setText("Downloading update\u2026");
        Updater.download(this, release, new Updater.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                statusText.setText("Downloading update\u2026 " + percent + "%");
            }

            @Override
            public void onReady(java.io.File apk) {
                statusText.setText("Update downloaded");
                Updater.install(MainActivity.this, apk);
            }

            @Override
            public void onFailed(String message) {
                statusText.setText("Update failed: " + message);
                toast("Could not download the update");
            }
        });
    }

    /**
     * Plays the logo morph once, on a cold open.
     *
     * The activity is fully built underneath the splash, so dismissing it reveals a
     * ready screen instead of a half-inflated one.
     */
    private void playIntro() {
        splash = findViewById(R.id.splash);
        if (splash == null) return;
        if (SPLASH_SHOWN) {
            splash.setVisibility(View.GONE);
            return;
        }
        SPLASH_SHOWN = true;
        // The intro is a visual beat only; no startup sound is played.
        splash.setListener(new SplashView.Listener() {
            @Override
            public void onBrandShown() {
                // The word is being inked in right now, so the sound lands with it.
                // Silent on purpose: the brand beat replaced the chime.
            }

            @Override
            public void onFinished() {
                splash.animate()
                        .alpha(0f)
                        .setDuration(320)
                        .withEndAction(() -> {
                            splash.setVisibility(View.GONE);

                            // The content settles in as the splash lifts, so the
                            // transition reads as one motion rather than a cut.
                            View root = findViewById(R.id.bottomBar);
                            if (root != null) {
                                root.setTranslationY(24f * getResources().getDisplayMetrics().density);
                                root.setAlpha(0f);
                                root.animate().translationY(0f).alpha(1f).setDuration(320).start();
                            }
                        })
                        .start();
            }
        });
        splash.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void bindViews() {
        linkInput = findViewById(R.id.linkInput);
        clearButton = findViewById(R.id.clearButton);
        resolveButton = findViewById(R.id.resolveButton);
        statusText = findViewById(R.id.statusText);
        collectionBar = findViewById(R.id.collectionBar);
        collectionCover = findViewById(R.id.collectionCover);
        collectionName = findViewById(R.id.collectionName);
        collectionMeta = findViewById(R.id.collectionMeta);
        downloadAllButton = findViewById(R.id.downloadAllButton);
        list = findViewById(R.id.list);
        emptyState = findViewById(R.id.emptyState);

        homeView = findViewById(R.id.homeView);
        libraryView = findViewById(R.id.libraryView);
        libraryStatus = findViewById(R.id.libraryStatus);
        songsList = (DragRecyclerView) findViewById(R.id.songsList);
        libraryEmpty = findViewById(R.id.libraryEmpty);
        grantAccessButton = findViewById(R.id.grantAccessButton);
        grantAccessButton.setOnClickListener(v -> requestAudioAccessOrSettings());
        cancelAllButton = findViewById(R.id.cancelAllButton);
        shuffleAllButton = findViewById(R.id.shuffleAllButton);
        librarySortButton = findViewById(R.id.librarySort);
        if (librarySortButton != null) {
            librarySortButton.setOnClickListener(v -> cycleLibrarySort());
        }
        View appMenu = findViewById(R.id.appMenu);
        if (appMenu != null) appMenu.setOnClickListener(this::showAppMenu);
        librarySearch = findViewById(R.id.librarySearch);

        libraryHeader = findViewById(R.id.libraryHeader);
        selectionBar = findViewById(R.id.selectionBar);
        selectionCount = findViewById(R.id.selectionCount);
        findViewById(R.id.selectionClose).setOnClickListener(v -> songAdapter.clearSelection());
        findViewById(R.id.selectionSelectAll).setOnClickListener(v -> {
            if (songAdapter.getItemCount() == 0) {
                toast("Nothing to select");
                return;
            }
            songAdapter.selectAll();
        });
        findViewById(R.id.selectionPlaylist).setOnClickListener(v -> addSelectedToPlaylist());
        findViewById(R.id.selectionQueue).setOnClickListener(v -> queueSelected());
        findViewById(R.id.selectionDelete).setOnClickListener(v -> confirmDeleteSelected());

        installBackHandling();

        miniPlayer = findViewById(R.id.miniPlayer);
        miniArt = findViewById(R.id.miniArt);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlayPause = findViewById(R.id.miniPlayPause);
        miniProgress = findViewById(R.id.miniProgress);

        navHome = findViewById(R.id.navHome);
        navLibrary = findViewById(R.id.navLibrary);
        navHomeIcon = (ImageView) ((LinearLayout) navHome).getChildAt(0);
        navHomeLabel = (TextView) ((LinearLayout) navHome).getChildAt(1);
        navLibraryIcon = (ImageView) ((LinearLayout) navLibrary).getChildAt(0);
        navLibraryLabel = (TextView) ((LinearLayout) navLibrary).getChildAt(1);
        navPlaylists = findViewById(R.id.navPlaylists);
        navPlaylistsIcon = (ImageView) ((LinearLayout) navPlaylists).getChildAt(0);
        navPlaylistsLabel = (TextView) ((LinearLayout) navPlaylists).getChildAt(1);

        playlistsView = findViewById(R.id.playlistsView);
        playlistsStatus = findViewById(R.id.playlistsStatus);
        playlistsList = findViewById(R.id.playlistsList);
        playlistsEmpty = findViewById(R.id.playlistsEmpty);

        statusText.setText("Ready \u2014 nothing loaded yet");
    }

    private void wireActions() {
        clearButton.setOnClickListener(v -> linkInput.setText(""));
        linkInput.addTextChangedListener(new SimpleWatcher(hasText ->
                clearButton.setVisibility(hasText ? View.VISIBLE : View.GONE)));

        resolveButton.setOnClickListener(v -> resolve());
        downloadAllButton.setOnClickListener(v -> {
            if (collection != null) startDownload(collection.tracks);
        });

        navHome.setOnClickListener(v -> showTab(TAB_HOME));
        navLibrary.setOnClickListener(v -> showTab(TAB_LIBRARY));
        navPlaylists.setOnClickListener(v -> showTab(TAB_PLAYLISTS));
        findViewById(R.id.newPlaylistButton).setOnClickListener(v -> createPlaylist());
        findViewById(R.id.emptyNewPlaylist).setOnClickListener(v -> createPlaylist());


        cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        shuffleAllButton.setOnClickListener(v -> shuffleAll());
        librarySearch.addTextChangedListener(new SimpleWatcher(hasText -> {
            libraryQuery = librarySearch.getText().toString().trim().toLowerCase(Locale.US);
            applyLibraryFilter();
        }));

        findViewById(R.id.miniOpen).setOnClickListener(v -> openPlayer());
        miniPlayer.setOnClickListener(v -> openPlayer());
        miniPlayPause.setOnClickListener(v -> playback.toggle());
        findViewById(R.id.miniPrev).setOnClickListener(v -> playback.previous());
        findViewById(R.id.miniNext).setOnClickListener(v -> playback.next(false));
        findViewById(R.id.miniMore).setOnClickListener(this::showMiniMenu);
    }

    private void setupLists() {
        adapter = new TrackAdapter(new TrackAdapter.Actions() {
            @Override
            public void onDownloadClicked(Track track, int index) {
                startDownload(single(track));
            }

            @Override
            public void onCancelClicked(Track track, int index) {
                cancelDownload(index);
            }

            @Override
            public void onPlayClicked(Track track, int index) {
                playFromList(index);
            }
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());

        songAdapter = new SongAdapter(new SongAdapter.Actions() {
            @Override
            public void onPlay(int index) {
                playLibraryFrom(index);
            }

            @Override
            public void onMore(View anchor, Track track) {
                showSongMenu(anchor, track);
            }

            @Override
            public void onLongPress(Track track) {
                songAdapter.beginSelection(track);
            }

            @Override
            public void onToggle(Track track) {
                songAdapter.toggle(track);
            }

            @Override
            public void onSelectionChanged(int count, boolean selecting) {
                renderSelection(count, selecting);
            }
        });
        songsList.setLayoutManager(new LinearLayoutManager(this));
        songsList.setAdapter(songAdapter);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Actions() {
            @Override
            public void onOpen(Playlist playlist) {
                openPlaylist(playlist);
            }

            @Override
            public void onMore(View anchor, Playlist playlist) {
                showPlaylistMenu(playlist);
            }
        });
        playlistsList.setLayoutManager(new LinearLayoutManager(this));
        playlistsList.setAdapter(playlistAdapter);
    }

    // ---------------------------------------------------------------- tabs

    private static final int TAB_HOME = 0;
    private static final int TAB_LIBRARY = 1;
    private static final int TAB_PLAYLISTS = 2;

    /** Library opens first: the songs you already own matter more than the paste box. */
    private int activeTab = TAB_LIBRARY;

    /**
     * Back leaves selection mode before it leaves the screen.
     *
     * This has to go through the dispatcher, not {@code onBackPressed()}: the app targets
     * SDK 34, where back is routed through OnBackPressedDispatcher and the deprecated
     * override is never called. That is why a plain override left back exiting the screen
     * mid-selection instead of clearing it.
     */
    private void installBackHandling() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (songAdapter != null && songAdapter.isSelecting()) {
                            songAdapter.clearSelection();
                            return;
                        }
                        // Hand the press back to the system so the screen still closes.
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
    }

    private void showTab(int tab) {
        // Selection belongs to the library view; switching tabs drops it.
        if (songAdapter != null && songAdapter.isSelecting() && tab != TAB_LIBRARY) {
            songAdapter.clearSelection();
        }
        if (tab == TAB_PLAYLISTS) {
            showPlaylistsTab();
            return;
        }
        activeTab = tab;
        boolean library = tab == TAB_LIBRARY;
        if (playlistsView != null) playlistsView.setVisibility(View.GONE);
        View incoming = library ? libraryView : homeView;
        View outgoing = library ? homeView : libraryView;

        // Cross-fade the two panes instead of a hard swap. The outgoing view is hidden
        // the moment it finishes fading so it never eats a touch on the way out.
        if (outgoing.getVisibility() == View.VISIBLE && incoming != outgoing) {
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                outgoing.setVisibility(View.GONE);
                outgoing.setAlpha(1f);
            }).start();
        } else {
            outgoing.setVisibility(View.GONE);
        }

        if (incoming.getVisibility() != View.VISIBLE) {
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            incoming.setTranslationY(12f * getResources().getDisplayMetrics().density);
            incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        if (library) refreshLibrary();
        syncNav();
    }

    /**
     * Paints the bottom bar for the current tab.
     *
     * One routine for all three tabs means the pill, the tint and the label opacity can
     * never disagree about which tab is open.
     */
    private void syncNav() {
        styleNav(navHome, navHomeIcon, navHomeLabel, activeTab == TAB_HOME);
        styleNav(navLibrary, navLibraryIcon, navLibraryLabel, activeTab == TAB_LIBRARY);
        styleNav(navPlaylists, navPlaylistsIcon, navPlaylistsLabel, activeTab == TAB_PLAYLISTS);
    }

    private void styleNav(LinearLayout pill, ImageView icon, TextView label, boolean active) {
        if (pill == null || icon == null || label == null) return;
        pill.setBackgroundResource(active ? R.drawable.bg_pill_ghost : 0);
        icon.setImageTintList(getColorStateList(active ? R.color.accent : R.color.text_secondary));
        label.setTextColor(getColor(active ? R.color.accent : R.color.text_secondary));
        label.animate().alpha(active ? 1f : 0.72f).setDuration(180).start();
    }

    /** The playlists pane: a shelf of playlists rather than a list of songs. */
    private void showPlaylistsTab() {
        activeTab = TAB_PLAYLISTS;
        hideKeyboard();
        View[] panes = {homeView, libraryView};
        for (View pane : panes) {
            if (pane != null) {
                pane.animate().cancel();
                pane.setVisibility(View.GONE);
                pane.setAlpha(1f);
            }
        }
        playlistsView.setVisibility(View.VISIBLE);
        playlistsView.setAlpha(0f);
        playlistsView.setTranslationY(12f * getResources().getDisplayMetrics().density);
        playlistsView.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        refreshPlaylists();
        syncNav();
    }

    private void refreshPlaylists() {
        if (playlistAdapter == null) return;
        List<Playlist> lists = PlaylistStore.all(this);
        playlistAdapter.submit(lists);
        boolean empty = lists.isEmpty();
        playlistsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        playlistsStatus.setText(empty ? "Make a playlist" : lists.size() + (lists.size() == 1 ? " playlist" : " playlists"));
    }

    private void createPlaylist() {
        InputSheet.show(this, "New playlist", "Name it now \u2014 you can rename it later.",
                "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    toast("Playlist created");
                    openPlaylist(p);
                });
    }

    private void openPlaylist(Playlist playlist) {
        startActivity(new Intent(this, PlaylistActivity.class)
                .putExtra(PlaylistActivity.EXTRA_ID, playlist.id));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void showPlaylistMenu(Playlist playlist) {
        MenuSheet.show(this, playlist.name, playlist.summary(),
                new MenuSheet.Item(R.drawable.ic_play, "Play", () -> {
                    List<Track> tracks = new ArrayList<>();
                    for (Playlist.Entry e : playlist.songs) tracks.add(e.toTrack());
                    if (tracks.isEmpty()) {
                        toast("Add songs first");
                        return;
                    }
                    playback.playQueue(tracks, 0, playlist.name);
                    openPlayer();
                }),
                new MenuSheet.Item(R.drawable.ic_edit, "Rename playlist", () ->
                        PlaylistActions.rename(this, playlist, this::refreshPlaylists)),
                new MenuSheet.Item(R.drawable.ic_image, "Change cover", () ->
                        PlaylistActions.pickCover(this, playlist)),
                new MenuSheet.Item(R.drawable.ic_delete, "Delete playlist", true, () ->
                        PlaylistActions.confirmDelete(this, playlist, this::refreshPlaylists)));
    }

    /** "Add to playlist" from a library song: pick an existing list or make a new one. */
    private void addToPlaylist(Track track) {
        List<Playlist> lists = PlaylistStore.all(this);
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_add, "New playlist\u2026", () ->
                InputSheet.show(this, "New playlist", null, "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    p.add(track);
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    toast("Added to " + p.name);
                })));
        for (Playlist p : lists) {
            items.add(new MenuSheet.Item(R.drawable.ic_queue,
                    p.contains(track.localUri) ? p.name + "  \u00b7  already added" : p.name, () -> {
                if (p.contains(track.localUri)) {
                    toast("Already in " + p.name);
                    return;
                }
                p.add(track);
                PlaylistStore.save(this, p);
                refreshPlaylists();
                toast("Added to " + p.name);
            }));
        }
        MenuSheet.show(this, "Add to playlist", track.title,
                items.toArray(new MenuSheet.Item[0]));
    }

    // ---------------------------------------------------------------- resolving

    private void resolve() {
        String raw = linkInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            toast("Paste a Spotify link first");
            setBusy(false, "Paste a Spotify link to get started");
            return;
        }
        String link = extractLink(raw);
        hideKeyboard();
        setBusy(true, "Reading Spotify link\u2026");
        io.execute(() -> {
            try {
                SpotifyCollection resolved = SpotifyResolver.resolve(link);
                main.post(() -> showCollection(resolved));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> {
                    setBusy(false, "Could not read that link");
                    toast(message);
                });
            }
        });
    }

    private void showCollection(SpotifyCollection resolved) {
        collection = resolved;
        adapter.submit(resolved.tracks);
        // Cascade the new rows in. The animation is cleared afterwards so later
        // progress updates (which rebind single rows) stay instant.
        list.setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(
                this, R.anim.row_in_layout));
        list.scheduleLayoutAnimation();
        main.postDelayed(() -> list.setLayoutAnimation(null), 900);
        collectionBar.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        collectionName.setText(resolved.name);
        String kind = resolved.isPlaylist()
                ? ("Playlist \u00b7 " + resolved.tracks.size() + " tracks")
                : "Single track";
        collectionMeta.setText(kind + " \u00b7 saved as MP3");
        collectionCover.setImageDrawable(null);
        if (!resolved.coverUrl.isEmpty()) {
            Track probe = new Track(resolved.name, "", resolved.coverUrl);
            Art.load(this, probe, collectionCover);
        }
        setBusy(false, resolved.tracks.size() + " track" + (resolved.tracks.size() == 1 ? "" : "s")
                + " ready \u2014 tap Download");

        markAlreadySaved(resolved.tracks);
        // Per-track covers arrive in the background and repaint their row as they land;
        // the list is usable immediately instead of waiting for every lookup.
        SpotifyResolver.attachTrackArtwork(resolved, adapter::notifyDataSetChanged);
        // Resolve the first uploads now so tapping Download starts transferring at once.
        DownloadEngine.prewarm(getApplicationContext(), resolved.tracks);
    }

    /**
     * Flags rows whose song is already on the device.
     *
     * Pasting the same link twice used to re-download everything and leave "(1)" copies
     * behind. The resolve step now checks the saved library and marks those rows as
     * already downloaded, so Download all skips them and the row says so plainly.
     */
    private void markAlreadySaved(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        io.execute(() -> {
            List<Track> found = new ArrayList<>();
            List<LibraryStore.Item> hits = new ArrayList<>();
            for (Track t : tracks) {
                LibraryStore.Item hit = LibraryStore.findSaved(this, t.title, t.artist);
                if (hit != null) {
                    found.add(t);
                    hits.add(hit);
                }
            }
            if (found.isEmpty()) return;
            main.post(() -> {
                for (int i = 0; i < found.size() && i < hits.size(); i++) {
                    Track t = found.get(i);
                    t.status = Track.DONE;
                    t.progress = 100;
                    t.localUri = hits.get(i).uri;
                }
                adapter.notifyDataSetChanged();
                int n = found.size();
                statusText.setText(n + (n == 1 ? " track is" : " tracks are")
                        + " already in your library");
            });
        });
    }

    /**
     * Re-runs every row that ended in an error.
     *
     * A failed track used to be a dead end: the only way to try again was to paste the
     * link over and hope the duplicate guard had not already marked the rest saved. The
     * failures are usually transient (a dropped connection, a client that offered no
     * stream), so retrying just those rows is worth one tap.
     */
    private void retryFailed() {
        List<Track> failed = new ArrayList<>();
        for (Track t : adapter.items()) {
            if (t.status == Track.ERROR) failed.add(t);
        }
        if (failed.isEmpty()) {
            toast("Nothing to retry");
            return;
        }
        toast(failed.size() == 1 ? "Retrying 1 track" : "Retrying " + failed.size() + " tracks");
        startDownload(failed);
    }

    private void startDownload(List<Track> tracks) {
        if (tracks.isEmpty()) {
            toast("Nothing to download");
            return;
        }
        for (Track t : tracks) {
            t.status = Track.QUEUED;
            t.progress = 0;
            t.error = null;
        }
        adapter.notifyDataSetChanged();
        collectionBar.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        statusText.setText("Starting downloader\u2026");
        // The button doubles as "Cancel all" and "Retry failed". A fresh queue must get
        // the cancel behaviour back, otherwise a stale retry handler would be attached to
        // a download that is already running.
        cancelAllButton.setText("Cancel all");
        cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        cancelAllButton.setVisibility(View.VISIBLE);

        JSONArray arr = new JSONArray();
        for (Track t : tracks) {
            try {
                arr.put(t.toJson());
            } catch (Exception ignored) {
            }
        }
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_TRACKS, arr.toString())
                .putExtra(DownloadService.EXTRA_ALBUM, collection == null ? "Cartify" : collection.name)
                .putExtra(DownloadService.EXTRA_TOTAL, tracks.size());
        ContextCompat.startForegroundService(this, intent);
    }

    /**
     * Cancels exactly one row. The service kills that track's yt-dlp process, so the
     * transfer really stops instead of finishing silently in the background.
     */
    private void cancelDownload(int index) {
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_CANCEL_TRACK)
                .putExtra(DownloadService.EXTRA_INDEX, index);
        startService(intent);
        adapter.reset(index);
        statusText.setText("Cancelled one download");
    }

    private void cancelAllDownloads() {
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_CANCEL));
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Track t = adapter.items().get(i);
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) adapter.reset(i);
        }
        statusText.setText("All downloads cancelled");
    }

    private void warmUpDownloader() {
        io.execute(() -> {
            try {
                DownloadEngine.ensureInitialised(getApplicationContext());
            } catch (Exception e) {
                main.post(() -> statusText.setText("Downloader engine: " + e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- download callbacks

    @Override
    public void onTrackProgress(int index, int percent, String detail) {
        if (index < adapter.getItemCount()) {
            adapter.update(index, Track.ACTIVE, percent, detail, null, null);
            statusText.setText(String.format(Locale.US, "Downloading %d of %d \u2014 %s",
                    index + 1, adapter.getItemCount(), detail));
            cancelAllButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onTrackFinished(int index, String uri, String error) {
        if (index < adapter.getItemCount()) {
            if (error == null) adapter.update(index, Track.DONE, 100, "saved", uri, null);
            else adapter.update(index, Track.ERROR, 0, error, null, error);
        }
    }

    @Override
    public void onTrackCancelled(int index) {
        adapter.reset(index);
        statusText.setText("Download cancelled");
    }

    @Override
    public void onQueueFinished(int completed, int failed) {
        String summary;
        if (completed == 0 && failed == 0) {
            // A cancelled queue is not a failure: say so instead of "0 saved".
            summary = "Download cancelled";
        } else if (failed == 0) {
            summary = completed + " track" + (completed == 1 ? "" : "s") + " saved to Music/Cartify";
        } else {
            summary = completed + " saved \u00b7 " + failed + " failed";
        }
        statusText.setText(summary);
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Track t = adapter.items().get(i);
            if (t.status == Track.QUEUED || t.status == Track.ACTIVE) adapter.reset(i);
        }
        // "Cancel all" makes no sense once the queue has drained; when something failed,
        // the same slot offers a retry instead, so the user is never left with a dead end.
        if (failed > 0) {
            cancelAllButton.setText("Retry " + failed + " failed");
            cancelAllButton.setVisibility(View.VISIBLE);
            cancelAllButton.setOnClickListener(v -> retryFailed());
        } else {
            cancelAllButton.setVisibility(View.GONE);
            cancelAllButton.setOnClickListener(v -> cancelAllDownloads());
        }
        refreshLibrary();
    }

    // ---------------------------------------------------------------- library

    private void refreshLibrary() {
        io.execute(() -> {
            List<LibraryStore.Item> raw = LibraryStore.query(this);
            List<Track> songs = new ArrayList<>();
            for (LibraryStore.Item it : raw) songs.add(it.toTrack());
            main.post(() -> {
                libraryAll.clear();
                libraryAll.addAll(songs);
                applyLibraryFilter();
                syncNowPlayingHighlight();
            });
        });
    }

    /** Rebuilds the visible library rows from the saved songs and the search box. */
    private void applyLibraryFilter() {
        // Searching while rows are selected rebuilds the visible list, so the selection is
        // dropped rather than left pointing at songs the user can no longer see.
        if (songAdapter.isSelecting() && !libraryQuery.isEmpty()) songAdapter.clearSelection();
        List<Track> shown = new ArrayList<>();
        for (Track t : libraryAll) {
            if (libraryQuery.isEmpty()
                    || (t.title != null && t.title.toLowerCase(Locale.US).contains(libraryQuery))
                    || (t.artist != null && t.artist.toLowerCase(Locale.US).contains(libraryQuery))) {
                shown.add(t);
            }
        }
        sortLibrary(shown);
        songAdapter.submit(shown);
        String suffix = libraryQuery.isEmpty() ? "" : " Â· filtered";
        libraryStatus.setText(libraryAll.size() + " song" + (libraryAll.size() == 1 ? "" : "s") + suffix);
        updateLibraryEmpty();
    }

    /**
     * Orders the library rows.
     *
     * "Newest" relies on the MediaStore date the store already records, so a fresh
     * download appears at the top where the user expects it.
     */
    private void sortLibrary(List<Track> songs) {
        switch (librarySort) {
            case 1:
                songs.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.title == null ? "" : a.title, b.title == null ? "" : b.title));
                break;
            case 2:
                songs.sort((a, b) -> {
                    int byArtist = String.CASE_INSENSITIVE_ORDER.compare(
                            a.artist == null ? "" : a.artist, b.artist == null ? "" : b.artist);
                    return byArtist != 0 ? byArtist : String.CASE_INSENSITIVE_ORDER.compare(
                            a.title == null ? "" : a.title, b.title == null ? "" : b.title);
                });
                break;
            default:
                songs.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
                break;
        }
    }

    /** Cycles Newest -> Title -> Artist -> Newest. */
    private void cycleLibrarySort() {
        librarySort = (librarySort + 1) % 3;
        if (librarySortButton != null) {
            librarySortButton.setText(librarySort == 1 ? "Title" : librarySort == 2 ? "Artist" : "Newest");
            librarySortButton.setTextColor(getColor(R.color.accent));
            librarySortButton.setAlpha(0.6f);
            librarySortButton.animate().alpha(1f).setDuration(220).start();
        }
        applyLibraryFilter();
    }

    /** Starts the whole library in a random order. */
    private void shuffleAll() {
        if (libraryAll.isEmpty()) {
            toast("Your library is empty");
            return;
        }
        List<Track> shuffled = new ArrayList<>(libraryAll);
        java.util.Collections.shuffle(shuffled);
        playback.playQueue(shuffled, 0, "Library");
        playback.setShuffle(true);
        openPlayer();
    }

    private void updateLibraryEmpty() {
        boolean empty = songAdapter.getItemCount() == 0;
        libraryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        grantAccessButton.setVisibility(View.GONE);
        if (empty) {
            boolean blocked = !hasAudioAccess();
            ((TextView) libraryEmpty.getChildAt(0)).setText(blocked
                    ? "Allow access to your music"
                    : "Your library is empty");
            ((TextView) libraryEmpty.getChildAt(1)).setText(blocked
                    ? "Cartify needs permission to read the songs saved in Music/Cartify."
                    : "Downloaded songs appear here automatically.");
            if (blocked) grantAccessButton.setVisibility(View.VISIBLE);
        }
    }

    private void showSongMenu(View anchor, Track track) {
        MenuSheet.show(this, track.title,
                track.artist == null || track.artist.isEmpty() ? "Unknown artist" : track.artist,
                new MenuSheet.Item(R.drawable.ic_play, "Play now", () -> {
                    playback.playQueue(new ArrayList<>(songAdapter.items()),
                            indexOfUri(track.localUri), "Library");
                    openPlayer();
                }),
                new MenuSheet.Item(R.drawable.ic_next, "Play next", () -> {
                    playback.playNext(track);
                    toast("Playing next");
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
                    playback.append(track);
                    toast("Added to queue");
                }),
                new MenuSheet.Item(R.drawable.ic_add, "Add to playlist",
                        () -> addToPlaylist(track)),
                new MenuSheet.Item(R.drawable.ic_delete, "Delete from device", true,
                        () -> confirmDelete(track)));
    }

    // ---------------------------------------------------------------- bulk selection

    /** Swaps the library header for the bulk bar and back. */
    private void renderSelection(int count, boolean selecting) {
        if (selectionBar == null) return;
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (libraryHeader != null) libraryHeader.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (selecting && selectionCount != null) {
            selectionCount.setText(count + (count == 1 ? " selected" : " selected"));
            selectionCount.animate().cancel();
            selectionCount.setAlpha(0.55f);
            selectionCount.animate().alpha(1f).setDuration(160).start();
        }
    }

    /** "Add to playlist" for every selected row, with a running already-present count. */
    private void addSelectedToPlaylist() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        final List<Playlist> lists = PlaylistStore.all(this);
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_add, "New playlist\u2026", () ->
                InputSheet.show(this, "New playlist", null, "", "Create", value -> {
                    Playlist p = new Playlist(value);
                    int added = 0;
                    for (Track t : picked) if (p.add(t)) added++;
                    PlaylistStore.save(this, p);
                    refreshPlaylists();
                    finishBulkAdd(p.name, added, picked.size());
                })));
        for (Playlist p : lists) {
            int already = 0;
            for (Track t : picked) if (p.contains(t.localUri)) already++;
            String label = already == 0 ? p.name
                    : p.name + "  \u00b7  " + already + " already added";
            items.add(new MenuSheet.Item(R.drawable.ic_queue, label, () -> {
                int added = 0;
                for (Track t : picked) if (p.add(t)) added++;
                if (added == 0) {
                    toast("Already in " + p.name);
                    return;
                }
                PlaylistStore.save(this, p);
                refreshPlaylists();
                finishBulkAdd(p.name, added, picked.size());
            }));
        }
        MenuSheet.show(this, "Add to playlist",
                picked.size() + (picked.size() == 1 ? " song selected" : " songs selected"),
                items.toArray(new MenuSheet.Item[0]));
    }

    private void finishBulkAdd(String playlistName, int added, int total) {
        toast(added == total
                ? "Added " + added + " to " + playlistName
                : "Added " + added + " of " + total + " to " + playlistName);
        songAdapter.clearSelection();
    }

    /** Appends every selected row to the queue instead of replacing what is playing. */
    private void queueSelected() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        for (Track t : picked) playback.append(t);
        toast(picked.size() == 1 ? "Added to queue" : "Added " + picked.size() + " to queue");
        songAdapter.clearSelection();
    }

    /** Deleting is irreversible, so the sheet spells out how many files leave the device. */
    private void confirmDeleteSelected() {
        final List<Track> picked = songAdapter.selectedTracks();
        if (picked.isEmpty()) {
            toast("Select at least one song");
            return;
        }
        String what = picked.size() == 1
                ? "\"" + picked.get(0).title + "\""
                : picked.size() + " songs";
        MenuSheet.show(this, "Delete " + what + "?",
                "They will be removed from this device and from Music/Cartify. This cannot be undone.",
                new MenuSheet.Item(R.drawable.ic_delete, "Delete " + picked.size(), true,
                        () -> deleteSelected(picked)),
                new MenuSheet.Item(R.drawable.ic_close, "Cancel", () -> {
                }));
    }

    /**
     * Deletes rows one by one on a worker thread.
     *
     * A file this install owns disappears straight away; one saved by an earlier install
     * can only be removed by the system, so the whole batch is collected and handed to a
     * single consent dialog instead of prompting per song.
     */
    private void deleteSelected(final List<Track> picked) {
        io.execute(() -> {
            final List<Track> gone = new ArrayList<>();
            final List<String> denied = new ArrayList<>();
            for (Track t : picked) {
                if (t.localUri == null || t.localUri.isEmpty()) continue;
                if (LibraryStore.delete(this, t.localUri)) gone.add(t);
                else denied.add(t.localUri);
            }
            main.post(() -> {
                for (Track t : gone) {
                    detachFromQueue(t.localUri);
                    pruneFromPlaylists(t.localUri);
                }
                if (!denied.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestSystemDeleteBulk(denied, gone.size());
                } else {
                    reportBulkDeleted(gone.size(), denied.size());
                }
            });
        });
    }

    private void requestSystemDeleteBulk(List<String> uris, int alreadyGone) {
        try {
            List<Uri> parsed = new ArrayList<>();
            for (String u : uris) parsed.add(Uri.parse(u));
            android.app.PendingIntent request =
                    MediaStore.createDeleteRequest(getContentResolver(), parsed);
            pendingBulkDelete.clear();
            pendingBulkDelete.addAll(uris);
            pendingBulkDeletedCount = alreadyGone;
            startIntentSenderForResult(request.getIntentSender(), REQ_DELETE_BULK, null, 0, 0, 0);
        } catch (Exception e) {
            reportBulkDeleted(alreadyGone, uris.size());
        }
    }

    /** Running total for a bulk delete that spans the direct delete and the consent dialog. */
    private int pendingBulkDeletedCount;

    private void reportBulkDeleted(int deleted, int failed) {
        pendingBulkDelete.clear();
        pendingBulkDeletedCount = 0;
        songAdapter.clearSelection();
        if (deleted > 0) {
            toast(failed == 0
                    ? "Deleted " + deleted + (deleted == 1 ? " song" : " songs")
                    : "Deleted " + deleted + " \u00b7 " + failed + " kept");
        } else if (failed > 0) {
            toast("Could not delete " + failed + (failed == 1 ? " song" : " songs"));
        }
        refreshLibrary();
    }

    /** Deleting is irreversible, so ask first in the same glass sheet, saying exactly what goes. */
    private void confirmDelete(Track track) {
        MenuSheet.show(this, "Delete \"" + track.title + "\"?",
                "It will be removed from this device and from Music/Cartify. This cannot be undone.",
                new MenuSheet.Item(R.drawable.ic_delete, "Delete from device", true,
                        () -> deleteSong(track)),
                new MenuSheet.Item(R.drawable.ic_close, "Cancel", () -> {
                }));
    }

    private void deleteSong(Track track) {
        if (track.localUri == null || track.localUri.isEmpty()) {
            toast("Nothing to delete");
            return;
        }
        final String uri = track.localUri;
        io.execute(() -> {
            boolean removed = LibraryStore.delete(this, uri);
            main.post(() -> {
                if (removed) {
                    onSongRemoved(track, uri);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // The file was saved by an earlier install, so only the system can delete it.
                    requestSystemDelete(track, uri);
                } else {
                    toast("Could not delete \"" + track.title + "\"");
                }
            });
        });
    }

    /** Android 11+: hand the deletion to the system consent dialog. */
    private void requestSystemDelete(Track track, String uri) {
        try {
            android.app.PendingIntent request = MediaStore.createDeleteRequest(
                    getContentResolver(), java.util.Collections.singletonList(Uri.parse(uri)));
            pendingDeleteTrack = track;
            pendingDeleteUri = uri;
            startIntentSenderForResult(request.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
        } catch (Exception e) {
            toast("Could not delete \"" + track.title + "\"");
        }
    }

    private void onSongRemoved(Track track, String uri) {
        detachFromQueue(uri);
        pruneFromPlaylists(uri);
        toast("Deleted \"" + track.title + "\"");
        refreshLibrary();
    }

    /** Stop a deleted file from being played out of a queue that no longer owns it. */
    private void detachFromQueue(String uri) {
        if (uri == null || uri.isEmpty()) return;
        List<Track> queue = playback.queue();
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (uri.equals(queue.get(i).localUri)) {
                playback.removeAt(i);
                return;
            }
        }
    }

    /**
     * Drops a deleted file from every playlist that still lists it.
     *
     * Playlist entries are stored by uri, so deleting the MP3 from the library used to
     * leave the playlist pointing at a row that could never play. Rewriting the affected
     * playlists keeps the two views agreeing about what exists.
     */
    private void pruneFromPlaylists(String uri) {
        if (uri == null || uri.isEmpty()) return;
        boolean changed = false;
        for (Playlist p : PlaylistStore.all(this)) {
            if (p.removeUri(uri)) {
                PlaylistStore.save(this, p);
                changed = true;
            }
        }
        if (changed) refreshPlaylists();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (PlaylistActions.onActivityResult(this, requestCode, resultCode, data,
                this::refreshPlaylists)) return;
        if (requestCode == REQ_DELETE_BULK) {
            int already = pendingBulkDeletedCount;
            int allowed = 0;
            if (resultCode == RESULT_OK) {
                for (String u : pendingBulkDelete) {
                    detachFromQueue(u);
                    allowed++;
                }
            }
            reportBulkDeleted(already + allowed, pendingBulkDelete.size() - allowed);
            return;
        }
        if (requestCode != REQ_DELETE) return;
        Track track = pendingDeleteTrack;
        String uri = pendingDeleteUri;
        pendingDeleteTrack = null;
        pendingDeleteUri = "";
        if (track == null) return;
        if (resultCode == RESULT_OK) onSongRemoved(track, uri);
        else toast("Delete cancelled");
    }

    private int indexOfUri(String uri) {
        List<Track> items = songAdapter.items();
        for (int i = 0; i < items.size(); i++) if (items.get(i).localUri.equals(uri)) return i;
        return 0;
    }

    // ---------------------------------------------------------------- playback

    private void playFromList(int index) {
        List<Track> items = adapter.items();
        if (index < 0 || index >= items.size()) return;
        List<Track> ready = new ArrayList<>();
        int start = -1;
        for (Track t : items) {
            if (t.localUri != null && !t.localUri.isEmpty()) {
                if (t == items.get(index)) start = ready.size();
                ready.add(t);
            }
        }
        if (start < 0) return;
        playback.playQueue(ready, start, collection == null ? "Cartify" : collection.name);
        openPlayer();
    }

    private void playLibraryFrom(int index) {
        List<Track> songs = songAdapter.items();
        if (index < 0 || index >= songs.size()) return;
        playback.playQueue(new ArrayList<>(songs), index, "Library");
        openPlayer();
    }

    private void openPlayer() {
        if (playback.current() == null) return;
        startActivity(new Intent(this, PlayerActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onPlaybackChanged() {
        renderMini();
        syncNowPlayingHighlight();
        if (playlistAdapter != null) refreshPlaylists();
    }

    @Override
    public void onQueueChanged() {
        renderMini();
    }

    private void renderMini() {
        Track t = playback.current();
        if (t == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(t.title);
        miniArtist.setText(t.artist.isEmpty() ? "Unknown artist" : t.artist);
        // While a file is still preparing the player reports "not playing"; the icon
        // follows the user's intent instead so it never flips back under the finger.
        boolean showPause = playback.isPlaying() || (playback.isLoading() && playback.wantsPlay());
        miniPlayPause.setImageResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play);
        Art.load(this, t, miniArt);
        updateMiniProgress();
    }

    /**
     * The collapsed player's overflow: the actions a media bar cannot fit inline.
     *
     * Shuffle and repeat show their state in the label because the sheet has no toggle
     * switch; tapping flips the mode and the next open reflects it.
     */
    private void showMiniMenu(View anchor) {
        final Track t = playback.current();
        if (t == null) return;
        String subtitle = t.artist == null || t.artist.isEmpty() ? "Unknown artist" : t.artist;
        List<MenuSheet.Item> items = new ArrayList<>();
        items.add(new MenuSheet.Item(R.drawable.ic_shuffle,
                playback.isShuffle() ? "Shuffle: on" : "Shuffle: off", () -> {
            playback.setShuffle(!playback.isShuffle());
            toast(playback.isShuffle() ? "Shuffle on" : "Shuffle off");
        }));
        String repeatLabel;
        int repeatIcon;
        switch (playback.repeatMode()) {
            case Playback.REPEAT_ONE:
                repeatLabel = "Repeat: one song";
                repeatIcon = R.drawable.ic_repeat_one;
                break;
            case Playback.REPEAT_ALL:
                repeatLabel = "Repeat: all";
                repeatIcon = R.drawable.ic_repeat;
                break;
            default:
                repeatLabel = "Repeat: off";
                repeatIcon = R.drawable.ic_repeat;
                break;
        }
        items.add(new MenuSheet.Item(repeatIcon, repeatLabel, () -> {
            playback.cycleRepeat();
            String now;
            switch (playback.repeatMode()) {
                case Playback.REPEAT_ONE:
                    now = "Repeating one song";
                    break;
                case Playback.REPEAT_ALL:
                    now = "Repeating the queue";
                    break;
                default:
                    now = "Repeat off";
                    break;
            }
            toast(now);
        }));
        items.add(new MenuSheet.Item(R.drawable.ic_add, "Add to queue", () -> {
            playback.append(t);
            toast("Added to queue");
        }));
        items.add(new MenuSheet.Item(R.drawable.ic_queue, "Add to playlist",
                () -> addToPlaylist(t)));
        items.add(new MenuSheet.Item(R.drawable.ic_info, "Open full player",
                () -> openPlayer()));
        items.add(new MenuSheet.Item(R.drawable.ic_download, "Check for updates",
                this::checkForUpdatesManually));
        MenuSheet.show(this, t.title, subtitle, items.toArray(new MenuSheet.Item[0]));
    }

    private void updateMiniProgress() {
        if (miniPlayer.getVisibility() != View.VISIBLE) return;
        long dur = playback.duration();
        long pos = playback.position();
        miniProgress.setProgress(dur > 0 ? (int) (1000L * pos / dur) : 0);
    }

    private void syncNowPlayingHighlight() {
        Track t = playback.current();
        String uri = t == null ? "" : t.localUri;
        songAdapter.setPlaying(uri);
    }

    // ---------------------------------------------------------------- misc

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && SpotifyResolver.looksLikeSpotify(text)) {
                linkInput.setText(extractLink(text));
                showTab(TAB_HOME);
                resolve();
            }
        }
    }

    private static String extractLink(String text) {
        int start = text.indexOf("http");
        if (start < 0) return text.trim();
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private static List<Track> single(Track t) {
        List<Track> one = new ArrayList<>();
        one.add(t);
        return one;
    }

    private void setBusy(boolean busy, String message) {
        resolveButton.setEnabled(!busy);
        resolveButton.setText(busy ? "Loading\u2026" : "Load track list");
        statusText.setText(message);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Re-ask for audio access. If the user permanently denied it, Android no longer
     * shows the dialog, so send them to the app's settings page instead of doing nothing.
     */
    private void requestAudioAccessOrSettings() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            refreshLibrary();
            return;
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_NOTIFICATIONS);
        } else {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
            toast("Enable music access in Settings");
        }
    }

    private void requestNotificationPermission() {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Without read access MediaPlayer and MediaMetadataRetriever cannot open the
        // MP3s we saved, which shows up as silent playback and blank artwork.
        String audioPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, audioPerm)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(audioPerm);
        }
        if (!wanted.isEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toArray(new String[0]), REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The first library scan runs before the prompt is answered, so it comes back
        // empty. Rescan once the user has decided, otherwise the app looks like it has
        // no music at all until the next cold start.
        if (requestCode == REQ_NOTIFICATIONS) refreshLibrary();
    }

    /** True when this build can read the MP3s it saved. */
    private boolean hasAudioAccess() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderMini();
        syncNowPlayingHighlight();
        if (playlistAdapter != null) refreshPlaylists();
    }

    @Override
    protected void onDestroy() {
        // This pool belongs to the activity, not the process. Leaving it running meant
        // every rotation or return to the app leaked one thread for good.
        io.shutdownNow();
        ProgressBus.unregister(this);
        playback.removeListener(this);
        main.removeCallbacks(miniTicker);
        if (splash != null) splash.stop();
        super.onDestroy();
    }

    /** Minimal TextWatcher so no extra dependency is needed. */
    private static class SimpleWatcher implements android.text.TextWatcher {
        interface OnText {
            void onChanged(boolean hasText);
        }

        private final OnText callback;

        SimpleWatcher(OnText callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            callback.onChanged(s.length() > 0);
        }
    }
}
