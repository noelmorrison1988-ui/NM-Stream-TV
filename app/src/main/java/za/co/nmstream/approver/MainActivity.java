package za.co.nmstream.approver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String START_URL = "https://nm-stream-tv-account.floot.app/";
    private static final String APP_HOST = "nm-stream-tv-account.floot.app";
    private static final int FILE_CHOOSER_REQUEST = 1201;

    private WebView webView;
    private FrameLayout splash;
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(9, 10, 13));
        getWindow().setNavigationBarColor(Color.rgb(9, 10, 13));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 10, 13));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(9, 10, 13));
        configureWebView();

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " NMStreamApprover/1.0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (splash != null && splash.getVisibility() == View.VISIBLE) {
                    splash.animate()
                            .alpha(0f)
                            .setDuration(220)
                            .withEndAction(() -> splash.setVisibility(View.GONE))
                            .start();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker is available.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
    }

    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

        if (("https".equals(scheme) || "http".equals(scheme)) && APP_HOST.equals(host)) {
            return false;
        }

        if ("https".equals(scheme) || "http".equals(scheme)
                || "mailto".equals(scheme) || "tel".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        return false;
    }

    private FrameLayout buildSplash() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.rgb(9, 10, 13));

        TextView brand = new TextView(this);
        brand.setText("NM STREAM");
        brand.setTextColor(Color.rgb(201, 166, 107));
        brand.setTextSize(30);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.12f);

        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        brandParams.gravity = Gravity.CENTER;
        brandParams.bottomMargin = 42;
        panel.addView(brand, brandParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("APPROVER");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.25f);

        FrameLayout.LayoutParams subParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.gravity = Gravity.CENTER;
        subParams.topMargin = 30;
        panel.addView(subtitle, subParams);

        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(42, 42);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        progressParams.bottomMargin = 120;
        panel.addView(progress, progressParams);

        return panel;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
