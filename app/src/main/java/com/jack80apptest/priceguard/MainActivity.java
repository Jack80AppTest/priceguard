package com.jack80apptest.priceguard;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {

    private static final String HOME_URL = "https://priceguard.ca/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;
    private OnBackPressedCallback webBackCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        configureWebView();
        configureBackNavigation();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    private void configureBackNavigation() {
        webBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    finish();
                }
            }
        };

        getOnBackPressedDispatcher().addCallback(this, webBackCallback);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new PriceGuardWebViewClient());
        webView.setWebChromeClient(new PriceGuardChromeClient());
    }

    private final class PriceGuardWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(Uri.parse(url));
        }

        private boolean handleUrl(Uri uri) {
            String scheme = uri.getScheme();

            if ("priceguard-app".equalsIgnoreCase(scheme)
                    && "retry".equalsIgnoreCase(uri.getHost())) {
                webView.loadUrl(HOME_URL);
                return true;
            }

            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                if (host != null) {
                    String normalizedHost = host.toLowerCase(Locale.US);
                    if (normalizedHost.equals("priceguard.ca")
                            || normalizedHost.endsWith(".priceguard.ca")) {
                        return false;
                    }
                }

                openExternal(uri);
                return true;
            }

            if ("mailto".equalsIgnoreCase(scheme)
                    || "tel".equalsIgnoreCase(scheme)
                    || "sms".equalsIgnoreCase(scheme)
                    || "geo".equalsIgnoreCase(scheme)) {
                openExternal(uri);
                return true;
            }

            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showConnectionError();
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error
        ) {
            if (request.isForMainFrame()) {
                showConnectionError();
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (webBackCallback != null) {
                webBackCallback.setEnabled(view.canGoBack());
            }
        }
    }

    private final class PriceGuardChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
        ) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }

            MainActivity.this.filePathCallback = filePathCallback;

            Intent fileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
            fileIntent.setType("*/*");
            fileIntent.putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    new String[]{"image/jpeg", "image/png", "application/pdf"}
            );

            Intent chooser = Intent.createChooser(
                    fileIntent,
                    getString(R.string.choose_receipt)
            );

            Intent cameraIntent = createCameraIntent();
            if (cameraIntent != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            }

            try {
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                return true;
            } catch (Exception e) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
                MainActivity.this.filePathCallback = null;
                cameraImageUri = null;
                return false;
            }
        }
    }

    private Intent createCameraIntent() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (cameraIntent.resolveActivity(getPackageManager()) == null) {
            return null;
        }

        try {
            File imageFile = File.createTempFile(
                    "priceguard_receipt_",
                    ".jpg",
                    getCacheDir()
            );

            cameraImageUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    imageFile
            );

            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            cameraIntent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            cameraIntent.setClipData(
                    ClipData.newRawUri("priceguard_receipt", cameraImageUri)
            );

            List<ResolveInfo> cameraActivities = getPackageManager().queryIntentActivities(
                    cameraIntent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            );

            for (ResolveInfo info : cameraActivities) {
                grantUriPermission(
                        info.activityInfo.packageName,
                        cameraImageUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            }

            return cameraIntent;
        } catch (IOException e) {
            cameraImageUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        Uri[] result = null;

        if (resultCode == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else if (cameraImageUri != null) {
                result = new Uri[]{cameraImageUri};
            }
        }

        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }

        cameraImageUri = null;
    }

    private void openExternal(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

    private void showConnectionError() {
        String html =
                "<!doctype html><html><head><meta name=\"viewport\" "
                        + "content=\"width=device-width,initial-scale=1\"></head>"
                        + "<body style=\"font-family:sans-serif;padding:32px;text-align:center;\">"
                        + "<h2>Connexion impossible</h2>"
                        + "<p>Vérifie ta connexion Internet, puis réessaie.</p>"
                        + "<p><a href=\"priceguard-app://retry\" "
                        + "style=\"display:inline-block;padding:12px 18px;"
                        + "background:#1565C0;color:white;text-decoration:none;"
                        + "border-radius:8px;\">Réessayer</a></p>"
                        + "</body></html>";

        webView.loadDataWithBaseURL(
                HOME_URL,
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
