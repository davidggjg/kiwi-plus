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
    private boolean isSearchResults = false;
    private Set<String> blockedHosts = new HashSet<>();
    private SharedPreferences prefs;

    private static final String[][] DEFAULT_SHORTCUTS = {
        {"GitHub", "https://github.com", "🐙"},
        {"YouTube", "https://youtube.com", "▶️"},
        {"Twitter", "https://twitter.com", "🐦"},
        {"Reddit", "https://reddit.com", "🤖"},
        {"Instagram", "https://instagram.com", "📸"},
        {"WhatsApp", "https://web.whatsapp.com", "💬"},
        {"כאן 11", "https://www.kan.org.il", "📺"},
        {"Telegram", "https://telegram.org", "✈️"}
    };

    private static final String PROXY_URL = "https://kiwiplus-proxy.onrender.com/";
    private static final String DDG_API = "https://api.duckduckgo.com/?format=json&no_redirect=1&no_html=1&q=";
    private static final String DDG_SEARCH = "https://duckduckgo.com/html/?q=";

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

    private static final Pattern STREAM_PATTERN = Pattern.compile(
        ".*(manifest|playlist|\\.m3u8|\\.mpd|stream|segment|\\.ts)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private static final String HOME_BASE = "https://kiwiplus.local";
    private static final String SEARCH_BASE = "https://kiwiplus.search";
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

        @JavascriptInterface
        public void doSearch(String query) {
            runOnUiThread(() -> performSearch(query));
        }

        @JavascriptInterface
        public void navigate(String url) {
            runOnUiThread(() -> loadUrl(url));
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
            "  <input type='text' id='q' placeholder='חפש או הכנס כתובת...' " +
            "    onkeydown='if(event.key==\"Enter\") doSearch()' />" +
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
            "<h3>זיהוי שידורים</h3><p>תופס קישורי מדיה מוסתרים אוטומטית</p>" +
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

    private void performSearch(String query) {
        query = query.trim();
        if (query.startsWith("http://") || query.startsWith("https://")) {
            loadUrl(query);
            return;
        }
        if (query.contains(".") && !query.contains(" ")) {
            loadUrl("https://" + query);
            return;
        }

        // חיפוש - הצג תוצאות בעיצוב שלנו
        isSearchResults = true;
        isHomePage = false;
        final String finalQuery = query;
        urlBar.setText("🔍 " + query);

        // הצג loading
        String loadingHtml = buildSearchLoadingHtml(query);
        webView.loadDataWithBaseURL(SEARCH_BASE, loadingHtml, "text/html", "UTF-8", null);

        // חפש ב-background
        new Thread(() -> {
            try {
                String encodedQuery = Uri.encode(finalQuery);
                // שלוף תוצאות מ-DuckDuckGo HTML
                Request request = new Request.Builder()
                    .url(DDG_SEARCH + encodedQuery)
                    .header("User-Agent", UA_MOBILE)
                    .header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
                    .build();

                Response response = directClient.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "";

                // פרסר תוצאות מה-HTML של DDG
                List<String[]> results = parseDDGResults(body);

                runOnUiThread(() -> {
                    String html = buildSearchResultsHtml(finalQuery, results);
                    webView.loadDataWithBaseURL(SEARCH_BASE, html, "text/html", "UTF-8", null);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    // fallback - פתח DDG ישירות
                    isSearchResults = false;
                    webView.loadUrl(DDG_SEARCH + Uri.encode(finalQuery));
                });
            }
        }).start();
    }

    private List<String[]> parseDDGResults(String html) {
        List<String[]> results = new ArrayList<>();
        try {
            // חיפוש תוצאות בHTML של DDG
            // כל תוצאה נראית כך: <a class="result__a" href="...">title</a>
            java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
                "<a class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = linkPattern.matcher(html);

            java.util.regex.Pattern snippetPattern = java.util.regex.Pattern.compile(
                "<a class=\"result__snippet\"[^>]*>([^<]+)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher snippetMatcher = snippetPattern.matcher(html);

            List<String> snippets = new ArrayList<>();
            while (snippetMatcher.find()) {
                snippets.add(snippetMatcher.group(1).trim());
            }

            int i = 0;
            while (matcher.find() && results.size() < 10) {
                String url = matcher.group(1);
                String title = matcher.group(2).trim();
                String snippet = i < snippets.size() ? snippets.get(i) : "";
                if (url != null && !url.startsWith("//duckduckgo")) {
                    if (url.startsWith("/l/?")) {
                        // DDG redirect URL - חלץ את ה-URL האמיתי
                        int uddIdx = url.indexOf("uddg=");
                        if (uddIdx != -1) {
                            url = Uri.decode(url.substring(uddIdx + 5));
                            int ampIdx = url.indexOf("&");
                            if (ampIdx != -1) url = url.substring(0, ampIdx);
                        }
                    }
                    results.add(new String[]{title, url, snippet});
                    i++;
                }
            }
        } catch (Exception e) { /* ignore */ }
        return results;
    }

    private String buildSearchLoadingHtml(String query) {
        return "<!DOCTYPE html><html dir='rtl'><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>" +
            "* { margin:0; padding:0; box-sizing:border-box; }" +
            "body { background:#f5faf0; font-family:sans-serif; padding:16px; }" +
            ".header { display:flex; align-items:center; gap:12px; margin-bottom:20px; }" +
            ".logo { font-size:24px; font-weight:900; color:#3a7d1e; }" +
            ".query { color:#666; font-size:14px; }" +
            ".loading { display:flex; flex-direction:column; align-items:center; margin-top:60px; gap:16px; }" +
            ".dots { display:flex; gap:8px; }" +
            ".dot { width:10px; height:10px; border-radius:50%; background:#3a7d1e;" +
            "  animation:pulse 1.4s infinite ease-in-out; }" +
            ".dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}" +
            "@keyframes pulse{0%,80%,100%{transform:scale(.6);opacity:.4}40%{transform:scale(1);opacity:1}}" +
            "</style></head><body>" +
            "<div class='header'><span class='logo'>KiwiPlus 🥝</span>" +
            "<span class='query'>מחפש: " + query + "</span></div>" +
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
          .append("body { background:#f5faf0; font-family:sans-serif; padding:16px 16px 80px; }")
          .append(".header { display:flex; align-items:center; gap:8px; margin-bottom:16px; padding-bottom:12px; border-bottom:2px solid #e0f0e0; }")
          .append(".logo { font-size:22px; font-weight:900; color:#3a7d1e; }")
          .append(".search-row { flex:1; display:flex; background:white; border-radius:20px; padding:6px 12px; box-shadow:0 1px 6px rgba(0,0,0,0.1); }")
          .append(".search-row input { flex:1; border:none; outline:none; font-size:14px; color:#333; background:transparent; }")
          .append(".search-row button { background:none; border:none; cursor:pointer; font-size:16px; }")
          .append(".result { background:white; border-radius:12px; padding:14px; margin-bottom:10px; box-shadow:0 1px 6px rgba(0,0,0,0.06); cursor:pointer; }")
          .append(".result:active { background:#f0f9f0; }")
          .append(".result-title { font-size:16px; font-weight:600; color:#1a0dab; margin-bottom:4px; }")
          .append(".result-url { font-size:11px; color:#3a7d1e; margin-bottom:6px; }")
          .append(".result-snippet { font-size:13px; color:#555; line-height:1.4; }")
          .append(".no-results { text-align:center; margin-top:40px; color:#888; }")
          .append(".count { font-size:12px; color:#888; margin-bottom:12px; }")
          .append("</style></head><body>")
          .append("<div class='header'>")
          .append("<span class='logo'>🥝</span>")
          .append("<div class='search-row'>")
          .append("<input type='text' id='q' value='").append(query.replace("'", "\\'")).append("' onkeydown='if(event.key==\"Enter\") doSearch()' />")
          .append("<button onclick='doSearch()'>🔍</button>")
          .append("</div></div>");

        if (results.isEmpty()) {
            sb.append("<div class='no-results'>")
              .append("<p style='font-size:40px;margin-bottom:12px;'>🔍</p>")
              .append("<p>לא נמצאו תוצאות</p>")
              .append("</div>");
        } else {
            sb.append("<p class='count'>").append(results.size()).append(" תוצאות</p>");
            for (String[] r : results) {
                String title = r[0];
                String url = r[1];
                String snippet = r[2];
                String displayUrl = url.length() > 50 ? url.substring(0, 50) + "..." : url;
                sb.append("<div class='result' onclick=\"KiwiPlus.navigate('")
                  .append(url.replace("'", "\\'")).append("')\">")
                  .append("<div class='result-title'>").append(title).append("</div>")
                  .append("<div class='result-url'>").append(displayUrl).append("</div>")
                  .append("<div class='result-snippet'>").append(snippet).append("</div>")
                  .append("</div>");
            }
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
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForMedia(url);
                if (url.startsWith(HOME_BASE) || url.startsWith(SEARCH_BASE) || url.startsWith(PROXY_URL)) return null;
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
                if (isHomePage || isSearchResults || url.startsWith(HOME_BASE) || url.startsWith(SEARCH_BASE)) return;
                mediaUrls.clear();
                hlsInjected = false;
                updateMediaButton(false);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
                // הזרק network observer מוקדם - לפני שהדף נטען!
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

    // מזריק observer מוקדם - לפני שהדף מתחיל לטעון
    private void injectEarlyNetworkObserver(WebView view) {
        String js =
            "(function() {" +
            "  if (window._kiwiEarlyObserver) return;" +
            "  window._kiwiEarlyObserver = true;" +
            // XMLHttpRequest - תופס AJAX
            "  var origOpen = XMLHttpRequest.prototype.open;" +
            "  XMLHttpRequest.prototype.open = function(method, url) {" +
            "    if (url && typeof url === 'string') {" +
            "      var isMedia = /\\.(m3u8|mp4|ts|mp3|webm|mpd)(\\?|$)/i.test(url);" +
            "      var isStream = /(manifest|playlist|stream|hls|dash|segment|chunk)/i.test(url);" +
            "      var isKaltura = /kaltura|entry_id/i.test(url);" +
            "      if (isMedia || isStream || isKaltura) window.KiwiPlus.onMediaFound(url);" +
            "    }" +
            "    return origOpen.apply(this, arguments);" +
            "  };" +
            // Fetch - תופס fetch requests
            "  var origFetch = window.fetch;" +
            "  window.fetch = function(input, init) {" +
            "    var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');" +
            "    if (url) {" +
            "      var isMedia = /\\.(m3u8|mp4|ts|mp3|webm|mpd)(\\?|$)/i.test(url);" +
            "      var isStream = /(manifest|playlist|stream|hls|dash|segment|chunk)/i.test(url);" +
            "      var isKaltura = /kaltura|entry_id/i.test(url);" +
            "      if (isMedia || isStream || isKaltura) window.KiwiPlus.onMediaFound(url);" +
            "    }" +
            "    return origFetch.apply(this, arguments);" +
            "  };" +
            // WebSocket - תופס websocket streams
            "  var origWS = window.WebSocket;" +
            "  window.WebSocket = function(url, protocols) {" +
            "    if (url && /(stream|video|media|live)/i.test(url)) {" +
            "      window.KiwiPlus.onMediaFound(url);" +
            "    }" +
            "    return protocols ? new origWS(url, protocols) : new origWS(url);" +
            "  };" +
            // PerformanceObserver - תופס resources שנטענו
            "  try {" +
            "    var observer = new PerformanceObserver(function(list) {" +
            "      list.getEntries().forEach(function(entry) {" +
            "        var url = entry.name;" +
            "        if (!url || !url.startsWith('http')) return;" +
            "        var isMedia = /\\.(m3u8|mp4|ts|mp3|webm|mpd)(\\?|$)/i.test(url);" +
            "        var isStream = /(manifest|playlist|stream|hls|dash|segment|chunk)/i.test(url);" +
            "        if (isMedia || isStream) window.KiwiPlus.onMediaFound(url);" +
            "      });" +
            "    });" +
            "    observer.observe({ entryTypes: ['resource'] });" +
            "  } catch(e) {}" +
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
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE) || currentUrl.startsWith(SEARCH_BASE)) {
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
                    "<style>body { background:#1a1a2e; color:#a0d080; font-family:monospace; font-size:11px; padding:12px; white-space:pre-wrap; word-break:break-all; }</style>" +
                    "</head><body>" + source.replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
                webView.loadDataWithBaseURL("https://kiwiplus.source", sourceHtml, "text/html", "UTF-8", null);
                urlBar.setText("📄 מקור דף");
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
        if (currentUrl == null || currentUrl.startsWith(HOME_BASE) || currentUrl.startsWith(SEARCH_BASE)) {
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
            "  var urls = [];" +
            // סריקת video/audio elements
            "  document.querySelectorAll('video, audio, source').forEach(function(el) {" +
            "    if (el.src && el.src.startsWith('http')) urls.push(el.src);" +
            "    if (el.currentSrc && el.currentSrc.startsWith('http')) urls.push(el.currentSrc);" +
            "  });" +
            "  var h = document.documentElement.innerHTML;" +
            // סריקת m3u8 ישיר
            "  var m3u = h.match(/https?:[^'\"\\s\\\\]+\\.m3u8[^'\"\\s\\\\]*/g);" +
            "  if (m3u) m3u.forEach(function(u) { urls.push(u); });" +
            // סריקת Kaltura - מוצא partner_id + entry_id ובונה URL מלא
            "  var partnerId = null;" +
            "  var entryId = null;" +
            // חיפוש partner_id
            "  var pm = h.match(/['\"]?partner_?[Ii]d['\"]?\\s*[:=,]\\s*['\"]?(\\d{4,})/i);" +
            "  if (!pm) pm = h.match(/\\/p\\/(\\d{4,})\\//i);" +
            "  if (!pm) pm = h.match(/partnerId[^0-9]*(\\d{4,})/i);" +
            "  if (pm) partnerId = pm[1];" +
            // חיפוש entry_id
            "  var em = h.match(/entry_?[Ii]d[^a-zA-Z0-9_]*([01][_][a-zA-Z0-9]+)/i);" +
            "  if (!em) em = h.match(/entryId[^a-zA-Z0-9_]*([01][_][a-zA-Z0-9]+)/i);" +
            "  if (em) entryId = em[1];" +
            // בניית URL מלא של Kaltura
            "  if (partnerId && entryId) {" +
            "    var kUrl = 'https://cdnapisec.kaltura.com/p/' + partnerId +" +
            "      '/sp/' + partnerId + '00/playManifest/entryId/' + entryId +" +
            "      '/format/applehttp/protocol/https/a.m3u8';" +
            "    urls.push(kUrl);" +
            "    window.KiwiPlus.onMediaFound(kUrl);" +
            "  } else if (entryId) {" +
            // אם יש רק entry_id בלי partner_id
            "    window.KiwiPlus.onMediaFound('kaltura:entry_id=' + entryId);" +
            "  }" +
            // חיפוש iframes של Kaltura
            "  document.querySelectorAll('iframe').forEach(function(el) {" +
            "    var src = el.src || '';" +
            "    if (src.indexOf('kaltura') !== -1 || src.indexOf('entry_id') !== -1) {" +
            "      urls.push(src);" +
            // ניסיון לחלץ partner_id ו-entry_id מה-iframe src
            "      var ipm = src.match(/\\/p\\/(\\d{4,})/);" +
            "      var iem = src.match(/entry_?[Ii]d[=\\/]([01][_][a-zA-Z0-9]+)/);" +
            "      if (ipm && iem) {" +
            "        var ikUrl = 'https://cdnapisec.kaltura.com/p/' + ipm[1] +" +
            "          '/sp/' + ipm[1] + '00/playManifest/entryId/' + iem[1] +" +
            "          '/format/applehttp/protocol/https/a.m3u8';" +
            "        window.KiwiPlus.onMediaFound(ikUrl);" +
            "      }" +
            "    }" +
            "  });" +
            "  urls.filter(function(v,i,a){ return v && a.indexOf(v)===i; })" +
            "    .forEach(function(u) { window.KiwiPlus.onMediaFound(u); });" +
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
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            performSearch(input);
            return;
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
