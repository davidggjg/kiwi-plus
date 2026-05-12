package com.kiwiplus.browser;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnRefresh, btnMedia, btnHome, btnMenu;
    private SwipeRefreshLayout swipeRefresh;
    private View splashScreen;
    private TextView splashTitle, splashSubtitle;
    private List<String> mediaUrls = new ArrayList<>();
    private boolean isHomePage = false;
    private boolean isDesktopMode = false;
    private boolean hlsInjected = false;
    private Set<String> blockedHosts = new HashSet<>();
    private SharedPreferences prefs;

    private static final String[][] DEFAULT_SHORTCUTS = {
        {"GitHub", "https://github.com", "🐙"},
        {"YouTube", "https://youtube.com", "▶️"},
        {"Twitter", "https://twitter.com", "🐦"},
        {"Reddit", "https://reddit.com", "🤖"},
        {"Instagram", "https://instagram.com", "📸"},
        {"WhatsApp", "https://web.whatsapp.com", "💬"},
        {"DuckDuckGo", "https://duckduckgo.com", "🔍"},
        {"Telegram", "https://telegram.org", "✈️"}
    };

    private static final String PROXY_URL = "https://kiwiplus-proxy.onrender.com/";

    private static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Pattern MEDIA_PATTERN = Pattern.compile(
        ".*\\.(mp4|m3u8|mp3|webm|ogg|avi|mkv|ts|flv|mov)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern KALTURA_PATTERN = Pattern.compile(
        ".*(kaltura\\.com|cdnapisec\\.kaltura\\.com).*entry_id=([a-zA-Z0-9_]+).*",
        Pattern.CASE_INSENSITIVE
    );

    private static final String HOME_BASE = "https://kiwiplus.local";
    private OkHttpClient directClient;
    private OkHttpClient proxyClient;

    public class MediaBridge {
        @JavascriptInterface
        public void onMediaFound(String url) {
            if (url != null && !url.isEmpty() && !mediaUrls.contains(url)) {
                mediaUrls.add(url);
                runOnUiThread(() -> updateMediaButton(true));
            }
        }

        @JavascriptInterface
        public void onVideoJsReady(String src) {
            if (src != null && !src.isEmpty()) {
                runOnUiThread(() -> {
                    if (!mediaUrls.contains(src)) {
                        mediaUrls.add(src);
                        updateMediaButton(true);
                    }
                    if (src.contains(".m3u8") && !hlsInjected) {
                        injectHlsIntoVideoJs(src);
                    }
                });
            }
        }

        @JavascriptInterface
        public void addShortcut(String name, String url) {
            runOnUiThread(() -> {
                try {
                    String saved = prefs.getString("shortcuts", null);
                    JSONArray arr;
                    if (saved != null) {
                        arr = new JSONArray(saved);
                    } else {
                        arr = new JSONArray();
                        for (String[] s : DEFAULT_SHORTCUTS) {
                            JSONObject obj = new JSONObject();
                            obj.put("name", s[0]);
                            obj.put("url", s[1]);
                            obj.put("emoji", s[2]);
                            arr.put(obj);
                        }
                    }
                    JSONObject newShortcut = new JSONObject();
                    newShortcut.put("name", name);
                    newShortcut.put("url", url);
                    newShortcut.put("emoji", "🌐");
                    arr.put(newShortcut);
                    prefs.edit().putString("shortcuts", arr.toString()).apply();
                    Toast.makeText(MainActivity.this, "✅ קיצור נוסף!", Toast.LENGTH_SHORT).show();
                    showHomePage();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "שגיאה", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("kiwiplus", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnMedia = findViewById(R.id.btnMedia);
        btnHome = findViewById(R.id.btnHome);
        btnMenu = findViewById(R.id.btnMenu);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashScreen = findViewById(R.id.splashScreen);
        splashTitle = findViewById(R.id.splashTitle);
        splashSubtitle = findViewById(R.id.splashSubtitle);

        directClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

        proxyClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(new okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build();

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(0xFFf0f7ee);
        webView.addJavascriptInterface(new MediaBridge(), "KiwiPlus");

        showSplash();
        setupWebView();
        setupButtons();
        setupUrlBar();
    }

    private void showSplash() {
        splashScreen.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        AnimationSet anim = new AnimationSet(true);
        ScaleAnimation scale = new ScaleAnimation(0.5f, 1f, 0.5f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(800);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(800);
        anim.addAnimation(scale);
        anim.addAnimation(fade);
        splashTitle.startAnimation(anim);
        splashSubtitle.startAnimation(fade);
        new Handler().postDelayed(this::hideSplash, 2500);
    }

    private void hideSplash() {
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(500);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                splashScreen.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                showHomePage();
            }
        });
        splashScreen.startAnimation(fadeOut);
    }

    private String buildShortcutsHtml() {
        StringBuilder sb = new StringBuilder();
        String saved = prefs.getString("shortcuts", null);
        String[][] shortcuts = DEFAULT_SHORTCUTS;
        if (saved != null) {
            try {
                JSONArray arr = new JSONArray(saved);
                shortcuts = new String[arr.length()][3];
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    shortcuts[i][0] = obj.getString("name");
                    shortcuts[i][1] = obj.getString("url");
                    shortcuts[i][2] = obj.getString("emoji");
                }
            } catch (Exception e) { /* use defaults */ }
        }
        for (String[] s : shortcuts) {
            String domain = "";
            try { domain = Uri.parse(s[1]).getHost(); } catch (Exception e) {}
            sb.append("<div class='item' onclick=\"go('").append(s[1]).append("')\">");
            sb.append("<div class='icon'>");
            sb.append("<img src='https://www.google.com/s2/favicons?domain=").append(domain).append("&sz=64' ");
            sb.append("onerror=\"this.style.display='none';this.nextSibling.style.display='flex'\" />");
            sb.append("<span style='display:none'>").append(s[2]).append("</span>");
            sb.append("</div>");
            sb.append("<div class='label'>").append(s[0]).append("</div></div>");
        }
        return sb.toString();
    }

    private void showHomePage() {
        isHomePage = true;
        hlsInjected = false;
        urlBar.setText("");
        urlBar.setHint("חפש או הכנס כתובת");
        String shortcuts = buildShortcutsHtml();
        String html = "<!DOCTYPE html><html dir='rtl'><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>" +
            "* { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }" +
            "body { background:linear-gradient(160deg,#e8f5e0 0%,#f5faf0 60%,#e0f0e8 100%);" +
            "  font-family:sans-serif; color:#2d4a1e; min-height:100vh; padding:16px 16px 100px; }" +
            "h1 { font-size:36px; font-weight:900; color:#3a7d1e; margin-bottom:2px; text-align:center; }" +
            ".subtitle { font-size:12px; color:#7aaa5a; margin-bottom:16px; text-align:center; }" +
            ".search-box { display:flex; align-items:center; background:white;" +
            "  border-radius:24px; box-shadow:0 2px 12px rgba(0,0,0,0.1);" +
            "  padding:4px 8px 4px 16px; margin-bottom:24px; }" +
            ".search-box input { flex:1; border:none; outline:none; font-size:16px;" +
            "  color:#2d4a1e; background:transparent; padding:10px 0; }" +
            ".search-box input::placeholder { color:#aaa; }" +
            ".search-btn { width:40px; height:40px; border-radius:50%; background:#3a7d1e;" +
            "  border:none; cursor:pointer; display:flex; align-items:center; justify-content:center;" +
            "  font-size:18px; flex-shrink:0; }" +
            ".section-title { font-size:17px; font-weight:700; margin:20px 0 12px; color:#2d4a1e;" +
            "  display:flex; justify-content:space-between; align-items:center; }" +
            ".edit-btn { font-size:12px; color:#7aaa5a; cursor:pointer; padding:4px 8px;" +
            "  background:#e8f5e0; border-radius:12px; }" +
            ".grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-bottom:8px; }" +
            ".item { display:flex; flex-direction:column; align-items:center; gap:5px; cursor:pointer; }" +
            ".item .icon { width:54px; height:54px; border-radius:50%; background:white;" +
            "  display:flex; align-items:center; justify-content:center; font-size:22px;" +
            "  box-shadow:0 2px 8px rgba(0,0,0,0.09); overflow:hidden; transition:transform 0.1s; }" +
            ".item .icon img { width:32px; height:32px; object-fit:contain; }" +
            ".item:active .icon { transform:scale(0.88); }" +
            ".item .label { font-size:10px; color:#4a7a2e; font-weight:600; text-align:center; }" +
            ".card { background:white; border-radius:16px; padding:14px;" +
            "  box-shadow:0 2px 10px rgba(0,0,0,0.06); margin-bottom:12px; }" +
            ".row { display:flex; align-items:center; gap:12px; }" +
            ".cicon { width:42px; height:42px; border-radius:12px; background:#e8f5e0;" +
            "  display:flex; align-items:center; justify-content:center; font-size:20px; flex-shrink:0; }" +
            ".ctext h3 { font-size:13px; font-weight:700; color:#2d4a1e; }" +
            ".ctext p { font-size:11px; color:#7aaa5a; margin-top:2px; }" +
            ".badge { display:inline-block; background:#e8f5e0; color:#3a7d1e;" +
            "  padding:2px 8px; border-radius:20px; font-size:10px; font-weight:700; margin-top:4px; }" +
            "</style></head><body>" +
            "<div style='text-align:center; padding-top:24px; margin-bottom:8px;'>" +
            "<h1>KiwiPlus 🥝</h1>" +
            "<p class='subtitle'>browse free · 🔒 חכם · 📡 עוקף חסימות</p>" +
            "</div>" +
            "<div class='search-box'>" +
            "  <input type='text' id='searchInput' placeholder='חפש או הכנס כתובת...' " +
            "    onkeydown='if(event.key==\"Enter\") doSearch()' />" +
            "  <button class='search-btn' onclick='doSearch()'>🔍</button>" +
            "</div>" +
            "<div class='section-title'>" +
            "  <span>קישורים מהירים</span>" +
            "  <span class='edit-btn' onclick='addShortcut()'>+ הוסף</span>" +
            "</div>" +
            "<div class='grid' id='shortcuts'>" + shortcuts + "</div>" +
            "<p class='section-title'>מצב</p>" +
            "<div class='card'><div class='row'><div class='cicon'>🛡️</div><div class='ctext'>" +
            "<h3>פרוקסי חכם</h3><p>ישיר כשאפשר, פרוקסי כשנחסם</p>" +
            "<span class='badge'>🟢 פעיל</span></div></div></div>" +
            "<div class='card'><div class='row'><div class='cicon'>🎬</div><div class='ctext'>" +
            "<h3>זיהוי מדיה אוטומטי</h3><p>Video.js · HLS · Kaltura</p>" +
            "<span class='badge'>🟢 Bridge פעיל</span></div></div></div>" +
            "<script>" +
            "function doSearch() {" +
            "  var q = document.getElementById('searchInput').value.trim();" +
            "  if (!q) return;" +
            "  if (q.startsWith('http://') || q.startsWith('https://')) {" +
            "    window.location.href = q;" +
            "  } else if (q.indexOf('.') !== -1 && q.indexOf(' ') === -1) {" +
            "    window.location.href = 'https://' + q;" +
            "  } else {" +
            "    window.location.href = 'https://duckduckgo.com/?q=' + encodeURIComponent(q);" +
            "  }" +
            "}" +
            "function go(url){ window.location.href=url; }" +
            "function addShortcut(){" +
            "  var name = prompt('שם הקיצור:');" +
            "  if (!name) return;" +
            "  var url = prompt('כתובת האתר:');" +
            "  if (!url) return;" +
            "  if (!url.startsWith('http')) url = 'https://' + url;" +
            "  window.KiwiPlus.addShortcut(name, url);" +
            "}" +
            "setTimeout(function(){ document.getElementById('searchInput').focus(); }, 300);" +
            "</script>" +
            "</body></html>";
        webView.loadDataWithBaseURL(HOME_BASE, html, "text/html", "UTF-8", null);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setUserAgentString(UA_MOBILE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForMedia(url);
                if (url.startsWith(HOME_BASE) || url.startsWith(PROXY_URL)) return null;
                if (!request.getMethod().equals("GET")) return null;
                if (!url.startsWith("http")) return null;

                String host = request.getUrl().getHost();
                if (host != null && blockedHosts.contains(host)) {
                    return fetchViaProxy(url, request);
                }

                try {
                    Request.Builder reqBuilder = new Request.Builder().url(url);
                    for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
                    }
                    Response response = directClient.newCall(reqBuilder.build()).execute();
                    if (response.body() == null) return null;
                    int code = response.code();
                    if (code == 403 || code == 407 || code == 451) {
                        response.close();
                        if (host != null) blockedHosts.add(host);
                        return fetchViaProxy(url, request);
                    }
                    String contentType = response.header("Content-Type", "text/plain");
                    String mimeType = contentType != null && contentType.contains(";")
                        ? contentType.split(";")[0].trim() : contentType;
                    HashMap<String, String> headers = new HashMap<>();
                    for (String name : response.headers().names()) {
                        String val = response.header(name);
                        if (val != null) headers.put(name, val);
                    }
                    String message = response.message();
                    if (message == null || message.isEmpty()) message = "OK";
                    return new WebResourceResponse(mimeType, "UTF-8",
                        response.code(), message, headers, response.body().byteStream());
                } catch (Exception e) {
                    if (host != null) blockedHosts.add(host);
                    return fetchViaProxy(url, request);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (isHomePage || url.startsWith(HOME_BASE)) return;
                mediaUrls.clear();
                hlsInjected = false;
                updateMediaButton(false);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.startsWith(HOME_BASE)) { isHomePage = false; return; }
                if (isHomePage) { isHomePage = false; return; }
                progressBar.setVisibility(View.GONE);
                urlBar.setText(url);
                btnBack.setAlpha(view.canGoBack() ? 1f : 0.4f);
                btnForward.setAlpha(view.canGoForward() ? 1f : 0.4f);
                swipeRefresh.setRefreshing(false);
                injectVideoJsListener(view);
                injectMediaScanner(view);
                injectNetworkObserver(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }
            private View customView;
            private CustomViewCallback customViewCallback;
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                setContentView(customView);
            }
            @Override
            public void onHideCustomView() {
                setContentView(R.layout.activity_main);
                if (customViewCallback != null) { customViewCallback.onCustomViewHidden(); customViewCallback = null; }
                customView = null;
                recreate();
            }
        });
    }

    // תופס שידורים מרחוק דרך PerformanceObserver
    private void injectNetworkObserver(WebView view) {
        String js =
            "(function() {" +
            "  if (window._kiwiNetworkObserver) return;" +
            "  window._kiwiNetworkObserver = true;" +
            // PerformanceObserver - תופס כל resource שנטען
            "  try {" +
            "    var observer = new PerformanceObserver(function(list) {" +
            "      list.getEntries().forEach(function(entry) {" +
            "        var url = entry.name;" +
            "        if (!url || !url.startsWith('http')) return;" +
            "        var isMedia = /\\.(m3u8|mp4|ts|mp3|webm|ogg|flv|mov|avi)(\\?|$)/i.test(url);" +
            "        var isStream = /(manifest|playlist|stream|video|audio|media|chunk|segment)/i.test(url);" +
            "        var isKaltura = /kaltura|entry_id/i.test(url);" +
            "        if (isMedia || isStream || isKaltura) {" +
            "          window.KiwiPlus.onMediaFound(url);" +
            "        }" +
            "      });" +
            "    });" +
            "    observer.observe({ entryTypes: ['resource'] });" +
            "  } catch(e) {}" +
            // XMLHttpRequest override - תופס AJAX
            "  var origOpen = XMLHttpRequest.prototype.open;" +
            "  XMLHttpRequest.prototype.open = function(method, url) {" +
            "    if (url && typeof url === 'string') {" +
            "      var isMedia = /\\.(m3u8|mp4|ts|mp3|webm)(\\?|$)/i.test(url);" +
            "      var isStream = /(manifest|playlist|stream|hls|dash)/i.test(url);" +
            "      if (isMedia || isStream) window.KiwiPlus.onMediaFound(url);" +
            "    }" +
            "    return origOpen.apply(this, arguments);" +
            "  };" +
            // Fetch override - תופס fetch requests
            "  var origFetch = window.fetch;" +
            "  window.fetch = function(input, init) {" +
            "    var url = typeof input === 'string' ? input : (input && input.url);" +
            "    if (url) {" +
            "      var isMedia = /\\.(m3u8|mp4|ts|mp3|webm)(\\?|$)/i.test(url);" +
            "      var isStream = /(manifest|playlist|stream|hls|dash)/i.test(url);" +
            "      if (isMedia || isStream) window.KiwiPlus.onMediaFound(url);" +
            "    }" +
            "    return origFetch.apply(this, arguments);" +
            "  };" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    private WebResourceResponse fetchViaProxy(String url, WebResourceRequest request) {
        try {
            String proxyTarget = PROXY_URL + url;
            Request.Builder reqBuilder = new Request.Builder().url(proxyTarget);
            for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
            }
            Response response = proxyClient.newCall(reqBuilder.build()).execute();
            if (response.body() == null) return null;
            String contentType = response.header("Content-Type", "text/plain");
            String mimeType = contentType != null && contentType.contains(";")
                ? contentType.split(";")[0].trim() : contentType;
            HashMap<String, String> headers = new HashMap<>();
            for (String name : response.headers().names()) {
                String val = response.header(name);
                if (val != null) headers.put(name, val);
            }
            headers.put("Access-Control-Allow-Origin", "*");
            String message = response.message();
            if (message == null || message.isEmpty()) message = "OK";
            return new WebResourceResponse(mimeType, "UTF-8",
                response.code(), message, headers, response.body().byteStream());
        } catch (Exception e) { return null; }
    }

    private void injectVideoJsListener(WebView view) {
        String js =
            "(function() {" +
            "  function checkVideoJs() {" +
            "    if (typeof videojs === 'undefined') return;" +
            "    var players = videojs.getPlayers();" +
            "    for (var id in players) {" +
            "      var p = players[id];" +
            "      if (!p._kiwiListening) {" +
            "        p._kiwiListening = true;" +
            "        p.on('loadstart', function() {" +
            "          try { var src = this.currentSrc(); if (src) window.KiwiPlus.onVideoJsReady(src); } catch(e) {}" +
            "        });" +
            "        p.on('sourceset', function(e) {" +
            "          try { if (e.src) window.KiwiPlus.onVideoJsReady(e.src); } catch(e2) {}" +
            "        });" +
            "        try { var src = p.currentSrc(); if (src) window.KiwiPlus.onVideoJsReady(src); } catch(e) {}" +
            "      }" +
            "    }" +
            "    if (videojs.hooks && !window._kiwiHooked) {" +
            "      window._kiwiHooked = true;" +
            "      videojs.hook('setup', function(p) {" +
            "        p.ready(function() {" +
            "          var src = p.currentSrc();" +
            "          if (src) window.KiwiPlus.onVideoJsReady(src);" +
            "          p.on('loadstart', function() {" +
            "            var s = this.currentSrc(); if (s) window.KiwiPlus.onVideoJsReady(s);" +
            "          });" +
            "        });" +
            "      });" +
            "    }" +
            "  }" +
            "  var count = 0;" +
            "  var interval = setInterval(function() {" +
            "    checkVideoJs(); count++; if (count > 10) clearInterval(interval);" +
            "  }, 1000);" +
            "  checkVideoJs();" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    private void injectHlsIntoVideoJs(String m3u8Url) {
        hlsInjected = true;
        String js =
            "(function() {" +
            "  var url = '" + m3u8Url.replace("'", "\\'") + "';" +
            "  function doInject() {" +
            "    if (!Hls.isSupported()) return;" +
            "    document.querySelectorAll('video').forEach(function(video) {" +
            "      var src = video.src || video.currentSrc || '';" +
            "      if (!src && video.querySelector('source')) src = video.querySelector('source').src || '';" +
            "      if (src.indexOf('.m3u8') !== -1 || url.indexOf('.m3u8') !== -1) {" +
            "        var hls = new Hls({ enableWorker:true, lowLatencyMode:true });" +
            "        hls.loadSource(src || url);" +
            "        hls.attachMedia(video);" +
            "        hls.on(Hls.Events.MANIFEST_PARSED, function() { video.play().catch(function(){}); });" +
            "      }" +
            "    });" +
            "  }" +
            "  if (typeof Hls === 'undefined') {" +
            "    var s = document.createElement('script');" +
            "    s.src = 'https://cdn.jsdelivr.net/npm/hls.js@latest';" +
            "    s.onload = doInject;" +
            "    document.head.appendChild(s);" +
            "  } else { doInject(); }" +
            "})()";
        webView.evaluateJavascript(js, value ->
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "🎬 HLS.js הוזרק!", Toast.LENGTH_SHORT).show())
        );
    }

    private void injectHlsJs() {
        if (hlsInjected) {
            Toast.makeText(this, "✅ HLS.js כבר פעיל", Toast.LENGTH_SHORT).show();
            return;
        }
        for (String u : mediaUrls) {
            if (u.contains(".m3u8")) { injectHlsIntoVideoJs(u); return; }
        }
        injectHlsIntoVideoJs("");
    }

    private void showViewSource() {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE)) {
            Toast.makeText(this, "פתח אתר קודם", Toast.LENGTH_SHORT).show();
            return;
        }
        webView.evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })()",
            value -> runOnUiThread(() -> {
                String source = value
                    .replaceAll("^\"|\"$", "")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'");

                String sourceHtml = "<!DOCTYPE html><html><head>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<style>" +
                    "body { background:#1a1a2e; color:#a0d080; font-family:monospace; font-size:11px; padding:12px; white-space:pre-wrap; word-break:break-all; }" +
                    "</style></head><body>" +
                    source.replace("<", "&lt;").replace(">", "&gt;") +
                    "</body></html>";

                webView.loadDataWithBaseURL("https://kiwiplus.source", sourceHtml, "text/html", "UTF-8", null);
                urlBar.setText("📄 מקור: " + currentUrl);
            })
        );
    }

    private void showMenu() {
        PopupMenu popup = new PopupMenu(this, btnMenu);
        popup.getMenu().add(0, 1, 0, isDesktopMode ? "📱 מצב מובייל" : "🖥️ מצב מחשב");
        popup.getMenu().add(0, 2, 0, "🎬 הפעל נגן HLS");
        popup.getMenu().add(0, 3, 0, "🔄 רענן דף");
        popup.getMenu().add(0, 4, 0, "🏠 דף בית");
        popup.getMenu().add(0, 5, 0, "🗑️ נקה Cache");
        popup.getMenu().add(0, 6, 0, "📋 העתק כתובת");
        popup.getMenu().add(0, 7, 0, "🔍 חפש בדף");
        popup.getMenu().add(0, 8, 0, "➕ הוסף קיצור דרך");
        popup.getMenu().add(0, 9, 0, "📄 הצג מקור דף");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: toggleDesktopMode(); break;
                case 2: injectHlsJs(); break;
                case 3: webView.reload(); break;
                case 4: showHomePage(); break;
                case 5:
                    webView.clearCache(true);
                    Toast.makeText(this, "🗑️ Cache נוקה!", Toast.LENGTH_SHORT).show();
                    break;
                case 6:
                    copyToClipboard(webView.getUrl() != null ? webView.getUrl() : "");
                    Toast.makeText(this, "✅ כתובת הועתקה!", Toast.LENGTH_SHORT).show();
                    break;
                case 7: showSearchInPage(); break;
                case 8: addCurrentPageAsShortcut(); break;
                case 9: showViewSource(); break;
            }
            return true;
        });
        popup.show();
    }

    private void addCurrentPageAsShortcut() {
        String currentUrl = webView.getUrl();
        String currentTitle = webView.getTitle();
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE)) {
            Toast.makeText(this, "פתח אתר קודם", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("➕ הוסף קיצור דרך");
        final EditText input = new EditText(this);
        input.setText(currentTitle != null ? currentTitle : currentUrl);
        builder.setView(input);
        final String finalUrl = currentUrl;
        builder.setPositiveButton("הוסף", (dialog, which) -> {
            try {
                String name = input.getText().toString();
                String saved = prefs.getString("shortcuts", null);
                JSONArray arr;
                if (saved != null) {
                    arr = new JSONArray(saved);
                } else {
                    arr = new JSONArray();
                    for (String[] s : DEFAULT_SHORTCUTS) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", s[0]);
                        obj.put("url", s[1]);
                        obj.put("emoji", s[2]);
                        arr.put(obj);
                    }
                }
                JSONObject newShortcut = new JSONObject();
                newShortcut.put("name", name);
                newShortcut.put("url", finalUrl);
                newShortcut.put("emoji", "🌐");
                arr.put(newShortcut);
                prefs.edit().putString("shortcuts", arr.toString()).apply();
                Toast.makeText(this, "✅ קיצור נוסף!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { /* ignore */ }
        });
        builder.setNegativeButton("ביטול", null);
        builder.show();
    }

    private void showSearchInPage() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔍 חפש בדף");
        final EditText input = new EditText(this);
        input.setHint("מילת חיפוש...");
        builder.setView(input);
        builder.setPositiveButton("חפש", (dialog, which) -> {
            String query = input.getText().toString();
            if (!query.isEmpty()) webView.findAllAsync(query);
        });
        builder.setNegativeButton("סגור", (dialog, which) -> webView.clearMatches());
        builder.show();
    }

    private void toggleDesktopMode() {
        isDesktopMode = !isDesktopMode;
        WebSettings settings = webView.getSettings();
        if (isDesktopMode) {
            settings.setUserAgentString(UA_DESKTOP);
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
            Toast.makeText(this, "🖥️ מצב מחשב פעיל", Toast.LENGTH_SHORT).show();
        } else {
            settings.setUserAgentString(UA_MOBILE);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            Toast.makeText(this, "📱 מצב מובייל", Toast.LENGTH_SHORT).show();
        }
        webView.reload();
    }

    private void injectMediaScanner(WebView view) {
        String js =
            "(function() {" +
            "  document.querySelectorAll('video, audio, source').forEach(function(el) {" +
            "    if (el.src && el.src.startsWith('http')) window.KiwiPlus.onMediaFound(el.src);" +
            "    if (el.currentSrc && el.currentSrc.startsWith('http')) window.KiwiPlus.onMediaFound(el.currentSrc);" +
            "  });" +
            "  var h = document.documentElement.innerHTML;" +
            "  var m3u = h.match(/https?:[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*/g);" +
            "  if (m3u) m3u.forEach(function(u) { window.KiwiPlus.onMediaFound(u); });" +
            "  var km = h.match(/entry_id[^a-zA-Z0-9_]*([a-zA-Z0-9_]{5,})/g);" +
            "  if (km) km.forEach(function(m) {" +
            "    window.KiwiPlus.onMediaFound('kaltura:entry_id=' + m.replace(/entry_id[^a-zA-Z0-9_]*/, ''));" +
            "  });" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    private void checkForMedia(String url) {
        if (MEDIA_PATTERN.matcher(url).matches() || KALTURA_PATTERN.matcher(url).matches()) {
            if (!mediaUrls.contains(url)) {
                mediaUrls.add(url);
                runOnUiThread(() -> updateMediaButton(true));
            }
        }
    }

    private void updateMediaButton(boolean hasMedia) {
        btnMedia.setColorFilter(hasMedia ?
            getResources().getColor(android.R.color.holo_purple, null) :
            getResources().getColor(android.R.color.darker_gray, null));
    }

    private void setupButtons() {
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnForward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnHome.setOnClickListener(v -> showHomePage());
        btnMenu.setOnClickListener(v -> showMenu());
        btnRefresh.setOnClickListener(v -> { if (isHomePage) showHomePage(); else webView.reload(); });
        btnMedia.setOnClickListener(v -> showMediaDialog());
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    private void showMediaDialog() {
        if (mediaUrls.isEmpty()) {
            Toast.makeText(this, "לא נמצאו קישורי מדיה", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = mediaUrls.toArray(new String[0]);
        StringBuilder allUrls = new StringBuilder();
        for (String u : mediaUrls) allUrls.append(u).append("\n");
        String allUrlsStr = allUrls.toString().trim();
        new AlertDialog.Builder(this)
            .setTitle("🎬 קישורי מדיה (" + mediaUrls.size() + ")")
            .setItems(items, (dialog, which) -> {
                String selectedUrl = items[which];
                if (selectedUrl.contains(".m3u8")) {
                    openHlsPlayer(selectedUrl);
                } else {
                    copyToClipboard(selectedUrl);
                    Toast.makeText(this, "✅ הקישור הועתק!", Toast.LENGTH_SHORT).show();
                }
            })
            .setPositiveButton("📋 העתק הכל", (dialog, which) -> {
                copyToClipboard(allUrlsStr);
                Toast.makeText(this, "✅ כל הקישורים הועתקו!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("סגור", null)
            .show();
    }

    private void openHlsPlayer(String m3u8Url) {
        String playerHtml = "<!DOCTYPE html><html><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>*{margin:0;padding:0;box-sizing:border-box;}body{background:#000;display:flex;align-items:center;justify-content:center;height:100vh;}video{width:100%;max-height:100vh;}</style>" +
            "<script src='https://cdn.jsdelivr.net/npm/hls.js@latest'></script>" +
            "</head><body><video id='v' controls autoplay playsinline></video><script>" +
            "var url='" + m3u8Url.replace("'", "\\'") + "';" +
            "var video=document.getElementById('v');" +
            "if(Hls.isSupported()){" +
            "  var hls=new Hls({enableWorker:true,lowLatencyMode:true});" +
            "  hls.loadSource(url);" +
            "  hls.attachMedia(video);" +
            "  hls.on(Hls.Events.MANIFEST_PARSED,function(){video.play().catch(function(){});});" +
            "} else if(video.canPlayType('application/vnd.apple.mpegurl')){" +
            "  video.src=url; video.play();" +
            "}" +
            "</script></body></html>";
        webView.loadDataWithBaseURL("https://kiwiplus.player", playerHtml, "text/html", "UTF-8", null);
        urlBar.setText("🎬 נגן HLS");
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("url", text);
        clipboard.setPrimaryClip(clip);
    }

    private void setupUrlBar() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadUrl(urlBar.getText().toString().trim());
                return true;
            }
            return false;
        });
    }

    private void loadUrl(String input) {
        input = input.trim();
        isHomePage = false;
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(input);
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
