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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // JavaScript Interface - גשר בין JS לJava
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
                    // אם זה m3u8 - הזרק hls.js אוטומטית
                    if (src.contains(".m3u8") && !hlsInjected) {
                        injectHlsIntoVideoJs(src);
                    }
                });
            }
        }
    }

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
        btnMenu = findViewById(R.id.btnMenu);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashScreen = findViewById(R.id.splashScreen);
        splashTitle = findViewById(R.id.splashTitle);
        splashSubtitle = findViewById(R.id.splashSubtitle);

        proxyClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(new okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build();

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(0xFFf0f7ee);

        // חיבור JavaScript Bridge
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

    private void showHomePage() {
        isHomePage = true;
        hlsInjected = false;
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
