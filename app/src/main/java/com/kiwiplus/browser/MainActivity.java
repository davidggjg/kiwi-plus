package com.kiwiplus.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnRefresh, btnMedia, btnHome, btnDesktop;
    private SwipeRefreshLayout swipeRefresh;
    private View splashScreen;
    private TextView splashTitle, splashSubtitle;
    private List<String> mediaUrls = new ArrayList<>();
    private boolean isHomePage = false;
    private boolean isDesktopMode = false;

    // שרת הפרוקסי שלנו
    private static final String PROXY_URL = "https://kiwiplus-proxy.onrender.com/";

    private static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
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

    private OkHttpClient proxyClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnMedia = findViewById(R.id.btnMedia);
        btnHome = findViewById(R.id.btnHome);
        btnDesktop = findViewById(R.id.btnDesktop);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashScreen = findViewById(R.id.splashScreen);
        splashTitle = findViewById(R.id.splashTitle);
        splashSubtitle = findViewById(R.id.splashSubtitle);

        proxyClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

        webView.setBackgroundColor(0xFFf0f7ee);
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

    private void showHomePage() {
        isHomePage = true;
        urlBar.setText("");
        urlBar.setHint("חפש או הכנס כתובת");
        String html = "<!DOCTYPE html><html dir='rtl'><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>" +
            "* { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }" +
            "body { background:linear-gradient(160deg,#e8f5e0 0%,#f5faf0 60%,#e0f0e8 100%);" +
            "  font-family:sans-serif; color:#2d4a1e; min-height:100vh; padding:24px 16px 100px; }" +
            "h1 { font-size:36px; font-weight:900; color:#3a7d1e; margin-bottom:2px; }" +
            ".subtitle { font-size:12px; color:#7aaa5a; margin-bottom:24px; }" +
            ".section-title { font-size:17px; font-weight:700; margin:20px 0 12px; color:#2d4a1e; }" +
            ".grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-bottom:8px; }" +
            ".item { display:flex; flex-direction:column; align-items:center; gap:5px; cursor:pointer; }" +
            ".item .icon { width:54px; height:54px; border-radius:50%; background:white;" +
            "  display:flex; align-items:center; justify-content:center; font-size:22px;" +
            "  box-shadow:0 2px 8px rgba(0,0,0,0.09); transition:transform 0.1s; }" +
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
            "<h1>KiwiPlus 🥝</h1>" +
            "<p class='subtitle'>browse free · 🔒 פרוקסי מאובטח · 📡 עוקף חסימות</p>" +
            "<p class='section-title'>קישורים מהירים</p>" +
            "<div class='grid'>" +
            "<div class='item' onclick=\"go('https://github.com')\"><div class='icon'>🐙</div><div class='label'>GitHub</div></div>" +
            "<div class='item' onclick=\"go('https://youtube.com')\"><div class='icon'>▶️</div><div class='label'>YouTube</div></div>" +
            "<div class='item' onclick=\"go('https://twitter.com')\"><div class='icon'>🐦</div><div class='label'>Twitter</div></div>" +
            "<div class='item' onclick=\"go('https://reddit.com')\"><div class='icon'>🤖</div><div class='label'>Reddit</div></div>" +
            "<div class='item' onclick=\"go('https://instagram.com')\"><div class='icon'>📸</div><div class='label'>Instagram</div></div>" +
            "<div class='item' onclick=\"go('https://web.whatsapp.com')\"><div class='icon'>💬</div><div class='label'>WhatsApp</div></div>" +
            "<div class='item' onclick=\"go('https://duckduckgo.com')\"><div class='icon'>🔍</div><div class='label'>DuckDuckGo</div></div>" +
            "<div class='item' onclick=\"go('https://telegram.org')\"><div class='icon'>✈️</div><div class='label'>Telegram</div></div>" +
            "</div>" +
            "<p class='section-title'>מצב</p>" +
            "<div class='card'><div class='row'><div class='cicon'>🛡️</div><div class='ctext'>" +
            "<h3>פרוקסי פרטי</h3><p>כל הגלישה דרך השרת שלך</p>" +
            "<span class='badge'>🟢 kiwiplus-proxy.onrender.com</span></div></div></div>" +
            "<div class='card'><div class='row'><div class='cicon'>🎬</div><div class='ctext'>" +
            "<h3>זיהוי מדיה</h3><p>לחץ ▶ לזיהוי ונגן HLS מובנה</p>" +
            "<span class='badge'>HLS · Kaltura · Video.js</span></div></div></div>" +
            "<script>function go(url){ window.location.href=url; }</script>" +
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
        settings.setUserAgentString(UA_MOBILE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForMedia(url);

                if (url.startsWith(HOME_BASE) || url.startsWith(PROXY_URL)) return null;
                if (!request.getMethod().equals("GET")) return null;
                if (!url.startsWith("http")) return null;

                try {
                    String proxyTarget = PROXY_URL + url;
                    Request.Builder reqBuilder = new Request.Builder().url(proxyTarget);
                    for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
                    }
                    reqBuilder.addHeader("X-Forwarded-For", "1.1.1.1");

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

                    return new WebResourceResponse(
                        mimeType, "UTF-8",
                        response.code(), message,
                        headers,
                        response.body().byteStream()
                    );
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (isHomePage || url.startsWith(HOME_BASE)) return;
                mediaUrls.clear();
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
                injectMediaScanner(view);
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

    private void toggleDesktopMode() {
        isDesktopMode = !isDesktopMode;
        WebSettings settings = webView.getSettings();
        if (isDesktopMode) {
            settings.setUserAgentString(UA_DESKTOP);
            btnDesktop.setColorFilter(getResources().getColor(android.R.color.holo_green_light, null));
            Toast.makeText(this, "🖥️ מצב מחשב פעיל", Toast.LENGTH_SHORT).show();
        } else {
            settings.setUserAgentString(UA_MOBILE);
            btnDesktop.setColorFilter(getResources().getColor(android.R.color.darker_gray, null));
            Toast.makeText(this, "📱 מצב מובייל", Toast.LENGTH_SHORT).show();
        }
        webView.reload();
    }

    private void injectMediaScanner(WebView view) {
        String js =
            "(function() {" +
            "  var urls = [];" +
            "  document.querySelectorAll('video, audio, source').forEach(function(el) {" +
            "    if (el.src && el.src.startsWith('http')) urls.push(el.src);" +
            "    if (el.currentSrc && el.currentSrc.startsWith('http')) urls.push(el.currentSrc);" +
            "  });" +
            "  try {" +
            "    if (typeof videojs !== 'undefined') {" +
            "      var players = videojs.getPlayers();" +
            "      for (var id in players) {" +
            "        var p = players[id];" +
            "        try { var s = p.currentSrc(); if(s) urls.push(s); } catch(e){}" +
            "        try { var s2 = p.src(); if(s2 && typeof s2==='string') urls.push(s2); } catch(e){}" +
            "      }" +
            "    }" +
            "  } catch(e) {}" +
            "  document.querySelectorAll('iframe').forEach(function(el) {" +
            "    var src = el.src || '';" +
            "    if (src.indexOf('kaltura') !== -1 || src.indexOf('entry_id') !== -1) urls.push(src);" +
            "  });" +
            "  var h = document.documentElement.innerHTML;" +
            "  var km = h.match(/entry_id[^a-zA-Z0-9_]*([a-zA-Z0-9_]{5,})/g);" +
            "  if (km) km.forEach(function(m) { urls.push('kaltura:entry_id=' + m.replace(/entry_id[^a-zA-Z0-9_]*/, '')); });" +
            "  var m3u = h.match(/https?:[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*/g);" +
            "  if (m3u) m3u.forEach(function(u) { urls.push(u); });" +
            "  return JSON.stringify(urls.filter(function(v,i,a){ return v && a.indexOf(v)===i; }));" +
            "})()";

        view.evaluateJavascript(js, value -> {
            if (value == null || value.equals("null") || value.equals("\"[]\"")) return;
            try {
                String inner = value.replaceAll("^\"|\"$", "").replaceAll("^\\[|\\]$", "");
                if (inner.isEmpty()) return;
                String[] parts = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String part : parts) {
                    String u = part.trim().replaceAll("^\"|\"$", "");
                    if (!u.isEmpty() && !mediaUrls.contains(u)) mediaUrls.add(u);
                }
                if (!mediaUrls.isEmpty()) runOnUiThread(() -> updateMediaButton(true));
            } catch (Exception e) { /* ignore */ }
        });
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
        btnDesktop.setOnClickListener(v -> toggleDesktopMode());
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
            "<style>* {margin:0;padding:0;box-sizing:border-box;} body{background:#000;display:flex;align-items:center;justify-content:center;height:100vh;} video{width:100%;max-height:100vh;}</style>" +
            "<script src='https://cdn.jsdelivr.net/npm/hls.js@latest'></script>" +
            "</head><body>" +
            "<video id='v' controls autoplay playsinline></video>" +
            "<script>" +
            "var url='" + m3u8Url.replace("'", "\\'") + "';" +
            "var video=document.getElementById('v');" +
            "if(Hls.isSupported()){var hls=new Hls();hls.loadSource(url);hls.attachMedia(video);hls.on(Hls.Events.MANIFEST_PARSED,function(){video.play();});}" +
            "else if(video.canPlayType('application/vnd.apple.mpegurl')){video.src=url;video.play();}" +
            "</script></body></html>";
        webView.loadDataWithBaseURL("https://kiwiplus.player", playerHtml, "text/html", "UTF-8", null);
        urlBar.setText("🎬 נגן HLS");
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("media url", text);
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
