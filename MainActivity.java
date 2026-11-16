package com.aero.store;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends Activity {

    // The build reads your link from site.txt and puts it here.
    static final String SITE = "https://YOUR-SITE.pages.dev";

    WebView web;
    FrameLayout root;
    DownloadManager dm;
    SharedPreferences prefs;
    Handler h = new Handler(Looper.getMainLooper());

    // Mirrors web.getUrl(). It can be read from the JS bridge thread,
    // where calling WebView methods directly is not allowed.
    volatile String pageUrl = "";
    volatile String userAgent = "";
    boolean polling = false;
    final Object lock = new Object();

    Runnable pollTick = new Runnable() {
        @Override public void run() {
            polling = false;
            boolean active = pushState();
            if (active) {
                polling = true;
                h.postDelayed(this, 400);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        prefs = getSharedPreferences("dl", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        userAgent = s.getUserAgentString();

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("http")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                pageUrl = url == null ? "" : url;
            }
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                pageUrl = url == null ? "" : url;
            }
            @Override public void onPageFinished(WebView v, String url) {
                pageUrl = url == null ? "" : url;
                startPolling();
            }
        });

        // Fallback for links that are not started through AeroBridge (e.g. a MediaFire page).
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            String name = URLUtil.guessFileName(url, cd, mime);
            boolean ok = enqueue(url, name, ua);
            if (ok) {
                String cur = web.getUrl();
                if (cur != null && cur.contains("mediafire.com") && web.canGoBack()) web.goBack();
                startPolling();
            }
        });

        web.addJavascriptInterface(new Bridge(), "AeroBridge");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(SITE);
    }

    @Override protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(pollTick);
        super.onDestroy();
    }

    // ---------- security ----------

    static boolean isSiteUrl(String u) {
        if (u == null) return false;
        String base = SITE.endsWith("/") ? SITE.substring(0, SITE.length() - 1) : SITE;
        if (!u.startsWith(base)) return false;
        if (u.length() == base.length()) return true;
        char c = u.charAt(base.length());
        return c == '/' || c == '?' || c == '#';
    }

    boolean bridgeAllowed() {
        return isSiteUrl(pageUrl);
    }

    // ---------- url -> download id map (SharedPreferences) ----------

    JSONObject loadMap() {
        try {
            return new JSONObject(prefs.getString("map", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    void saveMap(JSONObject m) {
        prefs.edit().putString("map", m.toString()).apply();
    }

    // ---------- download engine ----------

    static String cleanName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.startsWith(".")) n = "_" + n;
        if (n.isEmpty()) n = "app.apk";
        return n;
    }

    /** Starts (or restarts) a download keyed by url. Safe to call from any thread. */
    boolean enqueue(String url, String fileName, String ua) {
        if (url == null || !url.startsWith("https://")) {
            toast("Download link must be https");
            return false;
        }
        try {
            String name = cleanName(fileName);
            synchronized (lock) {
                JSONObject map = loadMap();
                if (map.has(url)) {
                    long oldId = map.optLong(url, -1);
                    if (oldId >= 0) dm.remove(oldId);
                    map.remove(url);
                }
                File old = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                if (old.exists()) old.delete();

                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String agent = (ua == null || ua.isEmpty()) ? userAgent : ua;
                if (agent != null && !agent.isEmpty()) r.addRequestHeader("User-Agent", agent);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("Cookie", ck);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
                long id = dm.enqueue(r);

                map.put(url, id);
                saveMap(map);
            }
            return true;
        } catch (Exception e) {
            toast("Download failed to start");
            return false;
        }
    }

    void cancelDownload(String url) {
        synchronized (lock) {
            JSONObject map = loadMap();
            if (map.has(url)) {
                long id = map.optLong(url, -1);
                if (id >= 0) {
                    try { dm.remove(id); } catch (Exception e) { }
                }
                map.remove(url);
                saveMap(map);
            }
        }
    }

    /** Returns the local file for a finished download, or null. */
    File localFile(String url) {
        long id;
        synchronized (lock) {
            id = loadMap().optLong(url, -1);
        }
        if (id < 0) return null;
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        if (c == null) return null;
        try {
            if (!c.moveToFirst()) return null;
            int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (st != DownloadManager.STATUS_SUCCESSFUL) return null;
            String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (local == null) return null;
            String path = Uri.parse(local).getPath();
            if (path == null) return null;
            File f = new File(path);
            return f.exists() ? f : null;
        } finally {
            c.close();
        }
    }

    /** Builds the {url: {status,pct,got,total}} map. Drops entries whose download or file is gone. */
    JSONObject buildState() {
        JSONObject out = new JSONObject();
        synchronized (lock) {
            JSONObject map = loadMap();
            List<String> gone = new ArrayList<>();
            Iterator<String> it = map.keys();
            while (it.hasNext()) {
                String url = it.next();
                long id = map.optLong(url, -1);
                Cursor c = null;
                try {
                    if (id >= 0) c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c == null || !c.moveToFirst()) { gone.add(url); continue; }
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    String status;
                    int pct = 0;
                    if (tot > 0) pct = (int) Math.min(100L, got * 100L / tot);
                    if (st == DownloadManager.STATUS_SUCCESSFUL) {
                        String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        String path = local == null ? null : Uri.parse(local).getPath();
                        if (path == null || !new File(path).exists()) { gone.add(url); continue; }
                        status = "done";
                        pct = 100;
                    } else if (st == DownloadManager.STATUS_FAILED) {
                        status = "failed";
                    } else if (st == DownloadManager.STATUS_PAUSED) {
                        status = "paused";
                    } else {
                        status = "running";
                    }
                    JSONObject o = new JSONObject();
                    o.put("status", status);
                    o.put("pct", pct);
                    o.put("got", got);
                    o.put("total", tot > 0 ? tot : 0);
                    out.put(url, o);
                } catch (Exception e) {
                    gone.add(url);
                } finally {
                    if (c != null) c.close();
                }
            }
            if (!gone.isEmpty()) {
                for (String g : gone) map.remove(g);
                saveMap(map);
            }
        }
        return out;
    }

    static boolean hasActive(JSONObject state) {
        Iterator<String> it = state.keys();
        while (it.hasNext()) {
            JSONObject o = state.optJSONObject(it.next());
            if (o == null) continue;
            String st = o.optString("status");
            if ("running".equals(st) || "paused".equals(st)) return true;
        }
        return false;
    }

    /** Main thread only. Pushes state to the store page. Returns true while any download is active. */
    boolean pushState() {
        JSONObject st = buildState();
        if (isSiteUrl(web.getUrl())) {
            String json = st.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            web.evaluateJavascript("window.aeroOnState&&window.aeroOnState(" + json + ")", null);
        }
        return hasActive(st);
    }

    /** Push once now and keep polling every 400 ms while something is downloading. */
    void startPolling() {
        h.removeCallbacks(pollTick);
        h.post(pollTick);
    }

    void toast(final String msg) {
        h.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    void doInstall(String url) {
        File f = localFile(url);
        if (f == null) {
            toast("File not found. Please download again.");
            startPolling();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installs from this app, then tap Install again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fp", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open installer", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- JS bridge ----------

    class Bridge {
        @JavascriptInterface
        public void start(final String url, final String fileName) {
            if (!bridgeAllowed()) return;
            if (url == null || !url.startsWith("https://")) return;
            new Thread(() -> {
                enqueue(url, fileName, userAgent);
                startPolling();
            }).start();
        }

        @JavascriptInterface
        public void cancel(final String url) {
            if (!bridgeAllowed() || url == null) return;
            new Thread(() -> {
                cancelDownload(url);
                startPolling();
            }).start();
        }

        @JavascriptInterface
        public void install(final String url) {
            if (!bridgeAllowed() || url == null) return;
            h.post(() -> doInstall(url));
        }

        @JavascriptInterface
        public String state() {
            if (!bridgeAllowed()) return "{}";
            return buildState().toString();
        }
    }
}
