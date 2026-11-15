package com.exerciseroutine.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.TimeZone;
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
    private static final int REQ_CALENDAR = 3;
    private static final String MARKER = "#exercise-routine-tracker";

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

    private void jsCall(String fn, String arg) {
        web.evaluateJavascript("window." + fn + " && window." + fn + "(" + arg + ")", null);
    }

    /** Remove the sessions this app added to a calendar (they carry a marker line). */
    private int removeOurs(long cal) {
        ContentResolver cr = getContentResolver();
        return cr.delete(CalendarContract.Events.CONTENT_URI,
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.DESCRIPTION + " LIKE ?",
                new String[]{String.valueOf(cal), "%" + MARKER + "%"});
    }

    /** Remove the original sessions Claude put in your calendar before the app could do it (titles like "Strength A (Phase 1)"). */
    private int removeLegacy() {
        ContentResolver cr = getContentResolver();
        ArrayList<String> args = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (String t : new String[]{"Strength A", "Strength B", "Easy run", "Long run"})
            for (int p = 1; p <= 4; p++) { if (in.length() > 0) in.append(','); in.append('?'); args.add(t + " (Phase " + p + ")"); }
        args.add("%Log it in the%");
        return cr.delete(CalendarContract.Events.CONTENT_URI,
                CalendarContract.Events.TITLE + " IN (" + in + ") AND " + CalendarContract.Events.DESCRIPTION + " LIKE ?",
                args.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CALENDAR) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        jsCall("__calPerm", String.valueOf(ok));
    }

    private void hideSplash() {
        if (splash == null || splash.getVisibility() == View.GONE) return;
        splash.animate().alpha(0f).setDuration(250).withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    private File dataFile() {
        return new File(getFilesDir(), "data.json");
    }

    private class Bridge {
        // ---- calendar ----
        @JavascriptInterface
        public boolean hasCalendarPermission() {
            return checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestCalendarPermission() {
            runOnUiThread(() -> {
                if (hasCalendarPermission()) jsCall("__calPerm", "true");
                else requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, REQ_CALENDAR);
            });
        }

        @JavascriptInterface
        public String calendars() {
            JSONArray out = new JSONArray();
            if (!hasCalendarPermission()) return out.toString();
            String[] cols = {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.IS_PRIMARY};
            String sel = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=? AND " + CalendarContract.Calendars.VISIBLE + "=1";
            try (Cursor c = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, cols, sel,
                    new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
                while (c != null && c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    String acct = c.getString(2), owner = c.getString(4);
                    o.put("id", c.getLong(0));
                    o.put("name", c.getString(1));
                    o.put("account", acct);
                    o.put("google", "com.google".equals(c.getString(3)));
                    o.put("primary", c.getInt(5) == 1 || (acct != null && acct.equals(owner) && acct.equals(c.getString(1))));
                    out.put(o);
                }
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public void clearCalendar(String calId) {
            new Thread(() -> {
                try { removeOurs(Long.parseLong(calId)); } catch (Exception ignored) { }
            }).start();
        }

        @JavascriptInterface
        public void syncCalendar(String calId, String eventsJson) {
            new Thread(() -> {
                JSONObject res = new JSONObject();
                try {
                    long cal = Long.parseLong(calId);
                    int removed = removeOurs(cal) + removeLegacy();
                    JSONArray evs = new JSONArray(eventsJson);
                    String tz = TimeZone.getDefault().getID();
                    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                    int added = 0;
                    for (int i = 0; i < evs.length(); i++) {
                        JSONObject e = evs.getJSONObject(i);
                        int back = ops.size();
                        ops.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                                .withValue(CalendarContract.Events.CALENDAR_ID, cal)
                                .withValue(CalendarContract.Events.TITLE, e.getString("title"))
                                .withValue(CalendarContract.Events.DESCRIPTION, e.getString("desc"))
                                .withValue(CalendarContract.Events.DTSTART, e.getLong("start"))
                                .withValue(CalendarContract.Events.DTEND, e.getLong("end"))
                                .withValue(CalendarContract.Events.EVENT_TIMEZONE, tz)
                                .withValue(CalendarContract.Events.HAS_ALARM, 1)
                                .build());
                        ops.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, back)
                                .withValue(CalendarContract.Reminders.MINUTES, 30)
                                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                                .build());
                        added++;
                        if (ops.size() >= 100) { getContentResolver().applyBatch(CalendarContract.AUTHORITY, ops); ops.clear(); }
                    }
                    if (!ops.isEmpty()) getContentResolver().applyBatch(CalendarContract.AUTHORITY, ops);
                    res.put("ok", true).put("added", added).put("removed", removed);
                } catch (Exception ex) {
                    try { res.put("ok", false).put("error", ex.getClass().getSimpleName()); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> jsCall("__calResult", res.toString()));
            }).start();
        }

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
