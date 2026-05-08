package com.kiwiplus.browser;

import android.app.AlertDialog;
import android.graphics.Bitmap;
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

        showSplash();
        setupWebView();
        setupButtons();
        setupUrlBar();
    }

    private void showSplash() {
        splashScreen.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        // אנימציית כניסה
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

        // אחרי 2.5 שניות עובר לדפדפן
        new Handler().postDelayed(() -> hideSplash(), 2500);
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
                webView.loadUrl("about:blank");
            }
        });
        splashScreen.startAnimation(fadeOut);
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
                mediaUrls.clear();
                updateMediaButton(false);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                urlBar.setText(url);
                btnBack.setAlpha(view.canGoBack() ? 1f : 0.4f);
                btnForward.setAlpha(view.canGoForward() ? 1f : 0.4f);
                swipeRefresh.setRefreshing(false);

                view.evaluateJavascript(
                    "(function() {" +
                    "  var urls = [];" +
                    "  document.querySelectorAll('video, audio, source').forEach(function(el) {" +
                    "    if (el.src && el.src.length > 0) urls.push(el.src);" +
                    "    if (el.currentSrc && el.currentSrc.length > 0) urls.push(el.currentSrc);" +
                    "  });" +
                    "  return JSON.stringify(urls);" +
                    "})()",
                    value -> {
                        if (value != null && !value.equals("null") && !value.equals("\"[]\"")) {
                            runOnUiThread(() -> updateMediaButton(true));
                        }
                    }
                );
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
        });
    }

    private void checkForMedia(String url) {
        if (MEDIA_PATTERN.matcher(url).matches()) {
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
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });

        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });

        btnRefresh.setOnClickListener(v -> webView.reload());
        btnMedia.setOnClickListener(v -> showMediaDialog());
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    private void showMediaDialog() {
        if (mediaUrls.isEmpty()) {
            Toast.makeText(this, "לא נמצאו קישורי מדיה", Toast.LENGTH_SHORT).show();
            return;
        }

        // בניית רשימה להצגה
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
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = "https://www.google.com/search?q=" + input.replace(" ", "+");
        }
        webView.loadUrl(url);
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
