package com.aero.store;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Aero Store wrapper.
 *
 * All download / install UI (Get button, progress ring, Downloads page) now lives in the
 * web page so it follows the selected app theme (light / dark / green / glass).
 * This class only does the native work and exposes it to the page as "AeroBridge":
 *   start(url, fileName)  - download a direct link
 *   cancel(url)           - cancel or remove a download
 *   install(url)          - open the Android installer for a finished download
 *   mark(url)             - remember which app a MediaFire page link belongs to
 *   state()               - JSON of all downloads: {url:{status,pct,got,total}}
 * and pushes changes back with window.aeroOnState(json).
 */
public class MainActivity extends Activity {

    // The build reads your link from site.txt and puts it here.
    static final String SITE = "https://YOUR-SITE.pages.dev";

    WebView web;
    FrameLayout root;
    DownloadManager dm;
    SharedPreferences prefs;
    Handler h = new Handler(Looper.getMainLooper());

    volatile String pendingKey = null; // app link the user tapped before a MediaFire page opened
    String lastJson = null;
    boolean polling = false;
    boolean active = false;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            push(false);
            if (active) {
                h.postDelayed(this, 500);
            } else {
                polling = false;
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
        web.addJavascriptInterface(new Bridge(), "AeroBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("http")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception e) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                push(true);
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> startFromWeb(url, ua, cd, mime));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(SITE);
        hideNav();
    }

    /* Keep the status bar visible, hide the navigation bar (swipe from the edge shows it briefly). */
    void hideNav() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (c == null) return;
        c.hide(WindowInsetsCompat.Type.navigationBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNav();
    }

    @Override protected void onResume() {
        super.onResume();
        hideNav();
        push(true);
        ensurePolling();
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    /* ---------- bridge exposed to the web page ---------- */

    class Bridge {
        @JavascriptInterface public void start(final String key, final String name) {
            h.post(() -> startDirect(key, name));
        }
        @JavascriptInterface public void cancel(final String key) {
            h.post(() -> removeKey(key));
        }
        @JavascriptInterface public void install(final String key) {
            h.post(() -> installKey(key));
        }
        @JavascriptInterface public void mark(String key) {
            pendingKey = key;
        }
        @JavascriptInterface public String state() {
            return buildState().toString();
        }
    }

    /* ---------- key -> DownloadManager id map (survives app restarts) ---------- */

    JSONObject map() {
        try { return new JSONObject(prefs.getString("map", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    void saveMap(JSONObject m) {
        prefs.edit().putString("map", m.toString()).apply();
    }

    File dir() {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    }

    /* ---------- starting / removing downloads ---------- */

    void startDirect(String key, String name) {
        try {
            File old = new File(dir(), name);
            if (old.exists()) old.delete();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(key));
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            enqueue(key, r);
        } catch (Exception e) {
            Toast.makeText(this, "Download failed to start", Toast.LENGTH_LONG).show();
        }
    }

    // MediaFire (and any other page) hands the file to the WebView's download listener.
    void startFromWeb(String url, String ua, String cd, String mime) {
        String name = URLUtil.guessFileName(url, cd, mime);
        String key = pendingKey != null ? pendingKey : url;
        pendingKey = null;
        try {
            File old = new File(dir(), name);
            if (old.exists()) old.delete();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            enqueue(key, r);
        } catch (Exception e) {
            Toast.makeText(this, "Download failed to start", Toast.LENGTH_LONG).show();
        }
        String cur = web.getUrl();
        if (cur != null && cur.contains("mediafire.com") && web.canGoBack()) web.goBack();
    }

    void enqueue(String key, DownloadManager.Request r) {
        JSONObject m = map();
        long prev = m.optLong(key, -1);
        if (prev >= 0) dm.remove(prev);
        long id = dm.enqueue(r);
        try { m.put(key, id); } catch (Exception e) { }
        saveMap(m);
        push(true);
        ensurePolling();
    }

    void removeKey(String key) {
        JSONObject m = map();
        long id = m.optLong(key, -1);
        if (id >= 0) dm.remove(id);
        m.remove(key);
        saveMap(m);
        push(true);
    }

    /* ---------- state -> web page ---------- */

    JSONObject buildState() {
        JSONObject m = map();
        JSONObject out = new JSONObject();
        List<String> keys = new ArrayList<>();
        Iterator<String> it = m.keys();
        while (it.hasNext()) keys.add(it.next());

        boolean act = false, dirty = false;
        for (String k : keys) {
            long id = m.optLong(k, -1);
            Cursor c = id < 0 ? null : dm.query(new DownloadManager.Query().setFilterById(id));
            try {
                if (c == null || !c.moveToFirst()) { m.remove(k); dirty = true; continue; }
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String status;
                if (st == DownloadManager.STATUS_SUCCESSFUL) status = "done";
                else if (st == DownloadManager.STATUS_FAILED) status = "failed";
                else if (st == DownloadManager.STATUS_PAUSED) { status = "paused"; act = true; }
                else { status = "running"; act = true; }
                int pct = tot > 0 ? (int) Math.min(100, got * 100 / tot) : 0;
                JSONObject o = new JSONObject();
                o.put("status", status);
                o.put("pct", pct);
                o.put("got", got);
                o.put("total", tot);
                out.put(k, o);
            } catch (Exception e) {
                // skip this entry
            } finally {
                if (c != null) c.close();
            }
        }
        if (dirty) saveMap(m);
        active = act;
        return out;
    }

    void push(boolean force) {
        if (web == null) return;
        String json = buildState().toString();
        if (!force && json.equals(lastJson)) return;
        lastJson = json;
        web.evaluateJavascript("window.aeroOnState&&window.aeroOnState(" + json + ")", null);
    }

    void ensurePolling() {
        if (polling) return;
        polling = true;
        h.removeCallbacks(tick);
        h.postDelayed(tick, 500);
    }

    /* ---------- install ---------- */

    void installKey(String key) {
        long id = map().optLong(key, -1);
        if (id < 0) return;
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        String local = null;
        try {
            if (c != null && c.moveToFirst()
                    && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            }
        } finally {
            if (c != null) c.close();
        }
        install(local);
    }

    void install(String localUri) {
        if (localUri == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installs from this app, then tap Install again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            File f = new File(Uri.parse(localUri).getPath());
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fp", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open installer", Toast.LENGTH_LONG).show();
        }
    }
}
