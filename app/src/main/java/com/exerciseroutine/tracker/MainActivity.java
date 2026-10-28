package com.exerciseroutine.tracker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Exercise Routine Tracker: the app's pages run inside a WebView, served from the APK's own assets.
 * Logs are kept in a file in the app's private storage (data.json), which Android's own
 * backup also covers. A "Save backup" in the app writes a copy wherever you choose.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;

    private WebView web;
    private View splash;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        splash = findViewById(R.id.splash);
        web.setBackgroundColor(getColor(R.color.bg));
        // Safety net: never leave the splash up for more than 10 seconds.
        splash.postDelayed(this::hideSplash, 10000);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // The app normally says it's ready itself; this is a backstop if it can't.
                splash.postDelayed(MainActivity.this::hideSplash, 3000);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Demo videos and any other links open in the phone's browser or YouTube app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose a backup file"), REQ_PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    private void hideSplash() {
        if (splash == null || splash.getVisibility() == View.GONE) return;
        splash.animate().alpha(0f).setDuration(250).withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    private File dataFile() {
        return new File(getFilesDir(), "data.json");
    }

    private class Bridge {
        @JavascriptInterface
        public void ready() {
            runOnUiThread(MainActivity.this::hideSplash);
        }

        @JavascriptInterface
        public String load() {
            File f = dataFile();
            if (!f.exists()) return "";
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void save(String json) {
            // Write to a temporary file first, then swap it in, so a crash mid-save can't corrupt your logs.
            File tmp = new File(getFilesDir(), "data.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(dataFile());
        }

        @JavascriptInterface
        public void saveBackup(String json, String fileName) {
            pendingBackup = json;
            runOnUiThread(() -> {
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("application/json");
                create.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(create, REQ_SAVE_FILE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Couldn't open the save screen", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_PICK_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(uri != null ? new Uri[]{uri} : null);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            boolean ok = false;
            if (uri != null && pendingBackup != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
            }
            pendingBackup = null;
            web.evaluateJavascript("window.__backupResult && window.__backupResult(" + ok + ")", null);
            if (ok) Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Let the app go back a screen first; close only from the main screen.
        web.evaluateJavascript("window.__appBack ? window.__appBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }
}
