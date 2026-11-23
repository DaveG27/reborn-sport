package com.rebornsport.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reborn Sport: shows the page in assets/index.html and gives it three native helpers.
 * - fetch: requests to the IPTV server are made from the phone (no browser limits),
 * - login storage in Android's encrypted preferences,
 * - copying text to the clipboard,
 * - kick-off reminders (notifications) and the home-screen widget's data.
 */
public class MainActivity extends Activity {

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(12);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();
        Reminders.ensureChannel(this);

        web = new WebView(this);
        // Dark while loading, so there is no white flash (and none if the page reloads).
        web.setBackgroundColor(Color.parseColor("#050A11"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        disableForceDark(s);
        web.setWebViewClient(new WebViewClient() {
            // If the page's engine runs out of memory, rebuild the screen instead of the app closing.
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                view.destroy();
                recreate();
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.requestFocus();
        web.loadUrl("file:///android_asset/index.html");
    }

    /** The page has its own dark mode; stop Android TV darkening it a second time. */
    @SuppressWarnings("deprecation")
    private static void disableForceDark(WebSettings s) {
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) {
            try { s.setForceDark(WebSettings.FORCE_DARK_OFF); } catch (Throwable ignored) {}
        }
    }

    private SharedPreferences openPrefs() {
        try {
            String key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    "login", key, this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Very old or unusual devices: fall back to the app's private storage.
            return getSharedPreferences("login_plain", Context.MODE_PRIVATE);
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Reads a JSON reply piece by piece and keeps only the named fields, so huge
     * channel lists and guides never reach the page in full (TV boxes run out of memory).
     * Handles a top-level array of objects, or an object with an "epg_listings" array.
     */
    static String slim(InputStream in, Set<String> keep) throws IOException {
        JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(in, 65536), StandardCharsets.UTF_8));
        r.setLenient(true);
        StringWriter sw = new StringWriter();
        JsonWriter w = new JsonWriter(sw);
        JsonToken t = r.peek();
        if (t == JsonToken.BEGIN_ARRAY) {
            slimArray(r, w, keep);
        } else if (t == JsonToken.BEGIN_OBJECT) {
            r.beginObject();
            w.beginObject();
            while (r.hasNext()) {
                String n = r.nextName();
                if ("epg_listings".equals(n) && r.peek() == JsonToken.BEGIN_ARRAY) { w.name(n); slimArray(r, w, keep); }
                else if (keep.contains(n)) { w.name(n); copyScalar(r, w); }
                else r.skipValue();
            }
            r.endObject();
            w.endObject();
        } else {
            r.skipValue();
            w.nullValue();
        }
        w.close();
        r.close();
        return sw.toString();
    }

    private static void slimArray(JsonReader r, JsonWriter w, Set<String> keep) throws IOException {
        r.beginArray();
        w.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                w.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if (keep.contains(n)) { w.name(n); copyScalar(r, w); } else r.skipValue();
                }
                r.endObject();
                w.endObject();
            } else r.skipValue();
        }
        r.endArray();
        w.endArray();
    }

    private static void copyScalar(JsonReader r, JsonWriter w) throws IOException {
        switch (r.peek()) {
            case STRING: w.value(r.nextString()); break;
            case NUMBER: w.value(r.nextString()); break;   // kept as text; the page converts
            case BOOLEAN: w.value(r.nextBoolean()); break;
            case NULL: r.nextNull(); w.nullValue(); break;
            default: r.skipValue(); w.nullValue();
        }
    }

    private void deliver(String id, int code, String body) {
        final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private class Bridge {
        /** Like fetch, but only the comma-separated fields are passed back to the page. */
        @JavascriptInterface
        public void fetchSlim(final String id, final String url, final String userAgent, final String fields) {
            pool.execute(() -> {
                int code = 0;
                String body;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", userAgent);
                    c.setRequestProperty("Accept", "*/*");
                    code = c.getResponseCode();
                    if (code >= 400) {
                        InputStream es = c.getErrorStream();
                        body = es == null ? "" : readAll(es);
                    } else {
                        body = slim(c.getInputStream(), new HashSet<>(Arrays.asList(fields.split(","))));
                    }
                } catch (Exception e) {
                    body = e.getClass().getSimpleName();
                    if (code == 200) code = 0;
                } finally {
                    if (c != null) c.disconnect();
                }
                deliver(id, code, body);
            });
        }

        @JavascriptInterface
        public void fetch(final String id, final String url, final String userAgent) {
            pool.execute(() -> {
                int code = 0;
                String body;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", userAgent);
                    c.setRequestProperty("Accept", "*/*");
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    body = in == null ? "" : readAll(in);
                } catch (Exception e) {
                    body = e.getClass().getSimpleName();
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public String getLogin() {
            return prefs.getString("login", "");
        }

        @JavascriptInterface
        public void setLogin(String json) {
            prefs.edit().putString("login", json == null ? "" : json).apply();
        }

        @JavascriptInterface
        public void setReminder(String id, String at, String title, String text) {
            try { Reminders.set(MainActivity.this, id, Long.parseLong(at), title, text); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void cancelReminder(String id) {
            Reminders.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public String getReminders() {
            return Reminders.all(MainActivity.this).toString();
        }

        /** Android 13+ asks the user before an app may show notifications. */
        @JavascriptInterface
        public void askNotifications() {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7));
            }
        }

        @JavascriptInterface
        public void saveWidget(String json) {
            getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString("games", json == null ? "" : json).apply();
            WidgetProvider.refreshAll(MainActivity.this);
            pool.execute(() -> TvRow.update(MainActivity.this, json));
        }

        /** Installed apps that can play a video stream, e.g. VLC or MX Player. */
        @JavascriptInterface
        public String getPlayers() {
            JSONArray out = new JSONArray();
            try {
                Intent probe = new Intent(Intent.ACTION_VIEW);
                probe.setDataAndType(Uri.parse("http://example.com/live.m3u8"), "video/*");
                for (ResolveInfo r : getPackageManager().queryIntentActivities(probe, 0)) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    boolean dup = false;
                    for (int i = 0; i < out.length(); i++) if (out.getJSONObject(i).optString("pkg").equals(pkg)) dup = true;
                    if (dup) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", String.valueOf(r.loadLabel(getPackageManager())));
                    out.put(o);
                }
            } catch (Exception ignored) {}
            return out.toString();
        }

        /** mode: "builtin", "ask", or an installed player's package name. */
        @JavascriptInterface
        public void play(String urlsJson, String title, String mode, String userAgent) {
            runOnUiThread(() -> {
                try {
                    JSONArray a = new JSONArray(urlsJson);
                    String[] urls = new String[a.length()];
                    for (int i = 0; i < a.length(); i++) urls[i] = a.getString(i);
                    if (urls.length == 0) return;
                    if ("builtin".equals(mode)) {
                        Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                        i.putExtra("urls", urls);
                        i.putExtra("title", title);
                        i.putExtra("ua", userAgent);
                        startActivity(i);
                        return;
                    }
                    Intent v = new Intent(Intent.ACTION_VIEW);
                    v.setDataAndType(Uri.parse(urls[0]), "video/*");
                    v.putExtra("title", title);
                    if ("ask".equals(mode)) {
                        startActivity(Intent.createChooser(v, "Watch with"));
                    } else {
                        v.setPackage(mode);
                        startActivity(v);
                    }
                } catch (Exception e) {
                    // Chosen player missing: fall back to the built-in one.
                    try {
                        JSONArray a = new JSONArray(urlsJson);
                        String[] urls = new String[a.length()];
                        for (int i = 0; i < a.length(); i++) urls[i] = a.getString(i);
                        Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                        i.putExtra("urls", urls);
                        i.putExtra("title", title);
                        i.putExtra("ua", userAgent);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public int buildNumber() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception e) { return 0; }
        }

        /** The version people see, e.g. "1.0". */
        @JavascriptInterface
        public String versionName() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        /** Opens a web link (e.g. the new APK) in the browser. */
        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            });
        }

        /** TV remotes: open the on-screen keyboard for the focused search box. */
        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(() -> {
                web.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface
        public void hideKeyboard() {
            runOnUiThread(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            });
        }

        @JavascriptInterface
        public boolean isTv() {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Reborn Sport", text));
            });
        }
    }
}
