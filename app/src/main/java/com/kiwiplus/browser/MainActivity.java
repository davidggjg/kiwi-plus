package com.kiwiplus.browser;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.view.animation.AnimationSet;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnRefresh, btnMedia;
    private SwipeRefreshLayout swipeRefresh;
    private View splashScreen;
    private TextView splashTitle, splashSubtitle;
    private List<String> mediaUrls = new ArrayList<>();

    private static final Pattern MEDIA_PATTERN = Pattern.compile(
        ".*\\.(mp4|m3u8|mp3|webm|ogg|avi|mkv|ts|flv|mov)(\\?.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern KALTURA_PATTERN = Pattern.compile(
        ".*(kaltura\\.com|cdnapisec\\.kaltura\\.com).*entry_id=([a-zA-Z0-9_]+).*",
        Pattern.CASE_INSENSITIVE
    );

    private static final String HOME_URL = "https://kiwiplus.home";

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
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashScreen = findViewById(R.id.splashScreen);
        splashTitle = findViewById(R.id.splashTitle);
        splashSubtitle = findViewById(R.id.splashSubtitle);

        webView.setBackgroundColor(0xFF0f0f1e);
        showSplash();
        setupWebView();
        setupButtons();
        setupUrlBar();
    }

    private void showSplash() {
        splashScreen.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        AnimationSet anim = new AnimationSet(true);
        ScaleAnimation scale = new ScaleAnimation(
            0.5f, 1f, 0.5f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        );
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
            @Override
            public void onAnimationEnd(Animation a) {
                splashScreen.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                showHomePage();
            }
        });
        splashScreen.startAnimation(fadeOut);
    }

    private void showHomePage() {
        urlBar.setText("");
        urlBar.setHint("חפש או הכנס כתובת");
        String html = "<!DOCTYPE html><html><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>" +
            "* { margin:0; padding:0; box-sizing:border-box; }" +
            "body { background:#0f0f1e; display:flex; flex-direction:column;" +
            "  align-items:center; justify-content:center; height:100vh;" +
            "  font-family: sans-serif; color:#fff; }" +
            "h1 { font-size:42px; color:#6c63ff; letter-spacing:4px; margin-bottom:8px; }" +
            "p { color:#555; font-size:14px; letter-spacing:2px; }" +
            ".dots { margin-top:40px; display:flex; gap:8px; }" +
            ".dot { width:8px; height:8px; border-radius:50%; background:#6c63ff;" +
            "  animation: pulse 1.4s infinite ease-in-out; }" +
            ".dot:nth-child(2) { animation-delay:0.2s; }" +
            ".dot:nth-child(3) { animation-delay:0.4s; }" +
            "@keyframes pulse { 0%,80%,100%{transform:scale(0.6);opacity:0.4}" +
            "  40%{transform:scale(1);opacity:1} }" +
            "</style></head><body>" +
            "<h1>KiwiPlus</h1>" +
            "<p>browse free</p>" +
            "<div class='dots'>" +
            "  <div class='dot'></div><div class='dot'></div><div class='dot'></div>" +
            "</div></body></html>";
        webView.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null);
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
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForMedia(url);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url.startsWith("data:") || url.equals(HOME_URL)) return;
                mediaUrls.clear();
                updateMediaButton(false);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.startsWith("data:") || url.equals(HOME_URL)) return;
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
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
                setContentView(customView);
            }

            @Override
            public void onHideCustomView() {
                setContentView(R.layout.activity_main);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                customView = null;
                recreate();
            }
        });
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
            "        try { var s2 = p.src(); if(s2) urls.push(s2); } catch(e){}" +
            "      }" +
            "    }" +
            "  } catch(e) {}" +
            "  document.querySelectorAll('iframe').forEach(function(el) {" +
            "    var src = el.src || '';" +
            "    if (src.indexOf('kaltura') !== -1 || src.indexOf('entry_id') !== -1) {" +
            "      urls.push(src);" +
            "    }" +
            "  });" +
            "  var html = document.documentElement.innerHTML;" +
            "  var km = html.match(/entry_id[^a-zA-Z0-9_]*([a-zA-Z0-9_]{5,})/g);" +
            "  if (km) km.forEach(function(m) {" +
            "    var id = m.replace(/entry_id[^a-zA-Z0-9_]*/, '');" +
            "    urls.push('kaltura:entry_id=' + id);" +
            "  });" +
            "  var m3u = html.match(/https?:[^'\"\\s]+\\.m3u8[^'\"\\s]*/g);" +
            "  if (m3u) m3u.forEach(function(u) { urls.push(u); });" +
            "  var unique = urls.filter(function(v,i,a){ return v && a.indexOf(v)===i; });" +
            "  return JSON.stringify(unique);" +
            "})()";

        view.evaluateJavascript(js, value -> {
            if (value == null || value.equals("null") || value.equals("\"[]\"")) return;
            try {
                String cleaned = value.replaceAll("^\"|\"$", "");
                if (cleaned.equals("[]")) return;
                String inner = cleaned.replaceAll("^\\[|\\]$", "");
                String[] parts = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String part : parts) {
                    String u = part.trim().replaceAll("^\"|\"$", "");
                    if (!u.isEmpty() && !mediaUrls.contains(u)) {
                        mediaUrls.add(u);
                    }
                }
                if (!mediaUrls.isEmpty()) {
                    runOnUiThread(() -> updateMediaButton(true));
                }
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
        btnRefresh.setOnClickListener(v -> webView.reload());
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
                copyToClipboard(items[which]);
                Toast.makeText(this, "✅ הקישור הועתק!", Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("📋 העתק הכל", (dialog, which) -> {
                copyToClipboard(allUrlsStr);
                Toast.makeText(this, "✅ כל הקישורים הועתקו!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("סגור", null)
            .show();
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip =
            android.content.ClipData.newPlainText("media url", text);
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
        if (input.startsWith("http://") || input.startsWith("https://")) {
            webView.loadUrl(input);
        } else if (input.contains(".") && !input.contains(" ")) {
            webView.loadUrl("https://" + input);
        } else {
            webView.loadUrl("https://duckduckgo.com/?q=" + Uri.encode(input));
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
