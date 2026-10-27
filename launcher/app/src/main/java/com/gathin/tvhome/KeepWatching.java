package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * keep_watching: the apps' Continue watching entries (the TV provider's Watch Next table) as a row of cards above
 * the dock. Reading the table needs READ_TV_LISTINGS, granted with pm grant; posters are fetched over HTTPS only,
 * or read from an app's own content provider, and nothing else goes online.
 */
final class KeepWatching {
    interface Host {
        void onKeepWatchingFocused();

        void onKeepWatchingDown();
    }

    private static final String TAG = "TvHome.KeepWatching";
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final int MAX_ITEMS = 6;
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    private static final int MAX_POSTER_BYTES = 4 * 1024 * 1024;
    /** A row this far through counts as watched. */
    private static final float WATCHED = 0.97f;

    private static final class Item {
        String pkg, title, subtitle, poster, intent;
        float progress;
        long engaged;
    }

    private final Activity activity;
    private final View box;
    private final LinearLayout row;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> posters = new LruCache<>(24);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int posterWidth;
    /** item_keep_watching's width and gap, read from the first card, and the row's width once MainActivity fits it. */
    private int cardWidth, cardGap, rowWidth;
    /** Bumped by every refresh and by leaving Home: work started for an older one is dropped. */
    private int generation;

    KeepWatching(Activity activity, View box, Host host) {
        this.activity = activity;
        this.box = box;
        this.row = box.findViewById(R.id.keepWatchingRow);
        this.host = host;
        posterWidth = Math.round(106 * activity.getResources().getDisplayMetrics().density);
    }

    void refresh() {
        int gen = ++generation;
        worker.execute(() -> {
            List<Item> items = query();
            main.post(() -> {
                if (gen == generation) show(items, gen);
            });
        });
    }

    void stop() {
        generation++;
    }

    void close() {
        generation++;
        worker.shutdownNow();
    }

    /** The row is width pixels wide, the dock band's width. */
    void fit(int width) {
        if (width <= 0 || width == rowWidth) return;
        rowWidth = width;
        sizeCards();
    }

    /** As many whole cards as fit, widened to fill the row exactly, so the cards end where the dock band does. */
    private void sizeCards() {
        int count = row.getChildCount();
        if (count == 0 || rowWidth == 0) return;
        int perRow = Math.max(1, (rowWidth + cardGap) / (cardWidth + cardGap));
        int width = (rowWidth - (perRow - 1) * cardGap) / perRow;
        for (int i = 0; i < count; i++) {
            View card = row.getChildAt(i);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            lp.width = width;
            lp.setMarginEnd(i == count - 1 ? 0 : cardGap);
            card.setLayoutParams(lp);
        }
    }

    private List<Item> query() {
        List<Item> items = new ArrayList<>();
        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, PERMISSION + " is not granted: run pm grant " + activity.getPackageName() + " " + PERMISSION);
            return items;
        }
        PackageManager pm = activity.getPackageManager();
        long now = System.currentTimeMillis();
        // No selection: a reader without ACCESS_ALL_EPG_DATA gets a SecurityException for one (docs/watch-next.md).
        try (Cursor c = activity.getContentResolver().query(WATCH_NEXT, null, null, null, null)) {
            if (c == null) return items;
            int pkg = c.getColumnIndex("package_name"), title = c.getColumnIndex("title");
            int season = c.getColumnIndex("season_display_number"), episode = c.getColumnIndex("episode_display_number");
            int episodeTitle = c.getColumnIndex("episode_title"), intent = c.getColumnIndex("intent_uri");
            int poster = c.getColumnIndex("poster_art_uri"), thumb = c.getColumnIndex("thumbnail_uri");
            int position = c.getColumnIndex("last_playback_position_millis"), duration = c.getColumnIndex("duration_millis");
            int engaged = c.getColumnIndex("last_engagement_time_utc_millis"), browsable = c.getColumnIndex("browsable");
            while (c.moveToNext()) {
                Item it = new Item();
                it.pkg = text(c, pkg);
                it.title = text(c, title);
                it.intent = text(c, intent);
                if (it.pkg == null || it.title == null || it.intent == null) continue;
                if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue;
                it.engaged = engaged >= 0 && !c.isNull(engaged) ? c.getLong(engaged) : 0;
                if (it.engaged > 0 && now - it.engaged > MAX_AGE_MS) continue;
                long pos = position >= 0 && !c.isNull(position) ? c.getLong(position) : 0;
                long dur = duration >= 0 && !c.isNull(duration) ? c.getLong(duration) : 0;
                it.progress = dur > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
                if (it.progress >= WATCHED) continue;
                String s = text(c, season), e = text(c, episode), et = text(c, episodeTitle);
                it.subtitle = s != null && e != null ? "S" + s + " E" + e + (et != null ? " \u00B7 " + et : "")
                        : e != null ? "E" + e : et;
                it.poster = text(c, poster);
                if (it.poster == null) it.poster = text(c, thumb);
                if (!installed(pm, it.pkg)) continue;
                items.add(it);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read Continue watching", e);
            return new ArrayList<>();
        }
        items.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item it : items) {
            if (seen.add(it.pkg + '\n' + it.title.toLowerCase(Locale.ROOT))) unique.add(it);
            if (unique.size() == MAX_ITEMS) break;
        }
        return unique;
    }

    private static String text(Cursor c, int column) {
        if (column < 0 || c.isNull(column)) return null;
        String s = c.getString(column);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void show(List<Item> items, int gen) {
        boolean hadFocus = row.hasFocus();
        row.removeAllViews();
        box.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (items.isEmpty()) {
            if (hadFocus) host.onKeepWatchingDown();
            return;
        }
        PackageManager pm = activity.getPackageManager();
        LayoutInflater inflater = LayoutInflater.from(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            View card = inflater.inflate(R.layout.item_keep_watching, row, false);
            ImageView poster = card.findViewById(R.id.kwPoster);
            View posterBox = card.findViewById(R.id.kwPosterBox);
            posterBox.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * density);
                }
            });
            posterBox.setClipToOutline(true);
            CharSequence appName = it.pkg;
            try {
                ApplicationInfo info = pm.getApplicationInfo(it.pkg, 0);
                appName = info.loadLabel(pm);
                ((ImageView) card.findViewById(R.id.kwAppIcon)).setImageDrawable(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            ((TextView) card.findViewById(R.id.kwApp)).setText(appName);
            ((TextView) card.findViewById(R.id.kwTitle)).setText(it.title);
            TextView subtitle = card.findViewById(R.id.kwSubtitle);
            subtitle.setText(it.subtitle);
            subtitle.setVisibility(it.subtitle == null ? View.GONE : View.VISIBLE);
            View progress = card.findViewById(R.id.kwProgress);
            View track = card.findViewById(R.id.kwProgressTrack);
            if (it.progress > 0f) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progress.getLayoutParams();
                lp.width = Math.max(1, Math.round(posterWidth * it.progress));
                progress.setLayoutParams(lp);
            } else {
                progress.setVisibility(View.GONE);
                track.setVisibility(View.GONE);
            }
            card.setContentDescription(appName + ", " + it.title);
            card.setOnClickListener(v -> open(it));
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    // A card at either end of the visible row grows inwards, staying inside the dock band's width.
                    View scroll = (View) row.getParent();
                    int x = v.getLeft() - scroll.getScrollX();
                    v.setPivotX(x < cardGap ? 0f : x + v.getWidth() > scroll.getWidth() - cardGap ? v.getWidth() : v.getWidth() / 2f);
                    host.onKeepWatchingFocused();
                }
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
            });
            boolean first = i == 0, last = i == items.size() - 1;
            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        host.onKeepWatchingDown();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
            row.addView(card);
            Bitmap cached = it.poster == null ? null : posters.get(it.poster);
            if (cached != null) {
                poster.setImageBitmap(cached);
            } else if (it.poster != null) {
                String uri = it.poster;
                worker.execute(() -> {
                    if (gen != generation) return;
                    Bitmap bitmap = loadPoster(uri);
                    if (bitmap == null) return;
                    main.post(() -> {
                        posters.put(uri, bitmap);
                        if (gen == generation && poster.isAttachedToWindow()) poster.setImageBitmap(bitmap);
                    });
                });
            }
        }
        if (cardWidth == 0) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getChildAt(0).getLayoutParams();
            cardWidth = lp.width;
            cardGap = lp.getMarginEnd();
        }
        sizeCards();
        if (hadFocus) row.getChildAt(0).requestFocus();
    }

    /**
     * Opens the entry with the intent its app listed, kept to that app: no other package or component, no selector and
     * no URI grants, so an entry cannot use the launcher to open something else. Falls back to the app's start screen.
     */
    private void open(Item it) {
        try {
            Intent intent = Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME);
            ComponentName component = intent.getComponent();
            if (component != null && !component.getPackageName().equals(it.pkg)) throw new SecurityException("other app");
            intent.setSelector(null);
            intent.setPackage(it.pkg);
            intent.setFlags((intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
            PackageManager pm = activity.getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(it.pkg);
            if (launch == null) return;
            try {
                activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    private Bitmap loadPoster(String uri) {
        byte[] data;
        try {
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme();
            if ("https".equalsIgnoreCase(scheme)) {
                data = download(new URL(uri));
            } else if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                try (InputStream in = activity.getContentResolver().openInputStream(parsed)) {
                    data = in == null ? null : readLimited(in);
                }
            } else {
                return null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (data == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sample = 1;
        while (options.outWidth / (sample * 2) >= posterWidth * 2) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static byte[] download(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            if (connection.getContentLengthLong() > MAX_POSTER_BYTES) return null;
            try (InputStream in = connection.getInputStream()) {
                return readLimited(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int total = 0;
        for (int n; (n = in.read(buffer)) != -1; ) {
            total += n;
            if (total > MAX_POSTER_BYTES) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
