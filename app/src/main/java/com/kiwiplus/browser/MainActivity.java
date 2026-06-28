package com.kiwiplus.browser;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
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
import android.webkit.SslErrorHandler;
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
    private boolean isSearchResults = false;
    private boolean splashShown = false;
    private Set<String> blockedHosts = new HashSet<>();
    private SharedPreferences prefs;

    private static final String[][] DEFAULT_SHORTCUTS = {
        {"כאן 11", "https://www.kan.org.il/live/", "📺"},
        {"YouTube", "https://youtube.com", "▶️"},
        {"רשת 13", "https://www.reshet.tv/live/", "📡"},
        {"ערוץ 14", "https://www.now14.co.il/live/", "🔴"},
        {"WhatsApp", "https://web.whatsapp.com", "💬"},
        {"Twitch", "https://twitch.tv", "🟣"},
        {"GitHub", "https://github.com", "🐙"},
        {"Telegram", "https://telegram.org", "✈️"}
    };

    private static final String PROXY_URL = "https://kiwiplus-proxy.onrender.com/";
    private static final String DDG_SEARCH = "https://duckduckgo.com/html/?q=";

    // User Agents - Chrome אמיתי כדי לא להיחסם
    private static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // UA שמתחזה ל-Googlebot לחיפוש
    private static final String UA_BOT =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    private static final Pattern MEDIA_PATTERN = Pattern.compile(
        ".*(\\.(mp4|m3u8|mp3|webm|ogg|avi|mkv|flv|mov))(\\?.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern KALTURA_PATTERN = Pattern.compile(
        ".*(kaltura\\.com|cdnapisec\\.kaltura\\.com).*(entry_id|entryId|playManifest|a\\.m3u8).*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STREAM_PATTERN = Pattern.compile(
        ".*(manifest|playlist|\\.m3u8|\\.mpd|/hls/|/dash/|/live/.*\\.ts)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private static final String HOME_BASE = "https://kiwiplus.local";
    private static final String SEARCH_BASE = "https://kiwiplus.search";
    private OkHttpClient directClient;
    private OkHttpClient proxyClient;

    // ===== MediaBridge =====
    public class MediaBridge {
        @JavascriptInterface
        public void onMediaFound(String url) {
            if (url == null || url.isEmpty()) return;
            // סנן segments קטנים
            if (url.matches(".*/(seg|chunk|segment)[-_]?\\d+\\.ts(\\?.*)?$")) return;
            if (!mediaUrls.contains(url)) {
                mediaUrls.add(url);
                runOnUiThread(() -> updateMediaButton(true));
            }
        }

        @JavascriptInterface
        public void onVideoJsReady(String src) {
            if (src == null || src.isEmpty()) return;
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
                            obj.put("name", s[0]); obj.put("url", s[1]); obj.put("emoji", s[2]);
                            arr.put(obj);
                        }
                    }
                    JSONObject newShortcut = new JSONObject();
                    newShortcut.put("name", name); newShortcut.put("url", url); newShortcut.put("emoji", "🌐");
                    arr.put(newShortcut);
                    prefs.edit().putString("shortcuts", arr.toString()).apply();
                    Toast.makeText(MainActivity.this, "✅ קיצור נוסף!", Toast.LENGTH_SHORT).show();
                    showHomePage();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "שגיאה", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void doSearch(String query) { runOnUiThread(() -> performSearch(query)); }

        @JavascriptInterface
        public void navigate(String url) { runOnUiThread(() -> loadUrl(url)); }

        @JavascriptInterface
        public void onUrlChanged(String newUrl) {
            runOnUiThread(() -> {
                mediaUrls.clear();
                hlsInjected = false;
                updateMediaButton(false);
                urlBar.setText(newUrl);
                new Handler().postDelayed(() -> {
                    injectVideoJsListener(webView);
                    injectMediaScanner(webView);
                    injectEarlyNetworkObserver(webView);
                }, 1500);
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

        // OkHttp עם SSL lenient
        javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
            }
        };
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("SSL");
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0])
                .hostnameVerifier((h, s) -> true);
            directClient = builder.build();
            proxyClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
                .sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0])
                .hostnameVerifier((h, s) -> true)
                .build();
        } catch (Exception e) {
            directClient = new OkHttpClient();
            proxyClient = new OkHttpClient();
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(0xFFf0f7ee);
        webView.addJavascriptInterface(new MediaBridge(), "KiwiPlus");

        // שמור את מצב ה-WebView בין rotations
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            splashScreen.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            splashShown = true;
        } else {
            showSplash();
        }

        setupWebView();
        setupButtons();
        setupUrlBar();
    }

    // ===== חשוב: שמור state בסיבוב מסך =====
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    // ===== מניעת recreate בסיבוב מסך =====
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // לא עושים כלום - WebView ישמר את מצבו
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
        new Handler().postDelayed(this::hideSplash, 2000);
    }

    private void hideSplash() {
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(400);
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
            sb.append("<div class='item' onclick=\"KiwiPlus.navigate('").append(s[1]).append("')\">");
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
        isSearchResults = false;
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
            "  border:none; cursor:pointer; font-size:18px; flex-shrink:0; }" +
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
            "  <input type='text' id='q' placeholder='חפש או הכנס כתובת...' " +
            "    onkeydown='if(event.key===\"Enter\") doSearch()' />" +
            "  <button class='search-btn' onclick='doSearch()'>🔍</button>" +
            "</div>" +
            "<div class='section-title'>" +
            "  <span>קישורים מהירים</span>" +
            "  <span class='edit-btn' onclick='addShortcut()'>+ הוסף</span>" +
            "</div>" +
            "<div class='grid'>" + shortcuts + "</div>" +
            "<p class='section-title'>מצב</p>" +
            "<div class='card'><div class='row'><div class='cicon'>🛡️</div><div class='ctext'>" +
            "<h3>פרוקסי חכם</h3><p>ישיר כשאפשר, פרוקסי כשנחסם</p>" +
            "<span class='badge'>🟢 פעיל</span></div></div></div>" +
            "<div class='card'><div class='row'><div class='cicon'>🎬</div><div class='ctext'>" +
            "<h3>זיהוי שידורים</h3><p>תופס HLS, DASH, Kaltura אוטומטית</p>" +
            "<span class='badge'>🟢 פעיל</span></div></div></div>" +
            "<script>" +
            "function doSearch() {" +
            "  var q = document.getElementById('q').value.trim();" +
            "  if (!q) return;" +
            "  KiwiPlus.doSearch(q);" +
            "}" +
            "function addShortcut(){" +
            "  var name = prompt('שם הקיצור:');" +
            "  if (!name) return;" +
            "  var url = prompt('כתובת האתר:');" +
            "  if (!url) return;" +
            "  if (!url.startsWith('http')) url = 'https://' + url;" +
            "  KiwiPlus.addShortcut(name, url);" +
            "}" +
            "setTimeout(function(){ document.getElementById('q').focus(); }, 300);" +
            "</script></body></html>";
        webView.loadDataWithBaseURL(HOME_BASE, html, "text/html", "UTF-8", null);
    }

    // ===== חיפוש משופר - Google + DDG =====
    private void performSearch(String query) {
        query = query.trim();
        if (query.startsWith("http://") || query.startsWith("https://")) { loadUrl(query); return; }
        if (query.contains(".") && !query.contains(" ") && !query.startsWith(".")) { loadUrl("https://" + query); return; }

        isSearchResults = true;
        isHomePage = false;
        final String finalQuery = query;
        urlBar.setText("🔍 " + query);

        webView.loadDataWithBaseURL(SEARCH_BASE, buildSearchLoadingHtml(query), "text/html", "UTF-8", null);

        new Thread(() -> {
            List<String[]> results = new ArrayList<>();
            try {
                // ניסיון 1: DuckDuckGo Lite עם headers שמתחזים לדפדפן אמיתי
                String encodedQ = Uri.encode(finalQuery);
                Request req = new Request.Builder()
                    .url("https://lite.duckduckgo.com/lite/?q=" + encodedQ + "&kl=il-he")
                    .header("User-Agent", UA_MOBILE)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
                    .header("Referer", "https://duckduckgo.com/")
                    .build();
                Response res = directClient.newCall(req).execute();
                String body = res.body() != null ? res.body().string() : "";
                results = parseDDGLite(body);

                // ניסיון 2: DDG JSON API אם Lite נכשל
                if (results.isEmpty()) {
                    Request req2 = new Request.Builder()
                        .url("https://api.duckduckgo.com/?q=" + encodedQ + "&format=json&no_redirect=1&no_html=1&skip_disambig=1&kl=il-he")
                        .header("User-Agent", UA_MOBILE)
                        .build();
                    Response res2 = directClient.newCall(req2).execute();
                    String body2 = res2.body() != null ? res2.body().string() : "{}";
                    results = parseDDGJson(body2, finalQuery);
                }

                // ניסיון 3: Google fallback
                if (results.isEmpty()) {
                    Request req3 = new Request.Builder()
                        .url("https://www.google.com/search?q=" + encodedQ + "&hl=he&num=10")
                        .header("User-Agent", UA_BOT)
                        .build();
                    Response res3 = directClient.newCall(req3).execute();
                    String body3 = res3.body() != null ? res3.body().string() : "";
                    results = parseGoogleResults(body3);
                }

            } catch (Exception e) { /* fallback below */ }

            final List<String[]> finalResults = results;
            runOnUiThread(() -> {
                if (finalResults.isEmpty()) {
                    // fallback: פתח DuckDuckGo ישירות
                    isSearchResults = false;
                    webView.loadUrl("https://duckduckgo.com/?q=" + Uri.encode(finalQuery) + "&kl=il-he");
                } else {
                    webView.loadDataWithBaseURL(SEARCH_BASE,
                        buildSearchResultsHtml(finalQuery, finalResults),
                        "text/html", "UTF-8", null);
                }
            });
        }).start();
    }

    // ===== פרסור Google תוצאות =====
    private List<String[]> parseGoogleResults(String html) {
        List<String[]> results = new ArrayList<>();
        try {
            // חלץ תוצאות מ-Google HTML
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<h3[^>]*>([^<]+)</h3>[\\s\\S]{0,300}?href=\"(https?://(?!(?:www\\.)?google\\.)[^\"]+)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher m = p.matcher(html);
            Set<String> seen = new HashSet<>();
            while (m.find() && results.size() < 10) {
                String title = m.group(1).replaceAll("<[^>]+>", "").trim();
                String url = m.group(2).trim();
                if (!url.isEmpty() && !title.isEmpty() && !seen.contains(url)) {
                    seen.add(url);
                    results.add(new String[]{title, url, ""});
                }
            }
        } catch (Exception e) {}
        return results;
    }

    private List<String[]> parseDDGJson(String json, String query) {
        List<String[]> results = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            String abstractText = obj.optString("AbstractText", "");
            String abstractUrl = obj.optString("AbstractURL", "");
            String abstractSource = obj.optString("AbstractSource", "");
            if (!abstractText.isEmpty() && !abstractUrl.isEmpty())
                results.add(new String[]{abstractSource.isEmpty() ? query : abstractSource, abstractUrl, abstractText});

            JSONArray topics = obj.optJSONArray("RelatedTopics");
            if (topics != null) {
                for (int i = 0; i < topics.length() && results.size() < 8; i++) {
                    try {
                        JSONObject topic = topics.getJSONObject(i);
                        String url = topic.optString("FirstURL", "");
                        String text = topic.optString("Text", "");
                        if (!url.isEmpty() && !text.isEmpty())
                            results.add(new String[]{text.length() > 80 ? text.substring(0, 80) + "..." : text, url, text});
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {}
        return results;
    }

    private List<String[]> parseDDGLite(String html) {
        List<String[]> results = new ArrayList<>();
        try {
            // DDG Lite - חלץ תוצאות עם title + URL + snippet
            java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile(
                "<a[^>]+class=\"result-link\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Pattern snippetPat = java.util.regex.Pattern.compile(
                "<td[^>]+class=\"result-snippet\"[^>]*>([^<]{10,300})</td>",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher tm = titlePat.matcher(html);
            java.util.regex.Matcher sm = snippetPat.matcher(html);
            List<String> snippets = new ArrayList<>();
            while (sm.find()) snippets.add(sm.group(1).trim().replaceAll("<[^>]+>", ""));
            Set<String> seen = new HashSet<>();
            int idx = 0;
            while (tm.find() && results.size() < 10) {
                String url = tm.group(1).trim();
                String title = tm.group(2).trim().replaceAll("<[^>]+>", "");
                if (url.startsWith("//")) url = "https:" + url;
                // ניקוי redirect URLs של DDG
                if (url.contains("duckduckgo.com/l/?uddg=")) {
                    try {
                        String encoded = url.replaceAll(".*uddg=([^&]+).*", "$1");
                        url = java.net.URLDecoder.decode(encoded, "UTF-8");
                    } catch (Exception e2) {}
                }
                if (!url.startsWith("http") || url.contains("duckduckgo.com")) { idx++; continue; }
                if (!seen.contains(url) && !title.isEmpty()) {
                    seen.add(url);
                    String snippet = idx < snippets.size() ? snippets.get(idx) : "";
                    results.add(new String[]{title, url, snippet});
                }
                idx++;
            }

            // fallback parsing אם הסגנון שונה
            if (results.isEmpty()) {
                java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
                    "<a[^>]+href=\"(https?://(?!.*duckduckgo)[^\"]{10,}?)\"[^>]*>([^<]{5,120})</a>",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher m2 = p2.matcher(html);
                while (m2.find() && results.size() < 10) {
                    String url = m2.group(1).trim();
                    String title = m2.group(2).trim().replaceAll("<[^>]+>", "");
                    if (!seen.contains(url) && !title.isEmpty() && title.length() > 4) {
                        seen.add(url);
                        results.add(new String[]{title, url, ""});
                    }
                }
            }
        } catch (Exception e) {}
        return results;
    }

    private String buildSearchLoadingHtml(String query) {
        return "<!DOCTYPE html><html dir='rtl'><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>* { margin:0; padding:0; box-sizing:border-box; }" +
            "body { background:#f5faf0; font-family:sans-serif; padding:16px; }" +
            ".header { display:flex; align-items:center; gap:12px; margin-bottom:20px; }" +
            ".logo { font-size:24px; font-weight:900; color:#3a7d1e; }" +
            ".loading { display:flex; flex-direction:column; align-items:center; margin-top:60px; gap:16px; }" +
            ".dots { display:flex; gap:8px; }" +
            ".dot { width:10px; height:10px; border-radius:50%; background:#3a7d1e;" +
            "  animation:pulse 1.4s infinite ease-in-out; }" +
            ".dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}" +
            "@keyframes pulse{0%,80%,100%{transform:scale(.6);opacity:.4}40%{transform:scale(1);opacity:1}}" +
            "</style></head><body>" +
            "<div class='header'><span class='logo'>KiwiPlus 🥝</span>" +
            "<span style='color:#666;font-size:14px;'>מחפש: " + query + "</span></div>" +
            "<div class='loading'><div class='dots'>" +
            "<div class='dot'></div><div class='dot'></div><div class='dot'></div>" +
            "</div><p style='color:#666;font-size:14px;'>מחפש...</p></div>" +
            "</body></html>";
    }

    private String buildSearchResultsHtml(String query, List<String[]> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html dir='rtl'><head>")
          .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
          .append("<style>")
          .append("* { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }")
          .append("body { background:#f5faf0; font-family:sans-serif; padding:12px 12px 80px; }")
          .append(".header { display:flex; align-items:center; gap:8px; margin-bottom:14px; padding-bottom:10px; border-bottom:2px solid #e0f0e0; }")
          .append(".logo { font-size:22px; font-weight:900; color:#3a7d1e; }")
          .append(".search-row { flex:1; display:flex; background:white; border-radius:20px; padding:6px 12px; box-shadow:0 1px 6px rgba(0,0,0,0.1); }")
          .append(".search-row input { flex:1; border:none; outline:none; font-size:14px; color:#333; background:transparent; }")
          .append(".search-row button { background:none; border:none; cursor:pointer; font-size:16px; }")
          .append(".result { background:white; border-radius:12px; padding:12px; margin-bottom:8px; box-shadow:0 1px 6px rgba(0,0,0,0.06); cursor:pointer; active:background:#f0f9f0; }")
          .append(".result:active { background:#f0f9f0; }")
          .append(".result-title { font-size:15px; font-weight:600; color:#1a0dab; margin-bottom:3px; }")
          .append(".result-url { font-size:11px; color:#3a7d1e; margin-bottom:5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }")
          .append(".result-snippet { font-size:12px; color:#555; line-height:1.4; }")
          .append(".count { font-size:11px; color:#888; margin-bottom:10px; }")
          .append("</style></head><body>")
          .append("<div class='header'>")
          .append("<span class='logo'>🥝</span>")
          .append("<div class='search-row'>")
          .append("<input type='text' id='q' value='").append(query.replace("'", "\\'")).append("' onkeydown='if(event.key===\"Enter\") doSearch()' />")
          .append("<button onclick='doSearch()'>🔍</button>")
          .append("</div></div>");

        sb.append("<p class='count'>").append(results.size()).append(" תוצאות</p>");
        for (String[] r : results) {
            String title = r[0];
            String url = r[1];
            String snippet = r.length > 2 ? r[2] : "";
            String displayUrl = url.length() > 45 ? url.substring(0, 45) + "..." : url;
            sb.append("<div class='result' onclick=\"KiwiPlus.navigate('")
              .append(url.replace("'", "\\'")).append("')\">")
              .append("<div class='result-title'>").append(title).append("</div>")
              .append("<div class='result-url'>").append(displayUrl).append("</div>");
            if (!snippet.isEmpty())
                sb.append("<div class='result-snippet'>").append(snippet).append("</div>");
            sb.append("</div>");
        }

        sb.append("<script>")
          .append("function doSearch() {")
          .append("  var q = document.getElementById('q').value.trim();")
          .append("  if (q) KiwiPlus.doSearch(q);")
          .append("}")
          .append("</script></body></html>");
        return sb.toString();
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

            // ===== טיפול ב-SSL errors - מאפשר לאתרים ישראלים עם הצפנה מיוחדת =====
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed(); // תמיד המשך - מטפל בכל SSL
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForMedia(url);

                if (url.startsWith(HOME_BASE) || url.startsWith(SEARCH_BASE) || url.startsWith(PROXY_URL)) return null;
                if (!request.getMethod().equals("GET")) return null;
                if (!url.startsWith("http")) return null;

                String host = request.getUrl().getHost();

                // אם כבר ידוע שהוסט חסום - נסה פרוקסי מהיר
                if (host != null && blockedHosts.contains(host)) {
                    return fetchViaProxyFast(url, request);
                }

                try {
                    Request.Builder reqBuilder = new Request.Builder().url(url);
                    for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                        try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
                    }
                    reqBuilder.header("User-Agent", isDesktopMode ? UA_DESKTOP : UA_MOBILE);

                    Response response = directClient.newCall(reqBuilder.build()).execute();
                    if (response.body() == null) { response.close(); return null; }

                    int code = response.code();
                    if (code == 403 || code == 407 || code == 451 || code == 429) {
                        response.close();
                        if (host != null) blockedHosts.add(host);
                        return fetchViaProxyFast(url, request);
                    }

                    String contentType = response.header("Content-Type", "application/octet-stream");
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

                } catch (Exception e) {
                    if (host != null) blockedHosts.add(host);
                    return fetchViaProxyFast(url, request);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (isHomePage || isSearchResults || url.startsWith(HOME_BASE) || url.startsWith(SEARCH_BASE)) return;
                mediaUrls.clear();
                hlsInjected = false;
                blockedHosts.clear(); // נקה חסימות בין דפים
                updateMediaButton(false);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
                injectEarlyNetworkObserver(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.startsWith(HOME_BASE) || url.startsWith(SEARCH_BASE)) {
                    isHomePage = url.startsWith(HOME_BASE);
                    return;
                }
                if (isHomePage) { isHomePage = false; return; }
                progressBar.setVisibility(View.GONE);
                urlBar.setText(url);
                btnBack.setAlpha(view.canGoBack() ? 1f : 0.4f);
                btnForward.setAlpha(view.canGoForward() ? 1f : 0.4f);
                swipeRefresh.setRefreshing(false);
                injectVideoJsListener(view);
                injectMediaScanner(view);
                injectSpaNavigationObserver(view);
                // סרוק שוב אחרי 3 שניות - לשידורים שנטענים לאט
                new Handler().postDelayed(() -> {
                    if (webView != null) injectMediaScanner(webView);
                }, 3000);
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
                // אל תעשה recreate! במקום זה - אפס views
                setupWebViewReferences();
            }
        });
    }

    // אפס references אחרי fullscreen בלי recreate
    private void setupWebViewReferences() {
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
        setupButtons();
        setupUrlBar();
    }

    private void injectSpaNavigationObserver(WebView view) {
        String js =
            "(function() {" +
            "  if (window._kiwiSpaObserver) return;" +
            "  window._kiwiSpaObserver = true;" +
            "  var lastUrl = window.location.href;" +
            "  var origPushState = history.pushState;" +
            "  history.pushState = function() {" +
            "    origPushState.apply(this, arguments);" +
            "    var newUrl = window.location.href;" +
            "    if (newUrl !== lastUrl) { lastUrl = newUrl; window.KiwiPlus.onUrlChanged(newUrl); }" +
            "  };" +
            "  var origReplaceState = history.replaceState;" +
            "  history.replaceState = function() {" +
            "    origReplaceState.apply(this, arguments);" +
            "    var newUrl = window.location.href;" +
            "    if (newUrl !== lastUrl) { lastUrl = newUrl; window.KiwiPlus.onUrlChanged(newUrl); }" +
            "  };" +
            "  window.addEventListener('popstate', function() {" +
            "    var newUrl = window.location.href;" +
            "    if (newUrl !== lastUrl) { lastUrl = newUrl; window.KiwiPlus.onUrlChanged(newUrl); }" +
            "  });" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    private void injectEarlyNetworkObserver(WebView view) {
        String js =
            "(function() {" +
            "  if (window._kiwiEarlyObserver) return;" +
            "  window._kiwiEarlyObserver = true;" +
            "  var origOpen = XMLHttpRequest.prototype.open;" +
            "  XMLHttpRequest.prototype.open = function(method, url) {" +
            "    if (url && typeof url === 'string') {" +
            "      if (/\\.(m3u8|mp4|ts|mp3|webm|mpd)(\\?|$)/i.test(url) ||" +
            "          /(manifest|playlist|hls|dash|live\\/)/i.test(url) ||" +
            "          /kaltura|entry_id/i.test(url)) {" +
            "        try { window.KiwiPlus.onMediaFound(url); } catch(e) {}" +
            "      }" +
            "    }" +
            "    return origOpen.apply(this, arguments);" +
            "  };" +
            "  var origFetch = window.fetch;" +
            "  window.fetch = function(input, init) {" +
            "    var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');" +
            "    if (url) {" +
            "      if (/\\.(m3u8|mp4|ts|mp3|webm|mpd)(\\?|$)/i.test(url) ||" +
            "          /(manifest|playlist|hls|dash|live\\/)/i.test(url) ||" +
            "          /kaltura|entry_id/i.test(url)) {" +
            "        try { window.KiwiPlus.onMediaFound(url); } catch(e) {}" +
            "      }" +
            "    }" +
            "    return origFetch.apply(this, arguments);" +
            "  };" +
            // PerformanceObserver - תופס resources
            "  try {" +
            "    var observer = new PerformanceObserver(function(list) {" +
            "      list.getEntries().forEach(function(entry) {" +
            "        var url = entry.name;" +
            "        if (!url || !url.startsWith('http')) return;" +
            "        if (/\\.(m3u8|mp4|mpd)(\\?|$)/i.test(url) ||" +
            "            /(manifest|playlist|\\/live\\/)/i.test(url)) {" +
            "          try { window.KiwiPlus.onMediaFound(url); } catch(e) {}" +
            "        }" +
            "      });" +
            "    });" +
            "    observer.observe({ entryTypes: ['resource'] });" +
            "  } catch(e) {}" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    // פרוקסי עם timeout מהיר - 5 שניות בלבד
    private WebResourceResponse fetchViaProxyFast(String url, WebResourceRequest request) {
        try {
            OkHttpClient fastProxy = proxyClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
            String proxyTarget = PROXY_URL + url;
            Request.Builder reqBuilder = new Request.Builder().url(proxyTarget);
            for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
            }
            reqBuilder.header("User-Agent", isDesktopMode ? UA_DESKTOP : UA_MOBILE);
            Response response = fastProxy.newCall(reqBuilder.build()).execute();
            if (response.body() == null) { response.close(); return null; }
            String contentType = response.header("Content-Type", "application/octet-stream");
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
        } catch (Exception e) { return null; } // timeout - תחזיר null ו-WebView ינסה לבד
    }

    private WebResourceResponse fetchViaProxy(String url, WebResourceRequest request) {
        try {
            String proxyTarget = PROXY_URL + url;
            Request.Builder reqBuilder = new Request.Builder().url(proxyTarget);
            for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                try { reqBuilder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) {}
            }
            reqBuilder.header("User-Agent", isDesktopMode ? UA_DESKTOP : UA_MOBILE);

            Response response = proxyClient.newCall(reqBuilder.build()).execute();
            if (response.body() == null) { response.close(); return null; }

            String contentType = response.header("Content-Type", "application/octet-stream");
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
            "          var src = p.currentSrc(); if (src) window.KiwiPlus.onVideoJsReady(src);" +
            "          p.on('loadstart', function() { var s = this.currentSrc(); if (s) window.KiwiPlus.onVideoJsReady(s); });" +
            "        });" +
            "      });" +
            "    }" +
            "  }" +
            "  var count = 0;" +
            "  var interval = setInterval(function() {" +
            "    checkVideoJs(); count++; if (count > 15) clearInterval(interval);" +
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
            "    document.querySelectorAll('video').forEach(function(video) {" +
            "      var src = video.src || video.currentSrc || '';" +
            "      if (!src && video.querySelector('source')) src = video.querySelector('source').src || '';" +
            "      var targetUrl = (src && src.indexOf('.m3u8') !== -1) ? src : url;" +
            "      if (!targetUrl) return;" +
            "      if (Hls.isSupported()) {" +
            "        var hls = new Hls({ enableWorker:true, lowLatencyMode:true });" +
            "        hls.loadSource(targetUrl);" +
            "        hls.attachMedia(video);" +
            "        hls.on(Hls.Events.MANIFEST_PARSED, function() { video.play().catch(function(){}); });" +
            "      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {" +
            "        video.src = targetUrl; video.play();" +
            "      }" +
            "    });" +
            "  }" +
            "  if (typeof Hls === 'undefined') {" +
            "    var s = document.createElement('script');" +
            "    s.src = 'https://cdn.jsdelivr.net/npm/hls.js@latest';" +
            "    s.onload = doInject; document.head.appendChild(s);" +
            "  } else { doInject(); }" +
            "})()";
        webView.evaluateJavascript(js, value ->
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "🎬 HLS.js הוזרק!", Toast.LENGTH_SHORT).show())
        );
    }

    private void injectHlsJs() {
        if (hlsInjected) { Toast.makeText(this, "✅ HLS.js כבר פעיל", Toast.LENGTH_SHORT).show(); return; }
        for (String u : mediaUrls) {
            if (u.contains(".m3u8")) { injectHlsIntoVideoJs(u); return; }
        }
        injectHlsIntoVideoJs("");
    }

    private void injectMediaScanner(WebView view) {
        String js =
            "(function() {" +
            "  var urls = [];" +
            "  document.querySelectorAll('video, audio, source').forEach(function(el) {" +
            "    if (el.src && el.src.startsWith('http')) urls.push(el.src);" +
            "    if (el.currentSrc && el.currentSrc.startsWith('http')) urls.push(el.currentSrc);" +
            "  });" +
            "  var h = document.documentElement.innerHTML;" +
            "  var m3u = h.match(/https?:[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*/g);" +
            "  if (m3u) m3u.forEach(function(u) { urls.push(u); });" +
            // Kaltura
            "  var partnerId = null, entryId = null;" +
            "  var pm = h.match(/['\"]?partner_?[Ii]d['\"]?\\s*[:=,]\\s*['\"]?(\\d{4,})/i) ||" +
            "           h.match(/\\/p\\/(\\d{4,})\\//i);" +
            "  if (pm) partnerId = pm[1];" +
            "  var em = h.match(/entry_?[Ii]d[^a-zA-Z0-9_]*([01][_][a-zA-Z0-9]+)/i);" +
            "  if (em) entryId = em[1];" +
            "  if (partnerId && entryId) {" +
            "    var kUrl = 'https://cdnapisec.kaltura.com/p/' + partnerId +" +
            "      '/sp/' + partnerId + '00/playManifest/entryId/' + entryId +" +
            "      '/format/applehttp/protocol/https/a.m3u8';" +
            "    urls.push(kUrl);" +
            "    try { window.KiwiPlus.onMediaFound(kUrl); } catch(e) {}" +
            "  }" +
            "  document.querySelectorAll('iframe').forEach(function(el) {" +
            "    var src = el.src || '';" +
            "    if (src.indexOf('kaltura') !== -1 || src.indexOf('entry_id') !== -1) {" +
            "      var ipm = src.match(/\\/p\\/(\\d{4,})/);" +
            "      var iem = src.match(/entry_?[Ii]d[=\\/]([01][_][a-zA-Z0-9]+)/);" +
            "      if (ipm && iem) {" +
            "        var ikUrl = 'https://cdnapisec.kaltura.com/p/' + ipm[1] +" +
            "          '/sp/' + ipm[1] + '00/playManifest/entryId/' + iem[1] +" +
            "          '/format/applehttp/protocol/https/a.m3u8';" +
            "        try { window.KiwiPlus.onMediaFound(ikUrl); } catch(e) {}" +
            "      }" +
            "    }" +
            "  });" +
            "  urls.filter(function(v,i,a){ return v && a.indexOf(v)===i; })" +
            "    .forEach(function(u) { try { window.KiwiPlus.onMediaFound(u); } catch(e) {} });" +
            "})()";
        view.evaluateJavascript(js, null);
    }

    private void checkForMedia(String url) {
        if (MEDIA_PATTERN.matcher(url).matches() || KALTURA_PATTERN.matcher(url).matches() ||
            STREAM_PATTERN.matcher(url).matches()) {
            if (!mediaUrls.contains(url)) {
                mediaUrls.add(url);
                runOnUiThread(() -> updateMediaButton(true));
            }
        }
    }

    private void updateMediaButton(boolean hasMedia) {
        if (btnMedia != null)
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
            Toast.makeText(this, "לא נמצאו קישורי מדיה - נסה ללחוץ Play", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> filtered = new ArrayList<>();
        for (String u : mediaUrls) {
            if (u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mp3") ||
                u.contains("playManifest") || u.contains("mainManifest") ||
                u.contains(".webm") || u.contains(".mpd")) {
                filtered.add(u);
            }
        }
        List<String> toShow = filtered.isEmpty() ? mediaUrls : filtered;
        String[] items = toShow.toArray(new String[0]);
        StringBuilder allUrls = new StringBuilder();
        for (String u : toShow) allUrls.append(u).append("\n");

        new AlertDialog.Builder(this)
            .setTitle("🎬 קישורי מדיה (" + toShow.size() + ")")
            .setItems(items, (dialog, which) -> {
                String selectedUrl = items[which];
                if (selectedUrl.contains(".m3u8") || selectedUrl.contains("playManifest") ||
                    selectedUrl.contains("mainManifest") || selectedUrl.contains(".mpd")) {
                    openHlsPlayer(selectedUrl);
                } else {
                    copyToClipboard(selectedUrl);
                    Toast.makeText(this, "✅ הקישור הועתק!", Toast.LENGTH_SHORT).show();
                }
            })
            .setPositiveButton("📋 העתק הכל", (dialog, which) -> {
                copyToClipboard(allUrls.toString().trim());
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
            "  hls.loadSource(url);hls.attachMedia(video);" +
            "  hls.on(Hls.Events.MANIFEST_PARSED,function(){video.play().catch(function(){});});" +
            "} else if(video.canPlayType('application/vnd.apple.mpegurl')){" +
            "  video.src=url; video.play();" +
            "}" +
            "</script></body></html>";
        webView.loadDataWithBaseURL("https://kiwiplus.player", playerHtml, "text/html", "UTF-8", null);
        urlBar.setText("🎬 נגן HLS");
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
        popup.getMenu().add(0, 10, 0, "🎬 נגן Kaltura מ-Embed");
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
                case 10: showKalturaEmbedDialog(); break;
            }
            return true;
        });
        popup.show();
    }

    private void toggleDesktopMode() {
        isDesktopMode = !isDesktopMode;
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(isDesktopMode ? UA_DESKTOP : UA_MOBILE);
        settings.setUseWideViewPort(!isDesktopMode);
        settings.setLoadWithOverviewMode(!isDesktopMode);
        Toast.makeText(this, isDesktopMode ? "🖥️ מצב מחשב פעיל" : "📱 מצב מובייל", Toast.LENGTH_SHORT).show();
        webView.reload();
    }

    private void showViewSource() {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE) || currentUrl.startsWith(SEARCH_BASE)) {
            Toast.makeText(this, "פתח אתר קודם", Toast.LENGTH_SHORT).show(); return;
        }
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()", value -> runOnUiThread(() -> {
            String source = value.replaceAll("^\"|\"$", "").replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
            String sourceHtml = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<style>body { background:#1a1a2e; color:#a0d080; font-family:monospace; font-size:11px; padding:12px; white-space:pre-wrap; word-break:break-all; }</style>" +
                "</head><body>" + source.replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
            webView.loadDataWithBaseURL("https://kiwiplus.source", sourceHtml, "text/html", "UTF-8", null);
            urlBar.setText("📄 מקור דף");
        }));
    }

    private void addCurrentPageAsShortcut() {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE) || currentUrl.startsWith(SEARCH_BASE)) {
            Toast.makeText(this, "פתח אתר קודם", Toast.LENGTH_SHORT).show(); return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("➕ הוסף קיצור דרך");
        final EditText input = new EditText(this);
        input.setText(webView.getTitle() != null ? webView.getTitle() : currentUrl);
        builder.setView(input);
        final String finalUrl = currentUrl;
        builder.setPositiveButton("הוסף", (dialog, which) -> {
            try {
                String name = input.getText().toString();
                String saved = prefs.getString("shortcuts", null);
                JSONArray arr;
                if (saved != null) { arr = new JSONArray(saved); }
                else {
                    arr = new JSONArray();
                    for (String[] s : DEFAULT_SHORTCUTS) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", s[0]); obj.put("url", s[1]); obj.put("emoji", s[2]);
                        arr.put(obj);
                    }
                }
                JSONObject newShortcut = new JSONObject();
                newShortcut.put("name", name); newShortcut.put("url", finalUrl); newShortcut.put("emoji", "🌐");
                arr.put(newShortcut);
                prefs.edit().putString("shortcuts", arr.toString()).apply();
                Toast.makeText(this, "✅ קיצור נוסף!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
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

    private void showKalturaEmbedDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🎬 נגן Kaltura");
        final EditText input = new EditText(this);
        input.setHint("הדבק כאן את ה-iframe src של Kaltura...");
        input.setPadding(32, 24, 32, 24);
        builder.setView(input);
        builder.setPositiveButton("▶ נגן", (dialog, which) -> {
            String embedUrl = input.getText().toString().trim();
            if (embedUrl.contains("iframe")) {
                java.util.regex.Matcher srcM = java.util.regex.Pattern.compile("src=\"([^\"]+)\"").matcher(embedUrl);
                if (srcM.find()) embedUrl = srcM.group(1);
            }
            try {
                java.util.regex.Matcher pm = java.util.regex.Pattern.compile("/p/(\\d+)/").matcher(embedUrl);
                java.util.regex.Matcher em = java.util.regex.Pattern.compile("(?:entry_id|entryId)[=\\/]([a-zA-Z0-9_]+)").matcher(embedUrl);
                if (pm.find() && em.find()) {
                    String partnerId = pm.group(1), entryId = em.group(1);
                    String m3u8 = "https://cdnapisec.kaltura.com/p/" + partnerId + "/sp/" + partnerId + "00/playManifest/entryId/" + entryId + "/format/applehttp/protocol/https/a.m3u8";
                    openHlsPlayer(m3u8);
                } else {
                    Toast.makeText(this, "❌ לא נמצא entry_id", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) { Toast.makeText(this, "שגיאה: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });
        builder.setNegativeButton("ביטול", null);
        builder.show();
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", text));
    }

    private void setupUrlBar() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch(urlBar.getText().toString().trim());
                return true;
            }
            return false;
        });
    }

    private void loadUrl(String input) {
        input = input.trim();
        isHomePage = false;
        isSearchResults = false;
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) { url = input; }
        else if (input.contains(".") && !input.contains(" ") && !input.startsWith(".")) { url = "https://" + input; }
        else { performSearch(input); return; }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
