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
import android.view.View;
import android.view.WindowManager;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
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

    /* Shown instead of the default Android error page when the site cannot load. */
    static final String OFFLINE_HTML = """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Offline</title>
<style>
:root{--bg1:#0a0f24;--bg2:#141b3d;--fg:#f2f5ff;--sub:#9aa6d1;--acc:#5b8cff;--acc2:#8a5bff;--ring:91,140,255}
@media(prefers-color-scheme:light){:root{--bg1:#f4f7ff;--bg2:#e3eaff;--fg:#10183a;--sub:#5b6794;--acc:#3b6bff;--acc2:#7a4bff;--ring:59,107,255}}
*{box-sizing:border-box;margin:0;-webkit-tap-highlight-color:transparent}
html,body{height:100%}
body{font-family:-apple-system,Roboto,"Segoe UI",sans-serif;color:var(--fg);overflow:hidden;
background:radial-gradient(120% 80% at 50% 0%,var(--bg2),var(--bg1));display:flex;align-items:center;justify-content:center;text-align:center;padding:24px}
.wrap{position:relative;z-index:2;display:flex;flex-direction:column;align-items:center;max-width:340px}
.stage{position:relative;width:230px;height:230px;display:flex;align-items:center;justify-content:center;margin-bottom:26px}
.ring{position:absolute;inset:0;margin:auto;width:120px;height:120px;border-radius:50%;border:2px solid rgba(var(--ring),.55);opacity:0;animation:ripple 3.6s cubic-bezier(.2,.6,.3,1) infinite}
.ring:nth-child(2){animation-delay:1.2s}.ring:nth-child(3){animation-delay:2.4s}
@keyframes ripple{0%{transform:scale(.7);opacity:.7}100%{transform:scale(2);opacity:0}}
.core{position:relative;width:118px;height:118px;border-radius:50%;display:flex;align-items:center;justify-content:center;
background:linear-gradient(145deg,var(--acc),var(--acc2));box-shadow:0 18px 50px rgba(var(--ring),.45),inset 0 2px 6px rgba(255,255,255,.35);animation:float 4s ease-in-out infinite}
@keyframes float{0%,100%{transform:translateY(-6px)}50%{transform:translateY(8px)}}
.core svg{width:62px;height:62px;overflow:visible}
.arc{fill:none;stroke:#fff;stroke-width:5;stroke-linecap:round;animation:blink 2.4s ease-in-out infinite}
.a2{animation-delay:.25s}.a3{animation-delay:.5s}
.dot{fill:#fff;animation:blink 2.4s ease-in-out infinite .75s}
@keyframes blink{0%,100%{opacity:.25}40%{opacity:1}}
.slash{stroke:#fff;stroke-width:5.5;stroke-linecap:round;stroke-dasharray:62;stroke-dashoffset:62;animation:draw .9s .5s cubic-bezier(.6,0,.2,1) forwards;filter:drop-shadow(0 0 3px rgba(0,0,0,.35))}
@keyframes draw{to{stroke-dashoffset:0}}
.p{position:fixed;bottom:-10px;border-radius:50%;background:rgba(var(--ring),.5);animation:rise linear infinite;z-index:1}
@keyframes rise{0%{transform:translateY(0);opacity:0}15%{opacity:.8}100%{transform:translateY(-105vh);opacity:0}}
h1{font-size:24px;font-weight:700;letter-spacing:.2px;opacity:0;animation:up .7s .2s ease forwards}
p.s{margin-top:10px;font-size:15px;line-height:1.55;color:var(--sub);opacity:0;animation:up .7s .4s ease forwards}
@keyframes up{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}
.pill{margin-top:22px;display:inline-flex;align-items:center;gap:8px;font-size:13px;color:var(--sub);padding:8px 14px;border-radius:99px;background:rgba(var(--ring),.12);opacity:0;animation:up .7s .6s ease forwards}
.pill i{width:7px;height:7px;border-radius:50%;background:var(--acc);animation:pulse 1.2s ease-in-out infinite}
@keyframes pulse{0%,100%{transform:scale(.7);opacity:.5}50%{transform:scale(1.3);opacity:1}}
button{margin-top:26px;border:0;color:#fff;font:600 16px inherit;font-family:inherit;padding:15px 38px;border-radius:16px;min-width:180px;
background:linear-gradient(135deg,var(--acc),var(--acc2));box-shadow:0 10px 26px rgba(var(--ring),.4);position:relative;transition:transform .15s,box-shadow .15s;
opacity:0;animation:up .7s .8s ease forwards}
button:active{transform:scale(.95);box-shadow:0 4px 12px rgba(var(--ring),.4)}
button .sp{display:none;width:18px;height:18px;border:3px solid rgba(255,255,255,.4);border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;margin:0 auto}
button.load .t{display:none}button.load .sp{display:block}
@keyframes spin{to{transform:rotate(360deg)}}
.shake{animation:shake .45s}
@keyframes shake{20%,60%{transform:translateX(-7px)}40%,80%{transform:translateX(7px)}}
@media(prefers-reduced-motion:reduce){*{animation-duration:.01s!important;animation-iteration-count:1!important}}
</style></head><body>
<div id="ps"></div>
<div class="wrap">
 <div class="stage">
  <div class="ring"></div><div class="ring"></div><div class="ring"></div>
  <div class="core">
   <svg viewBox="0 0 64 64">
    <path class="arc a1" d="M8 25a34 34 0 0 1 48 0"/>
    <path class="arc a2" d="M17 34a21 21 0 0 1 30 0"/>
    <path class="arc a3" d="M26 43a8 8 0 0 1 12 0"/>
    <circle class="dot" cx="32" cy="53" r="3.5"/>
    <line class="slash" x1="10" y1="8" x2="54" y2="58"/>
   </svg>
  </div>
 </div>
 <h1>No Internet Connection</h1>
 <p class="s">Please check your Wi-Fi or mobile data. Aero Store will reconnect automatically.</p>
 <div class="pill"><i></i><span>Waiting for connection</span></div>
 <button id="b" onclick="retry()"><span class="t">Try Again</span><span class="sp"></span></button>
</div>
<script>
var ps=document.getElementById('ps');
for(var i=0;i<14;i++){var d=document.createElement('div');d.className='p';var z=4+Math.random()*7;
d.style.cssText='left:'+Math.random()*100+'%;width:'+z+'px;height:'+z+'px;animation-duration:'+(7+Math.random()*7)+'s;animation-delay:'+(Math.random()*8)+'s';ps.appendChild(d)}
var busy=false,b=document.getElementById('b');
function retry(){
 if(busy)return;busy=true;b.classList.add('load');
 setTimeout(function(){
  try{AeroBridge.retry()}catch(e){location.reload()}
  setTimeout(function(){busy=false;b.classList.remove('load');b.classList.add('shake');
   setTimeout(function(){b.classList.remove('shake')},500)},4000);
 },600);
}
window.addEventListener('online',retry);
</script>
</body></html>
""";

    WebView web;
    String failedUrl = null;
    FrameLayout root;
    DownloadManager dm;
    SharedPreferences prefs;
    Handler h = new Handler(Looper.getMainLooper());

    volatile String pendingKey = null; // app link the user tapped before a MediaFire page opened
    String lastJson = null;
    boolean polling = false;
    boolean active = false;
    int topInsetPx = 0;

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

        // Edge-to-edge: the page draws behind a transparent status bar.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            topInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            applyInsets();
            return insets; // not consumed, WebView sits under the bar
        });
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
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    failedUrl = r.getUrl().toString();
                    v.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "UTF-8", null);
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                applyInsets();
                push(true);
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> startFromWeb(url, ua, cd, mime));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(SITE);
        hideNav();
    }

    /* Tell the page how tall the status bar is (CSS var --sat) and make the bar icons follow the page theme. */
    void applyInsets() {
        if (web == null) return;
        float d = getResources().getDisplayMetrics().density;
        String js = "(function(){try{"
            + "document.documentElement.style.setProperty('--sat','" + (topInsetPx / d) + "px');"
            + "if(window.__aeroBar)return;window.__aeroBar=1;"
            + "function f(){try{var t=document.createElement('div');"
            + "t.style.cssText='display:none;background:var(--bg,#fff)';document.documentElement.appendChild(t);"
            + "var c=getComputedStyle(t).backgroundColor.match(/[\\d.]+/g);t.remove();"
            + "var l=0.299*c[0]+0.587*c[1]+0.114*c[2];AeroBridge.bar(l<140);}catch(e){}}"
            + "f();new MutationObserver(f).observe(document.documentElement,{attributes:true});"
            + "var m=matchMedia('(prefers-color-scheme:dark)');"
            + "if(m.addEventListener)m.addEventListener('change',f);"
            + "}catch(e){}})()";
        web.evaluateJavascript(js, null);
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
        @JavascriptInterface public void retry() {
            h.post(() -> web.loadUrl(failedUrl != null ? failedUrl : SITE));
        }
        @JavascriptInterface public void bar(final boolean darkPage) {
            h.post(() -> {
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                if (c != null) c.setAppearanceLightStatusBars(!darkPage);
            });
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
