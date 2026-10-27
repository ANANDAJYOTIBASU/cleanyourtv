package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * weather: the current temperature after hero_clock's date, from Open-Meteo (no account or key) for the TV's own
 * location. The place is rounded to two decimals, about a kilometre, before it leaves the TV. Refreshed every
 * 30 minutes while Home is shown; the last reading and place are kept, so Home has them straight after a reboot.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 10 * 60 * 1000L;
    /** An older reading is hidden rather than shown as the current temperature. */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private static final int MAX_BYTES = 64 * 1024;

    private final Activity activity;
    private final TextView view;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    /** Bumped by every refresh and by leaving Home: work started for an older one is dropped. */
    private int generation;

    Weather(Activity activity, TextView view) {
        this.activity = activity;
        this.view = view;
        prefs = activity.getSharedPreferences("weather", Context.MODE_PRIVATE);
        show();
    }

    void refresh() {
        main.removeCallbacks(tick);
        int gen = ++generation;
        show();
        long age = System.currentTimeMillis() - prefs.getLong("time", 0);
        if (age >= 0 && age < REFRESH_MS) {
            main.postDelayed(tick, REFRESH_MS - age);
            return;
        }
        locate(gen);
    }

    void stop() {
        generation++;
        main.removeCallbacks(tick);
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    /** The newest location Android already has, or a fresh network fix, else the last place used. */
    private void locate(int gen) {
        LocationManager lm = activity.getSystemService(LocationManager.class);
        boolean permitted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (lm == null || !permitted) {
            Log.w(TAG, "no location: run pm grant " + activity.getPackageName() + " android.permission.ACCESS_FINE_LOCATION");
            fetch(gen, saved());
            return;
        }
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "last location", e);
        }
        if (best != null) {
            fetch(gen, keep(best));
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            for (String provider : new String[] {"fused", LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue;
                    lm.getCurrentLocation(provider, null, activity.getMainExecutor(), l -> {
                        if (gen == generation) fetch(gen, l != null ? keep(l) : saved());
                    });
                    return;
                } catch (RuntimeException e) {
                    Log.w(TAG, "current location from " + provider, e);
                }
            }
        }
        fetch(gen, saved());
    }

    private double[] keep(Location l) {
        double lat = Math.round(l.getLatitude() * 100) / 100.0, lon = Math.round(l.getLongitude() * 100) / 100.0;
        prefs.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).apply();
        return new double[] {lat, lon};
    }

    private double[] saved() {
        if (!prefs.contains("lat")) return null;
        return new double[] {prefs.getFloat("lat", 0f), prefs.getFloat("lon", 0f)};
    }

    private void fetch(int gen, double[] place) {
        if (place == null) {
            main.postDelayed(tick, RETRY_MS);
            return;
        }
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                + "&current=temperature_2m,weather_code,is_day", place[0], place[1]);
        worker.execute(() -> {
            JSONObject current = null;
            try {
                current = new JSONObject(download(new URL(url))).getJSONObject("current");
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "fetch: " + e);
            }
            JSONObject result = current;
            main.post(() -> {
                if (gen != generation) return;
                if (result != null && result.has("temperature_2m")) {
                    prefs.edit()
                            .putLong("time", System.currentTimeMillis())
                            .putFloat("temp", (float) result.optDouble("temperature_2m"))
                            .putInt("code", result.optInt("weather_code", 0))
                            .putBoolean("day", result.optInt("is_day", 1) == 1)
                            .apply();
                    show();
                    main.postDelayed(tick, REFRESH_MS);
                } else {
                    main.postDelayed(tick, RETRY_MS);
                }
            });
        });
    }

    private void show() {
        long age = System.currentTimeMillis() - prefs.getLong("time", 0);
        if (!prefs.contains("temp") || age < 0 || age > STALE_MS) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(String.format(Locale.getDefault(), "%d\u00B0", Math.round(prefs.getFloat("temp", 0f))));
        int size = Math.round(view.getTextSize() * 1.15f);
        Icon icon = new Icon(prefs.getInt("code", 0), prefs.getBoolean("day", true), view.getCurrentTextColor());
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawables(icon, null, null, null);
        view.setVisibility(View.VISIBLE);
    }

    private static String download(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("response too large");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** A filled glyph for a WMO weather code: sun or moon, cloud, rain, snow, storm or fog. */
    static final class Icon extends Drawable {
        private final int code;
        private final boolean day;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int code, boolean day, int color) {
            this.code = code;
            this.day = day;
            fill.setColor(color);
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float s = getBounds().width();
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            line.setStrokeWidth(s * 0.075f);
            boolean fog = code == 45 || code == 48;
            boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
            boolean storm = code >= 95;
            boolean rain = !snow && !storm && code >= 51;
            if (fog) {
                canvas.drawLine(s * 0.14f, s * 0.34f, s * 0.86f, s * 0.34f, line);
                canvas.drawLine(s * 0.24f, s * 0.52f, s * 0.80f, s * 0.52f, line);
                canvas.drawLine(s * 0.14f, s * 0.70f, s * 0.70f, s * 0.70f, line);
            } else if (code <= 1) {
                drawSunOrMoon(canvas, s, 0.5f, 0.5f, 1f, null);
            } else if (code == 2) {
                Path cloud = cloud(s, 0.08f, 0.06f, 0f);
                drawSunOrMoon(canvas, s, 0.38f, 0.38f, 0.7f, cloud(s, 0.08f, 0.06f, 0.07f));
                canvas.drawPath(cloud, fill);
            } else {
                float lift = code == 3 ? 0f : -0.12f;
                canvas.drawPath(cloud(s, 0f, lift, 0f), fill);
                if (rain) {
                    for (float x : new float[] {0.38f, 0.56f, 0.74f}) {
                        canvas.drawLine(s * x, s * 0.76f, s * (x - 0.06f), s * 0.92f, line);
                    }
                } else if (snow) {
                    for (float[] p : new float[][] {{0.36f, 0.80f}, {0.56f, 0.88f}, {0.76f, 0.80f}}) {
                        canvas.drawCircle(s * p[0], s * p[1], s * 0.05f, fill);
                    }
                } else if (storm) {
                    Path bolt = new Path();
                    bolt.moveTo(s * 0.58f, s * 0.68f);
                    bolt.lineTo(s * 0.44f, s * 0.86f);
                    bolt.lineTo(s * 0.54f, s * 0.86f);
                    bolt.lineTo(s * 0.47f, s * 1.0f);
                    bolt.lineTo(s * 0.66f, s * 0.80f);
                    bolt.lineTo(s * 0.56f, s * 0.80f);
                    bolt.lineTo(s * 0.64f, s * 0.68f);
                    bolt.close();
                    canvas.drawPath(bolt, fill);
                }
            }
            canvas.restore();
        }

        /** The sun with rays by day, a crescent moon at night; cut is a cloud to leave a gap around. */
        private void drawSunOrMoon(Canvas canvas, float s, float cx, float cy, float scale, Path cut) {
            Path body = new Path();
            if (day) {
                body.addCircle(s * cx, s * cy, s * 0.2f * scale, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float c = (float) Math.cos(a), n = (float) Math.sin(a);
                    Path ray = new Path();
                    ray.moveTo(s * (cx + c * 0.31f * scale), s * (cy + n * 0.31f * scale));
                    ray.lineTo(s * (cx + c * 0.43f * scale), s * (cy + n * 0.43f * scale));
                    Path stroked = new Path();
                    line.getFillPath(ray, stroked);
                    body.op(stroked, Path.Op.UNION);
                }
            } else {
                body.addCircle(s * cx, s * cy, s * 0.34f * scale, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(s * (cx + 0.2f * scale), s * (cy - 0.14f * scale), s * 0.28f * scale, Path.Direction.CW);
                body.op(bite, Path.Op.DIFFERENCE);
            }
            if (cut != null) body.op(cut, Path.Op.DIFFERENCE);
            canvas.drawPath(body, fill);
        }

        /** A cloud with a flat base, moved by (dx, dy) and grown by grow (all as fractions of the size). */
        private static Path cloud(float s, float dx, float dy, float grow) {
            Path p = new Path();
            p.addCircle(s * (0.33f + dx), s * (0.62f + dy), s * (0.16f + grow), Path.Direction.CW);
            p.addCircle(s * (0.53f + dx), s * (0.50f + dy), s * (0.22f + grow), Path.Direction.CW);
            p.addCircle(s * (0.72f + dx), s * (0.63f + dy), s * (0.15f + grow), Path.Direction.CW);
            p.addRect(s * (0.33f + dx), s * (0.55f + dy), s * (0.72f + dx), s * (0.78f + dy + grow), Path.Direction.CW);
            Path union = new Path();
            union.op(p, Path.Op.UNION);
            return union;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            line.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
