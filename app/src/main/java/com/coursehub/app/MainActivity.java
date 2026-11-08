package com.coursehub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // The app page is served from a real https origin (not file://), which is
    // what lets YouTube's player see a valid referrer and avoids Error 153.
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    // YouTube's own web endpoint that lists a playlist's videos, page by page.
    private static final String BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"
                    + "&key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20250925.01.00";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING_RESULTS = 8;

    // The page may only ask us to open these exact YouTube link shapes. The raw string is checked,
    // not Uri.getHost(), because Android's URL parsing can differ from the browser's.
    private static final Pattern YT_LINK = Pattern.compile(
            "^https://www\\.youtube\\.com/(watch\\?v=[A-Za-z0-9_-]{1,64}(&list=[A-Za-z0-9_-]{1,64})?"
                    + "|playlist\\?list=[A-Za-z0-9_-]{1,64})$");
    private static final Pattern REQ_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    // Used to stop a crash loop if the WebView's renderer keeps dying right after a restart.
    private static long lastRendererGone;

    private FrameLayout root;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    // One-time key given to our own page in the URL fragment. Android exposes the Native bridge to
    // every frame, including the YouTube player iframe; a frame that cannot read this key cannot use it.
    private final String bridgeToken = UUID.randomUUID().toString();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F1114);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0F1114);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // keeps your saved lectures
        s.setMediaPlaybackRequiresUserGesture(false); // lets the next lecture autoplay
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "Native");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;          // the YouTube player's own frame
                Uri u = request.getUrl();
                boolean https = "https".equals(u.getScheme());
                if (https && HOST.equals(u.getHost())) return false;  // our own page
                if (https) openInBrowser(u);                          // web links leave the app
                return true;                                          // any other scheme is dropped
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The WebView's renderer was killed (usually low memory). Without this the app crashes.
                long now = System.currentTimeMillis();
                boolean again = now - lastRendererGone < 15000;
                lastRendererGone = now;
                if (again) finish(); else recreate();
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                applyImmersive(true);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        root.addView(webView);
        setContentView(root);
        webView.loadUrl(START_URL + "#" + bridgeToken);
    }

    /**
     * Methods the page can call. Deliberately narrow: YouTube browse + open YouTube links.
     * Every call must carry the one-time key (bridgeToken) that only our own page knows.
     */
    private class Bridge {
        @JavascriptInterface
        public void browse(String token, final String reqId, final String body) {
            if (!bridgeToken.equals(token) || reqId == null || !REQ_ID.matcher(reqId).matches()
                    || body == null || body.length() > 20000) return;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String out;
                    try {
                        out = post(body);
                    } catch (Exception e) {
                        String m = e.getMessage();
                        out = "{\"__error\":" + JSONObject.quote(m == null ? "Network error" : m) + "}";
                    }
                    if (results.size() >= MAX_PENDING_RESULTS) results.clear();
                    results.put(reqId, out);
                    final String js = "window.__nativeDone && window.__nativeDone(" + JSONObject.quote(reqId) + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) webView.evaluateJavascript(js, null);
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public String take(String token, String reqId) {
            if (!bridgeToken.equals(token) || reqId == null) return "";
            String v = results.remove(reqId);
            return v == null ? "" : v;
        }

        @JavascriptInterface
        public void openExternal(String token, final String url) {
            if (!bridgeToken.equals(token) || url == null || url.length() > 300
                    || !YT_LINK.matcher(url).matches()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openInBrowser(Uri.parse(url));
                }
            });
        }
    }

    private String post(String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BROWSE_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Origin", "https://www.youtube.com");
            c.setRequestProperty("X-YouTube-Client-Name", "1");
            c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);

            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(data);
            } finally {
                os.close();
            }

            int code = c.getResponseCode();
            if (code != 200) throw new IOException("YouTube returned HTTP " + code);

            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
                    bos.write(buf, 0, n);
                }
            } finally {
                is.close();
            }
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void openInBrowser(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive(boolean on) {
        int flags = on
                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        applyImmersive(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        webView.evaluateJavascript("(window.__back && window.__back()) === true",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value)) finish();
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (webView != null) {
            root.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
