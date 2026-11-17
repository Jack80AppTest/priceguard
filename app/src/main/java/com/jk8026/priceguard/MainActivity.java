package com.jk8026.priceguard;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

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
    private TextView diagnosticView;
    private final StringBuilder diagnosticLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        configureDiagnosticLayout();

        configureWebView();
        configureBackNavigation();

        Uri incomingUri = getIntent() != null ? getIntent().getData() : null;
        if (isPriceGuardUri(incomingUri)) {
            addDiagnostic("Intent reçu: " + sanitizeUrl(incomingUri));
            webView.loadUrl(incomingUri.toString());
        } else if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        Uri incomingUri = intent != null ? intent.getData() : null;
        if (isPriceGuardUri(incomingUri) && webView != null) {
            addDiagnostic("Nouvel intent: " + sanitizeUrl(incomingUri));
            webView.loadUrl(incomingUri.toString());
        }
    }

    private boolean isPriceGuardUri(Uri uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            return false;
        }

        String host = uri.getHost();
        if (host == null) {
            return false;
        }

        String normalizedHost = host.toLowerCase(Locale.US);
        return normalizedHost.equals("priceguard.ca")
                || normalizedHost.endsWith(".priceguard.ca");
    }

    private void configureDiagnosticLayout() {
        FrameLayout root = new FrameLayout(this);

        root.addView(
                webView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        diagnosticView = new TextView(this);
        diagnosticView.setTextColor(Color.WHITE);
        diagnosticView.setBackgroundColor(0xCC000000);
        diagnosticView.setTextSize(11f);
        diagnosticView.setPadding(18, 12, 18, 12);
        diagnosticView.setVisibility(TextView.GONE);

        FrameLayout.LayoutParams diagnosticParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(diagnosticView, diagnosticParams);

        setContentView(root);
    }

    private void addDiagnostic(String message) {
        if (message == null) {
            return;
        }

        String sanitized = sanitizeDiagnosticText(message);
        if (diagnosticLog.length() > 0) {
            diagnosticLog.append("\n");
        }
        diagnosticLog.append(sanitized);

        if (diagnosticLog.length() > 6000) {
            diagnosticLog.delete(0, diagnosticLog.length() - 6000);
        }

        if (diagnosticView != null) {
            diagnosticView.setText(diagnosticLog.toString());
            diagnosticView.setVisibility(TextView.VISIBLE);
        }
    }

    private String sanitizeUrl(Uri uri) {
        if (uri == null) {
            return "(aucune URL)";
        }

        return uri.getScheme()
                + "://"
                + uri.getHost()
                + (uri.getPath() != null ? uri.getPath() : "");
    }

    private String sanitizeDiagnosticText(String text) {
        return text
                .replaceAll("(?i)(token_hash=)[^&\\s]+", "$1[masqué]")
                .replaceAll("(?i)(access_token=)[^&\\s]+", "$1[masqué]")
                .replaceAll("(?i)(refresh_token=)[^&\\s]+", "$1[masqué]")
                .replaceAll("(?i)(code=)[^&\\s]+", "$1[masqué]");
    }

    private boolean isAuthConfirmUrl(String url) {
        if (url == null) {
            return false;
        }

        Uri uri = Uri.parse(url);
        return isPriceGuardUri(uri) && "/auth/confirm".equals(uri.getPath());
    }

    private void runAuthDiagnostic(WebView view) {
        if (view == null || !isAuthConfirmUrl(view.getUrl())) {
            return;
        }

        String script =
                "(function(){try{"
                        + "var ck=(document.cookie||'').split(';').filter(Boolean).map(function(x){return x.split('=')[0].trim();});"
                        + "var lk=[];for(var i=0;i<localStorage.length;i++){lk.push(localStorage.key(i));}"
                        + "var sk=[];for(var j=0;j<sessionStorage.length;j++){sk.push(sessionStorage.key(j));}"
                        + "return JSON.stringify({"
                        + "origin:location.origin,"
                        + "path:location.pathname,"
                        + "ready:document.readyState,"
                        + "cookies:ck,"
                        + "localKeys:lk,"
                        + "sessionKeys:sk,"
                        + "ua:navigator.userAgent"
                        + "});"
                        + "}catch(e){return JSON.stringify({error:String(e)});}})();";

        view.evaluateJavascript(script, value -> addDiagnostic("JS état: " + value));
    }

    private boolean isMagicLinkVerification(Uri source, Uri destination) {
        return isDefaultHttpsOrigin(source, "app.priceguard.ca")
                && "/auth/confirm".equals(source.getEncodedPath())
                && isDefaultHttpsOrigin(
                        destination,
                        "waylslamfilmdatkqjun.supabase.co"
                )
                && "/auth/v1/verify".equals(destination.getEncodedPath());
    }

    private boolean isDefaultHttpsOrigin(Uri uri, String host) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

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
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (isAuthConfirmUrl(url)) {
                addDiagnostic("Début: " + sanitizeUrl(Uri.parse(url)));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri destination = request.getUrl();
            String currentUrl = view.getUrl();

            if (request.isForMainFrame()
                    && "GET".equals(request.getMethod())
                    && currentUrl != null
                    && isMagicLinkVerification(Uri.parse(currentUrl), destination)) {
                return false;
            }

            return handleUrl(destination);
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
                if (isPriceGuardUri(uri)) {
                    return false;
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
            if (isAuthConfirmUrl(view.getUrl())) {
                addDiagnostic(
                        "Erreur réseau: "
                                + error.getErrorCode()
                                + " "
                                + error.getDescription()
                );
            }

            if (request.isForMainFrame()) {
                showConnectionError();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            if (isAuthConfirmUrl(url)) {
                addDiagnostic("Fin: " + sanitizeUrl(Uri.parse(url)));
                runAuthDiagnostic(view);
            } else if (diagnosticView != null) {
                diagnosticView.setVisibility(TextView.GONE);
            }

            if (webBackCallback != null) {
                webBackCallback.setEnabled(view.canGoBack());
            }
        }
    }

    private final class PriceGuardChromeClient extends WebChromeClient {

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            if (consoleMessage != null
                    && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                    && isAuthConfirmUrl(webView != null ? webView.getUrl() : null)) {
                addDiagnostic(
                        "JS erreur: "
                                + consoleMessage.message()
                                + " @"
                                + consoleMessage.lineNumber()
                );
            }
            return super.onConsoleMessage(consoleMessage);
        }

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
