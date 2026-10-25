package de.olh.kmzmission;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity implements android.hardware.SensorEventListener {

    private WebView web;
    private SharedPreferences prefs;
    private LocationManager locationManager;
    private LocationListener locationListener;

    // ---------- Kompass (Geräteausrichtung) ----------
    private android.hardware.SensorManager sensorManager;
    private final float[] rotMat = new float[9], remapMat = new float[9], orient = new float[3];
    private double headingSmooth = Double.NaN;
    private long headingSent = 0;
    private float declination = 0;
    private long declinationAt = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        installAiaTrust();   // fehlende Zwischenzertifikate (dulv.de u. a.) automatisch nachladen
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        Elev.init(super.getFilesDir());
        try { Traffic.roleFile = new File(super.getFilesDir(), "einsatzflieger.txt"); if (!metered()) Traffic.roleUpdate(this, false); } catch (Exception ignored) { }
        dopCleanup();   // einmalig: Luftbilder mit grauem Fleck entfernen
        cacheVersionCheck();   // geänderte Datenbereiche aufräumen
        new Thread(this::uaveBackupRestore).start();   // UAV-Editor-Zugang aus „Kitzrettung Cache“ zurückholen (nach Neuinstallation)
        if (prefs.getInt("osm_ver", 1) == 4) new Thread(() -> {   // einmalig: Wege aus der OSM-Datei auslagern (sonst liest die App beim Start sehr lange)
            try {
                JSONArray all = new JSONArray(readFile(osmFile())), els = new JSONArray(), wys = new JSONArray();
                for (int i = 0; i < all.length(); i++) { JSONObject el = all.getJSONObject(i), t = el.optJSONObject("tags");
                    if (t != null && t.has("highway") && "way".equals(el.optString("type"))) wys.put(el); else els.put(el); }
                writeFileAtomic(osmWaysFile(), wys.toString()); writeFileAtomic(osmFile(), els.toString());
                prefs.edit().putInt("osm_ver", 5).apply();
                js("window.onOsmLines && window.onOsmLines()");
            } catch (Throwable ignored) { }
        }).start();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        web = new WebView(this);
        rootView = new android.widget.FrameLayout(this);
        rootView.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(rootView);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse r = tileFromCache(req.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, req);
            }

            /** Anzeige-Prozess abgestürzt (z. B. zu wenig Speicher): neu laden statt weißem Bildschirm */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.destroy();
                } catch (Exception ignored) { }
                runOnUiThread(() -> recreate());
                return true;
            }
        });
        new Thread(this::tileCacheTrim).start();
        web.addJavascriptInterface(new Bridge(), "Android");
        try { prefsRestore(); } catch (Exception ignored) { }   // Einstellungen nach Neuinstallation zurückholen
        web.loadUrl("file:///android_asset/index.html");

        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT < 30) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        requestPermissions(perms.toArray(new String[0]), 1);
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** 5D-Knopf der Fernsteuerung (meldet sich als Steuerkreuz): an die App weiterreichen. Andere Tasten (Zurück, Lautstärke …) normal. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // andere Meldewege derselben Richtungen auf das Steuerkreuz abbilden (je nach Fernsteuerung verschieden)
        if (k == 280 || k == 92) k = KeyEvent.KEYCODE_DPAD_UP;          // SYSTEM_NAVIGATION_UP / PAGE_UP
        else if (k == 281 || k == 93) k = KeyEvent.KEYCODE_DPAD_DOWN;   // SYSTEM_NAVIGATION_DOWN / PAGE_DOWN
        else if (k == 282) k = KeyEvent.KEYCODE_DPAD_LEFT;
        else if (k == 283) k = KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean dpad = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER;
        boolean inApp = pageBox != null && pageBox.getVisibility() == View.VISIBLE;   // Webseite/PDF in der App: dort normal blättern
        if (dpad && web != null && !inApp) {
            if (e.getAction() == KeyEvent.ACTION_DOWN)
                web.evaluateJavascript("window.onRcKey && window.onRcKey(" + k + "," + e.getRepeatCount() + ")", null);
            return true;
        }
        // Enter-Taste: Felder umschalten (UAV-Editor / Fernsteuerung) – abfangen, damit die Webansicht nichts „anklickt“
        if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) && web != null && !inApp && e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) {   // nicht die Bildschirmtastatur
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) web.evaluateJavascript("window.onRcKeyOther && window.onRcKeyOther(" + k + ")", null);
            return true;
        }
        // unbekannte Tasten für den Tasten-Test in den Einstellungen melden (nicht abfangen)
        if (e.getAction() == KeyEvent.ACTION_DOWN && web != null && k != KeyEvent.KEYCODE_BACK)
            web.evaluateJavascript("window.onRcKeyOther && window.onRcKeyOther(" + k + ")", null);
        return super.dispatchKeyEvent(e);
    }

    /** 5D-Knopf als Joystick/Steuerkreuz-Achse (HAT) oder Scrollrad: Richtungen an die App, Rohwerte für den Tasten-Test */
    private int hatX = 0, hatY = 0;
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        try {
            if (ev.getAction() == android.view.MotionEvent.ACTION_MOVE || ev.getAction() == android.view.MotionEvent.ACTION_SCROLL) {
                float hx = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
                float sv = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL), sh = ev.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL);
                boolean joy = (ev.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                        || (ev.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD;
                String raw = String.format(Locale.ROOT, "Quelle %d · HAT %.1f/%.1f · Scroll %.1f/%.1f · X/Y %.2f/%.2f", ev.getSource(), hx, hy, sh, sv,
                        ev.getAxisValue(android.view.MotionEvent.AXIS_X), ev.getAxisValue(android.view.MotionEvent.AXIS_Y));
                if (web != null) web.evaluateJavascript("window.onRcMotion && window.onRcMotion(" + JSONObject.quote(raw) + ")", null);
                int nx = Math.round(hx), ny = Math.round(hy), key = 0;
                if (nx != hatX) { if (nx < 0) key = KeyEvent.KEYCODE_DPAD_LEFT; else if (nx > 0) key = KeyEvent.KEYCODE_DPAD_RIGHT; hatX = nx; }
                if (ny != hatY) { if (ny < 0) key = KeyEvent.KEYCODE_DPAD_UP; else if (ny > 0) key = KeyEvent.KEYCODE_DPAD_DOWN; hatY = ny; }
                if (key != 0 && web != null) { web.evaluateJavascript("window.onRcKey && window.onRcKey(" + key + ",0)", null); return true; }
                if (joy && (hx != 0 || hy != 0)) return true;
            }
        } catch (Exception ignored) { }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume(); appFg = true;
        // Zugriff gerade (wieder) erteilt, z. B. nach Neuinstallation: ab jetzt den Ordner „Kitzrettung Cache“ benutzen und Karte neu laden
        try { File dc = dataDirCache; if (hasFileAccess() && dc != null && dc.equals(super.getFilesDir())) { dataDirCache = null; if (web != null && prefs.getBoolean("prefs_restored", false)) web.reload(); } } catch (Exception ignored) { }
        try { if (hasFileAccess() && !prefs.getBoolean("prefs_restored", false) && prefsRestore() && web != null) { web.reload(); } } catch (Exception ignored) { }
        try { if (hasFileAccess()) dataMigrate(); } catch (Exception ignored) { }   // Daten in „Kitzrettung Cache“ umziehen (einmalig)
        try { if (hasFileAccess() && !prefs.contains("uave_pw")) new Thread(() -> { uaveBackupRestore(); if (prefs.contains("uave_pw")) js("window.onUaveRestored && window.onUaveRestored()"); }).start(); } catch (Exception ignored) { }
        try {
            if (sensorManager == null) sensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
            android.hardware.Sensor rv = sensorManager != null ? sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) : null;
            if (rv != null) sensorManager.registerListener(this, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
            else js("window.onHeading && window.onHeading(null)");
        } catch (Exception ignored) { }
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    public void onBackPressed() {
        if (pageBox != null && pageBox.getVisibility() == android.view.View.VISIBLE) {   // Webseite in der App: zurück bzw. schließen
            if (pdfUrl == null && pageWeb.canGoBack()) pageWeb.goBack(); else closeInApp();
            return;
        }
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause(); appFg = false;
        try { if (sensorManager != null) sensorManager.unregisterListener(this); } catch (Exception ignored) { }
        stopLocation();
        try { new Thread(this::prefsBackupNow).start(); } catch (Exception ignored) { }   // Einstellungen sichern
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void stopLocation() {
        if (locTimeout != null) { locHandler.removeCallbacks(locTimeout); }
        if (locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) { }
            locationListener = null;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }
    }

    /** Technische Fehlermeldungen in verständlichen Text übersetzen */
    private String friendlyError(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e instanceof java.net.UnknownHostException || !online())
            return "Keine Internetverbindung – Abgleich mit UAV Editor nicht möglich. Die vorhandenen Felder bleiben nutzbar.";
        if (e instanceof java.net.SocketTimeoutException) return "UAV Editor antwortet nicht (Zeitüberschreitung). Bitte später erneut versuchen.";
        if (e instanceof java.net.ConnectException || e instanceof javax.net.ssl.SSLException) return "Verbindung zu UAV Editor fehlgeschlagen. Bitte Internetverbindung prüfen.";
        return m;
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isKmz(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".kmz") || n.endsWith(".kml");
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Datei mit gleichem Namen bis auf Leerzeichen/Bindestriche/Groß-Klein im selben Ordner */
    private static File findSimilar(File f) {
        File dir = f.getParentFile();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return null;
        String k = f.getName().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        for (File x : all) if (x.isFile() && x.getName().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "").equals(k)) return x;
        return null;
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        }
        // Datum immer auf "jetzt" setzen, damit die Planung in Pilot 2 als neueste erscheint
        dst.setLastModified(System.currentTimeMillis());
    }

    // ---------- Drohnen-Umrechnung M3T <-> M4T ----------
    // Werte ermittelt aus UAV-Editor-Exporten desselben Feldes für beide Drohnen.
    // Bildwinkel: M3T 49°, M4T 36° -> gleiche Streifenbreite bei 50 m bzw. 70 m.
    private static final Map<String, int[]> DRONES = new HashMap<>();   // {droneEnum, droneSub, payloadEnum, fov}
    static {
        DRONES.put("M3T", new int[]{77, 1, 67, 49});
        DRONES.put("M4T", new int[]{99, 1, 89, 36});
    }

    private static String droneOfKml(String kml) {
        Matcher e = Pattern.compile("<wpml:droneEnumValue>(\\d+)<").matcher(kml);
        Matcher s = Pattern.compile("<wpml:droneSubEnumValue>(\\d+)<").matcher(kml);
        if (e.find()) {
            int en = Integer.parseInt(e.group(1)), sub = s.find() ? Integer.parseInt(s.group(1)) : -1;
            for (Map.Entry<String, int[]> d : DRONES.entrySet())
                if (d.getValue()[0] == en && d.getValue()[1] == sub) return d.getKey();
        }
        // ohne Drohnenkennung: über die Flughöhe (Kitzrettung: Mavic 3T 50 m, Matrice 4T 70 m)
        Matcher h = Pattern.compile("<wpml:globalHeight>([\\d.]+)<").matcher(kml);
        if (h.find()) {
            double v = Double.parseDouble(h.group(1));
            if (v >= 45 && v <= 55 && DRONES.containsKey("M3T")) return "M3T";
            if (v >= 65 && v <= 75 && DRONES.containsKey("M4T")) return "M4T";
        }
        return null;
    }

    private static String setTag(String t, String tag, String value) {
        return t.replaceAll("(<wpml:" + tag + ">)[^<]*(<)", "$1" + Matcher.quoteReplacement(value) + "$2");
    }

    private static String convertKml(String t, String from, String to) {
        int[] a = DRONES.get(from), b = DRONES.get(to);
        Matcher gm = Pattern.compile("<wpml:globalHeight>([-\\d.]+)<").matcher(t);
        double base = gm.find() ? Double.parseDouble(gm.group(1)) : 50;
        long newBase = Math.round(base * Math.tan(Math.toRadians(a[3] / 2.0)) / Math.tan(Math.toRadians(b[3] / 2.0)));
        double delta = newBase - base;
        t = setTag(t, "droneEnumValue", String.valueOf(b[0]));
        t = setTag(t, "droneSubEnumValue", String.valueOf(b[1]));
        t = setTag(t, "payloadEnumValue", String.valueOf(b[2]));
        t = setTag(t, "updateTime", String.valueOf(System.currentTimeMillis()));
        Matcher m = Pattern.compile("(<wpml:(?:globalHeight|takeOffSecurityHeight|height|ellipsoidHeight|executeHeight)>)([-\\d.]+)(<)").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = m.group(2);
            double nv = Double.parseDouble(v) + delta;
            String out = v.contains(".") ? String.format(Locale.ROOT, "%.1f", nv) : String.valueOf(Math.round(nv));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + out + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Rechnet eine KMZ auf die Zieldrohne um. null = keine Änderung nötig (schon richtige Drohne). */
    private static byte[] convertKmz(File src, String to) throws Exception {
        if (!DRONES.containsKey(to)) throw new Exception("Unbekannte Zieldrohne " + to);
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(src))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = z.read(buf)) > 0) bos.write(buf, 0, n);
                entries.put(e.getName(), bos.toByteArray());
            }
        }
        String tplName = null;
        for (String n : entries.keySet()) if (n.toLowerCase(Locale.ROOT).endsWith("template.kml")) tplName = n;
        if (tplName == null) throw new Exception("keine DJI-Pilot-2-Mission (template.kml fehlt)");
        String tpl = new String(entries.get(tplName), StandardCharsets.UTF_8);
        String from = droneOfKml(tpl);
        if (from == null) throw new Exception("Drohnentyp der Datei unbekannt – nicht umgerechnet");
        if (from.equals(to)) return null;
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String ln = en.getKey().toLowerCase(Locale.ROOT);
            if (ln.endsWith(".kml") || ln.endsWith(".wpml"))
                en.setValue(convertKml(new String(en.getValue(), StandardCharsets.UTF_8), from, to).getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                zo.putNextEntry(new ZipEntry(en.getKey()));
                zo.write(en.getValue());
                zo.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Muss die Datei für die Zieldrohne umgerechnet werden? */
    private static boolean needsConvert(File src, String to) {
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(src))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().toLowerCase(Locale.ROOT).endsWith("template.kml")) continue;
                String t = new String(readBytes(z), StandardCharsets.UTF_8);
                String from = droneOfKml(t);
                return from != null && !from.equals(to);
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** KMZ neu packen: optional umrechnen (to != null), Zeitstempel createTime/updateTime und Zip-Einträge auf jetzt */
    private static byte[] rewriteKmz(File src, String to) throws Exception {
        if (to != null) {
            byte[] c = convertKmz(src, to);   // null = gleiche Drohne, nichts umzurechnen
            if (c != null) return stampKmz(c);
        }
        try (InputStream in = new FileInputStream(src)) { return stampKmz(readBytes(in)); }
    }

    private static byte[] stampKmz(byte[] kmz) throws Exception {
        String now = String.valueOf(System.currentTimeMillis());
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) { if (!e.isDirectory()) entries.put(e.getName(), readBytes(z)); }
        }
        if (entries.isEmpty()) return kmz;   // keine Zip-Datei: unverändert übernehmen
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                byte[] v = en.getValue();
                String ln = en.getKey().toLowerCase(Locale.ROOT);
                if (ln.endsWith(".kml") || ln.endsWith(".wpml")) {
                    String t = new String(v, StandardCharsets.UTF_8)
                            .replaceAll("<wpml:createTime>[^<]*</wpml:createTime>", "<wpml:createTime>" + now + "</wpml:createTime>")
                            .replaceAll("<wpml:updateTime>[^<]*</wpml:updateTime>", "<wpml:updateTime>" + now + "</wpml:updateTime>");
                    // fehlen die Zeitstempel in der Planung (template.kml), nach <Document> einfügen – Pilot 2 zeigt diese Zeit an
                    if (ln.endsWith("template.kml")) {
                        String add = "";
                        if (!t.contains("<wpml:createTime>")) add += "<wpml:createTime>" + now + "</wpml:createTime>";
                        if (!t.contains("<wpml:updateTime>")) add += "<wpml:updateTime>" + now + "</wpml:updateTime>";
                        if (!add.isEmpty()) t = t.replaceFirst("<Document>", "<Document>\n    " + java.util.regex.Matcher.quoteReplacement(add));
                    }
                    v = t.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry ze = new ZipEntry(en.getKey());
                ze.setTime(System.currentTimeMillis());
                zo.putNextEntry(ze); zo.write(v); zo.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** "Feld-M3T_1.8ha.kmz" -> "Feld-M4T_1.8ha.kmz" */
    private static String swapDroneInName(String name, String to) {
        String other = "M3T".equals(to) ? "M4T" : "M3T";
        return name.replaceAll("(?i)" + other, to);
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", msg); } catch (Exception ignored) { }
        return o;
    }

    /** Schnittstelle für die Oberfläche (assets/index.html) */
    public class Bridge {

        @JavascriptInterface
        public boolean hasAccess() { return hasFileAccess(); }

        /** Einstellungen nach Neuinstallation schon aus der Sicherung zurückgeholt? (vorher keine automatischen Downloads) */
        @JavascriptInterface
        public boolean prefsReady() { return hasFileAccess() && prefs.getBoolean("prefs_restored", false); }

        /** Größe des Cache-Ordners laut letzter Zählung (Bytes) – schnell, ohne neu zu zählen */
        @JavascriptInterface
        public double cacheBytes() { try { File f = cacheCountFile(); return f.isFile() ? new JSONObject(readFile(f)).optDouble("total", 0) : 0; } catch (Exception e) { return 0; } }

        /** true, wenn eine Internetverbindung besteht */
        @JavascriptInterface
        public boolean isOnline() { return online(); }

        /** Tag-/Nachtmodus: Statusleiste und Navigationsleiste passend einfärben */
        @JavascriptInterface
        public void setDark(boolean dark) {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        int c = dark ? 0xFF0F151B : 0xFFEEF2F5;
                        getWindow().setStatusBarColor(c);
                        getWindow().setNavigationBarColor(c);
                    }
                    if (Build.VERSION.SDK_INT >= 23) {
                        int f = getWindow().getDecorView().getSystemUiVisibility();
                        f = dark ? (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                        getWindow().getDecorView().setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
                }
            });
        }

        @JavascriptInterface
        public String getPref(String key, String def) { return prefs.getString(key, def); }

        @JavascriptInterface
        public void setPref(String key, String value) { prefs.edit().putString(key, value).apply(); prefsBackupLater(); }

        @JavascriptInterface
        public String internalRoot() { return Environment.getExternalStorageDirectory().getAbsolutePath(); }

        /** Interner Speicher + SD-Karten */
        @JavascriptInterface
        public String roots() {
            JSONArray arr = new JSONArray();
            try {
                arr.put(new JSONObject().put("name", "Interner Speicher")
                        .put("path", Environment.getExternalStorageDirectory().getAbsolutePath()));
                File[] dirs = getExternalFilesDirs(null);
                for (int i = 1; i < dirs.length; i++) {
                    if (dirs[i] == null) continue;
                    String p = dirs[i].getAbsolutePath();
                    int k = p.indexOf("/Android/");
                    if (k > 0) p = p.substring(0, k);
                    arr.put(new JSONObject().put("name", "SD-Karte " + (i == 1 ? "" : i)).put("path", p));
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public String listDirs(String path) {
            try {
                File dir = new File(path);
                JSONObject o = new JSONObject();
                o.put("ok", dir.isDirectory());
                o.put("path", dir.getAbsolutePath());
                File parent = dir.getParentFile();
                o.put("parent", parent != null && parent.canRead() ? parent.getAbsolutePath() : JSONObject.NULL);
                JSONArray names = new JSONArray();
                File[] list = dir.listFiles();
                int kmz = 0;
                if (list != null) {
                    List<String> n = new ArrayList<>();
                    for (File f : list) {
                        if (f.isDirectory() && !f.getName().startsWith(".")) n.add(f.getName());
                        else if (f.isFile() && isKmz(f.getName())) kmz++;
                    }
                    Collections.sort(n, String.CASE_INSENSITIVE_ORDER);
                    for (String s : n) names.put(s);
                }
                o.put("dirs", names);
                o.put("kmz", kmz);
                return o.toString();
            } catch (Exception e) {
                return err(e.getMessage()).toString();
            }
        }

        @JavascriptInterface
        public String listKmz(String path) {
            try {
                File dir = new File(path);
                if (!dir.isDirectory()) return err("Ordner nicht gefunden: " + path).toString();
                File[] list = dir.listFiles();
                if (list == null) return err("Kein Zugriff auf " + path).toString();
                Arrays.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                JSONArray arr = new JSONArray();
                for (File f : list) {
                    if (!f.isFile() || !isKmz(f.getName())) continue;
                    arr.put(new JSONObject().put("name", f.getName()).put("path", f.getAbsolutePath())
                            .put("size", f.length()).put("mtime", f.lastModified()));
                }
                return new JSONObject().put("ok", true).put("files", arr).toString();
            } catch (Exception e) {
                return err(e.getMessage()).toString();
            }
        }

        /** Heruntergeladene Felder (KMZ) löschen – nur Dateien direkt im angegebenen Quellordner. Ergebnis: Anzahl gelöscht */
        @JavascriptInterface
        public String deleteKmz(String pathsJson, String srcDir) {
            int ok = 0; JSONArray err = new JSONArray();
            try {
                File base = new File(srcDir).getCanonicalFile();
                JSONArray a = new JSONArray(pathsJson);
                for (int i = 0; i < a.length(); i++) {
                    File f = new File(a.getString(i)).getCanonicalFile();
                    if (!f.isFile() || !isKmz(f.getName()) || !base.equals(f.getParentFile())) { err.put(f.getName() + ": nicht im Quellordner"); continue; }
                    if (f.delete()) ok++; else err.put(f.getName() + ": lässt sich nicht löschen");
                }
                return new JSONObject().put("ok", ok).put("errors", err).toString();
            } catch (Exception e) { return err(e.getMessage()).toString(); }
        }

        /** Missionen von DJI Pilot 2 löschen – samt ihrem Ordner (DJI/Mission/KML/<ID>/). Nur Ordner direkt im Missionsordner. */
        @JavascriptInterface
        public String deleteRcMission(String pathsJson, String rcDir) {
            int ok = 0; JSONArray err = new JSONArray();
            try {
                File base = new File(rcDir).getCanonicalFile();
                JSONArray a = new JSONArray(pathsJson);
                for (int i = 0; i < a.length(); i++) {
                    File f = new File(a.getString(i)).getCanonicalFile(), d = f.getParentFile();
                    if (!f.isFile() || !isKmz(f.getName()) || d == null) { err.put(f.getName() + ": nicht gefunden"); continue; }
                    if (d.equals(base)) { if (f.delete()) ok++; else err.put(f.getName() + ": lässt sich nicht löschen"); continue; }   // liegt direkt im Ordner
                    if (!base.equals(d.getParentFile())) { err.put(f.getName() + ": nicht im Missionsordner"); continue; }
                    deleteTree(d);
                    if (!d.exists()) ok++; else err.put(f.getName() + ": Ordner lässt sich nicht (ganz) löschen");
                }
                return new JSONObject().put("ok", ok).put("errors", err).toString();
            } catch (Exception e) { return err(e.getMessage()).toString(); }
        }

        /** Missionen von DJI Pilot 2 auf der Fernsteuerung (DJI/Mission/KML/<ID>/<Name>.kmz) – je Unterordner eine Mission */
        @JavascriptInterface
        public String listKmzDeep(String path) {
            try {
                File dir = new File(path);
                if (!dir.isDirectory()) return err("Ordner nicht gefunden: " + path).toString();
                File[] subs = dir.listFiles();
                if (subs == null) return err("Kein Zugriff auf " + path).toString();
                JSONArray arr = new JSONArray();
                for (File d : subs) {
                    if (d.isFile() && isKmz(d.getName())) {   // Datei direkt im Ordner
                        arr.put(new JSONObject().put("name", d.getName()).put("path", d.getAbsolutePath()).put("size", d.length()).put("mtime", d.lastModified()));
                        continue;
                    }
                    if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                    File[] fs = d.listFiles(); File best = null;
                    if (fs != null) for (File f : fs) if (f.isFile() && isKmz(f.getName()) && (best == null || f.lastModified() > best.lastModified())) best = f;
                    if (best == null) { File t = new File(d, "wpmz/template.kml"); if (t.isFile()) best = t; }
                    if (best == null) continue;
                    String nm = best.getName().equalsIgnoreCase("template.kml") ? d.getName() + ".kml" : best.getName();
                    arr.put(new JSONObject().put("name", nm).put("path", best.getAbsolutePath()).put("size", best.length())
                            .put("mtime", best.lastModified()).put("rcId", d.getName()));
                }
                List<JSONObject> l = new ArrayList<>(); for (int i = 0; i < arr.length(); i++) l.add(arr.getJSONObject(i));
                Collections.sort(l, (x, y) -> x.optString("name").compareToIgnoreCase(y.optString("name")));
                return new JSONObject().put("ok", true).put("files", new JSONArray(l)).toString();
            } catch (Exception e) {
                return err(e.getMessage()).toString();
            }
        }

        /** Liefert den KML-Text einer KMZ/KML (bei KMZ bevorzugt wpmz/template.kml) */
        @JavascriptInterface
        public String readKml(String path) {
            try {
                File f = new File(path);
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".kml")) {
                    try (InputStream in = new FileInputStream(f)) { return readAll(in); }
                }
                String template = null;
                StringBuilder others = new StringBuilder();
                try (ZipInputStream zin = new ZipInputStream(new FileInputStream(f))) {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String n = e.getName().toLowerCase(Locale.ROOT);
                        if (e.isDirectory()) continue;
                        if (n.endsWith("template.kml")) template = readAll(zin);
                        else if (n.endsWith(".kml") || n.endsWith(".wpml")) others.append(readAll(zin)).append("\n<!--SPLIT-->\n");
                    }
                }
                // template.kml ohne Koordinaten (z. B. manche Pilot-2-Missionen): Wegpunkte aus waylines.wpml mitnehmen
                if (template != null && !template.contains("coordinates") && others.length() > 0) return template + "\n<!--SPLIT-->\n" + others;
                return template != null ? template : others.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /** Windenergieanlagen aus dem Marktstammdatenregister für das Einsatzgebiet: {states:[1405,…], box:[w,s,e,n]} */
        @JavascriptInterface
        public void mastrSync(String json) { new Thread(() -> mastrSyncImpl(json)).start(); }

        @JavascriptInterface
        public String mastrWind() {
            try { File f = dataFile("mastr_wind.json"); return f.isFile() ? readFile(f) : ""; } catch (Exception e) { return ""; }
        }

        /** Gespeicherte Geozonen-Bilder (dipul) löschen – vor dem Aktualisieren auf Knopfdruck */
        @JavascriptInterface
        public void dipulClear() { deleteTree(tileFile("dipul")); }

        /** Ordner anlegen (z. B. Zielordner beim Start, damit er in Pilot 2 beim Import schon da ist) */
        @JavascriptInterface
        public boolean ensureDir(String path) {
            try { File d = new File(path); return d.isDirectory() || d.mkdirs(); } catch (Exception e) { return false; }
        }

        /** Ordner umbenennen bzw. Inhalt verschieben (z. B. „KMZ Mission“ → „UAV-Editor“); vorhandene Dateien im Ziel bleiben */
        @JavascriptInterface
        public String moveDir(String from, String to) {
            try {
                File a = new File(from), b = new File(to);
                if (!a.isDirectory()) return "{\"ok\":true,\"moved\":0}";
                if (!b.exists() && a.renameTo(b)) return "{\"ok\":true,\"moved\":-1}";
                if (!b.isDirectory() && !b.mkdirs()) return "{\"ok\":false}";
                int n = 0; File[] fs = a.listFiles();
                if (fs != null) for (File f : fs) { File t = new File(b, f.getName()); if (!t.exists() && f.renameTo(t)) n++; }
                File[] rest = a.listFiles(); if (rest == null || rest.length == 0) a.delete();
                return "{\"ok\":true,\"moved\":" + n + "}";
            } catch (Exception e) { return "{\"ok\":false}"; }
        }

        /** Kopiert/verschiebt Dateien in den Zielordner */
        @JavascriptInterface
        public String transfer(String pathsJson, String dstPath, boolean move, boolean clear, String drone) {
            JSONObject res = new JSONObject();
            JSONArray errors = new JSONArray();
            List<String> scanned = new ArrayList<>();
            int done = 0;
            try {
                File dst = new File(dstPath);
                if (!dst.isDirectory() && !dst.mkdirs()) return err("Zielordner kann nicht angelegt werden: " + dstPath).toString();
                if (clear) {
                    File[] old = dst.listFiles();
                    if (old != null) for (File f : old)
                        if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".kmz") && !f.delete())
                            errors.put("Konnte nicht löschen: " + f.getName());
                }
                JSONArray paths = new JSONArray(pathsJson);
                for (int i = 0; i < paths.length(); i++) {
                    File src = new File(paths.getString(i));
                    if (!src.isFile()) {   // inzwischen umbenannt/ersetzt (z. B. durch einen Abgleich)? Gleichnamige Datei im Ordner suchen
                        File alt = findSimilar(src);
                        if (alt == null) { errors.put(src.getName() + ": nicht mehr im Quellordner (verschoben, gelöscht oder umbenannt)"); continue; }
                        src = alt;
                    }
                    try {
                        boolean conv = drone != null && !drone.isEmpty() && needsConvert(src, drone);
                        // immer neu schreiben: Zeitstempel in der Planung (createTime/updateTime) auf jetzt, ggf. umrechnen
                        byte[] converted = rewriteKmz(src, conv ? drone : null);
                        String name = conv ? swapDroneInName(src.getName(), drone) : src.getName();
                        File target = new File(dst, name);
                        if (src.getCanonicalPath().equals(target.getCanonicalPath())) {
                            errors.put(src.getName() + ": liegt bereits im Zielordner");
                            continue;
                        }
                        // alte Zieldatei löschen und neu anlegen -> auch das Erstelldatum der Datei ist "jetzt"
                        File tmp = new File(dst, "." + name + ".tmp");
                        try (OutputStream o = new FileOutputStream(tmp, false)) { o.write(converted); }
                        if (tmp.length() != converted.length) { tmp.delete(); throw new Exception("Schreiben unvollständig"); }
                        if (target.exists() && !target.delete()) { tmp.delete(); throw new Exception("alte Datei im Zielordner lässt sich nicht ersetzen"); }
                        if (!tmp.renameTo(target)) { copy(tmp, target); tmp.delete(); }
                        target.setLastModified(System.currentTimeMillis());
                        scanned.add(target.getAbsolutePath());
                        if (!res.has("check")) {   // zur Kontrolle: was tatsächlich in der Datei steht
                            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("<wpml:createTime>(\\d+)<").matcher(new String(converted, StandardCharsets.ISO_8859_1));
                            String inner = "";
                            try (ZipInputStream zc = new ZipInputStream(new ByteArrayInputStream(converted))) {
                                ZipEntry ze; while ((ze = zc.getNextEntry()) != null) if (ze.getName().toLowerCase(Locale.ROOT).endsWith("template.kml")) { inner = new String(readBytes(zc), StandardCharsets.UTF_8); break; }
                            } catch (Exception ignored) { }
                            java.util.regex.Matcher cm2 = java.util.regex.Pattern.compile("<wpml:createTime>(\\d+)<").matcher(inner);
                            java.util.regex.Matcher um2 = java.util.regex.Pattern.compile("<wpml:updateTime>(\\d+)<").matcher(inner);
                            res.put("check", new JSONObject().put("name", name).put("path", target.getAbsolutePath())
                                    .put("file", target.lastModified()).put("create", cm2.find() ? Long.parseLong(cm2.group(1)) : 0)
                                    .put("update", um2.find() ? Long.parseLong(um2.group(1)) : 0));
                        }
                        if (move) { if (!src.delete()) errors.put(src.getName() + ": kopiert, Original konnte nicht gelöscht werden"); else scanned.add(src.getAbsolutePath()); }
                        done++;
                    } catch (Exception e) {
                        errors.put(src.getName() + ": " + e.getMessage());
                    }
                }
                // Android-Dateiindex aktualisieren: sonst zeigen Dateiauswahl/Pilot 2 noch das alte Datum der ersetzten Datei
                if (!scanned.isEmpty()) {
                    try { android.media.MediaScannerConnection.scanFile(MainActivity.this, scanned.toArray(new String[0]), null, null); } catch (Exception ignored) { }
                }
                res.put("ok", errors.length() == 0);
                res.put("done", done);
                res.put("errors", errors);
                return res.toString();
            } catch (Exception e) {
                return err(e.getMessage()).toString();
            }
        }

        // ---------- UAV Editor ----------
        @JavascriptInterface
        public void uaveSetCredentials(String user, String password) {
            prefs.edit().putString("uave_user", user == null ? "" : user.trim()).apply();
            if (password != null && !password.isEmpty()) {
                try { prefs.edit().putString("uave_pw", encrypt(password)).apply(); }
                catch (Exception e) { prefs.edit().remove("uave_pw").apply(); }
            }
            uaveBackupSave(user, password);
        }

        /** Zugang sofort prüfen (Anmeldung beim UAV Editor). Ergebnis über window.onUaveTest({ok, error}) */
        @JavascriptInterface
        public void uaveTest() {
            new Thread(() -> {
                JSONObject r = new JSONObject();
                try {
                    if (!online()) throw new Exception("offline – Prüfung, sobald Internet da ist");
                    uaveToken = null; uaveLogin();
                    r.put("ok", true);
                } catch (Exception e) {
                    try { r.put("ok", false).put("offline", !online()).put("error", e.getMessage() != null ? e.getMessage() : "unbekannter Fehler"); } catch (Exception ignored) { }
                }
                js("window.onUaveTest && window.onUaveTest(" + JSONObject.quote(r.toString()) + ")");
            }).start();
        }

        @JavascriptInterface
        public String uaveUser() { return prefs.getString("uave_user", ""); }

        @JavascriptInterface
        public boolean uaveHasPassword() { return prefs.contains("uave_pw"); }

        @JavascriptInterface
        public void uaveForget() {
            prefs.edit().remove("uave_user").remove("uave_pw").remove("uave_manifest").apply();
            try { uaveBackupFile().delete(); } catch (Exception ignored) { }
        }

        /** Holt die Feldliste und vergleicht sie mit dem Quellordner. Ergebnis über window.onUave */
        @JavascriptInterface
        public void uaveCheck(String srcDir) {
            new Thread(() -> uaveCheckImpl(srcDir)).start();
        }

        /** Lädt die angegebenen Felder (JSON-Array mit IDs) als KMZ in den Quellordner */
        @JavascriptInterface
        public void uaveCancel() { uaveCancel = true; }

        @JavascriptInterface
        public void uaveDownload(String idsJson, String srcDir, String tzOffset) {
            new Thread(() -> uaveDownloadImpl(idsJson, srcDir, tzOffset)).start();
        }

        /** Mähdatum/Flugzeit je Datei aus dem letzten Abgleich: {datei:{id,cut,ft,name}} */
        @JavascriptInterface
        public String uaveFieldMeta() {
            JSONObject out = new JSONObject();
            try {
                JSONObject man = manifest();
                Iterator<String> it = man.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject m = man.optJSONObject(id);
                    if (m == null || m.optString("file").isEmpty()) continue;
                    out.put(m.optString("file"), new JSONObject().put("id", id).put("cut", m.optString("cut", ""))
                            .put("ft", m.optDouble("ft", 0)).put("name", m.optString("name", "")));
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Feldgrenzen je lokaler Datei: {datei: [lon,lat,lon,lat,…]} (aus dem letzten Abgleich) */
        @JavascriptInterface
        public String uaveOutlines() {
            JSONObject res = new JSONObject();
            try {
                JSONObject ol = outlineFile().isFile() ? new JSONObject(readFile(outlineFile())) : new JSONObject();
                JSONObject man = manifest();
                Iterator<String> it = man.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject m = man.optJSONObject(id);
                    if (m != null && ol.has(id) && !m.optString("file").isEmpty()) res.put(m.optString("file"), ringOf(ol.opt(id)));
                }
                // Alle Konturen mit Namen: die App ordnet Dateien ohne Eintrag über Namen bzw. Lage zu
                JSONArray all = new JSONArray();
                Iterator<String> it2 = ol.keys();
                while (it2.hasNext()) {
                    Object v = ol.opt(it2.next());
                    JSONArray r = ringOf(v);
                    if (r != null) all.put(new JSONObject().put("n", v instanceof JSONObject ? ((JSONObject) v).optString("n", "") : "").put("r", r));
                }
                res.put("_all", all);
                if (outlineFileByKmz().isFile()) {   // genaue Grenze aus der Export-JSON hat Vorrang
                    JSONObject bk = new JSONObject(readFile(outlineFileByKmz()));
                    Iterator<String> it3 = bk.keys();
                    while (it3.hasNext()) { String k = it3.next(); res.put(k, bk.get(k)); }
                }
            } catch (Exception ignored) { }
            return res.toString();
        }

        /** Ordnet lokale Dateinamen den UAV-Editor-Feld-IDs zu (aus dem letzten Abgleich). Ergebnis: {datei: id} */
        @JavascriptInterface
        public String uaveIdsForFiles(String filesJson) {
            JSONObject out = new JSONObject();
            try {
                JSONObject man = manifest();
                Map<String, String> byFile = new HashMap<>();
                Iterator<String> it = man.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject m = man.optJSONObject(id);
                    if (m != null) byFile.put(m.optString("file"), id);
                }
                JSONArray files = new JSONArray(filesJson);
                for (int i = 0; i < files.length(); i++) {
                    String f = files.getString(i);
                    if (byFile.containsKey(f)) out.put(f, byFile.get(f));
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Drohnentyp in UAV Editor ändern und die Felder neu exportieren */
        @JavascriptInterface
        public void uaveChangeDrone(String idsJson, String droneType, String srcDir, String tzOffset) {
            new Thread(() -> uaveChangeDroneImpl(idsJson, droneType, srcDir, tzOffset)).start();
        }

        // ---------- Geozonen (dipul) ----------
        @JavascriptInterface
        public String geoStatus() {
            try {
                return new JSONObject().put("fetched", geoFetched()).put("tiles", geoTileCount()).put("running", geoRunning)
                        .put("osmFetched", prefs.getLong("osm_fetched", 0)).put("osmSize", osmFile().length()).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Welche Zonengruppen sind im gespeicherten dipul-Kartenbild an diesem Punkt eingefärbt? (offline, aus den Kacheln)
         *  Ergebnis: {"hit":["luft",…],"have":["luft",…],"z":14} – have = Gruppen, für die hier ein Bild gespeichert ist */
        @JavascriptInterface
        public String dipulPixel(double lat, double lon, String groupsCsv) {
            JSONArray hit = new JSONArray(), have = new JSONArray();
            int zUsed = -1;
            try {
                double X = (lon + 180) / 360, sl = Math.sin(Math.toRadians(lat)), Y = 0.5 - Math.log((1 + sl) / (1 - sl)) / (4 * Math.PI);
                for (String g : groupsCsv.split(",")) {
                    if (g.isEmpty()) continue;
                    for (int z = 16; z >= 7; z--) {
                        double N = (1 << z) * 256.0;
                        int px = (int) Math.floor(X * N), py = (int) Math.floor(Y * N);
                        File f = tileFile("dipul/" + g + "/" + z + "/" + (px >> 8) + "/" + (py >> 8) + ".png");
                        if (!f.isFile() || f.length() == 0) continue;
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath());
                        if (bm == null) continue;
                        have.put(g); zUsed = Math.max(zUsed, z);
                        int cx = px & 255, cy = py & 255, n = 0;
                        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                            int xx = Math.max(0, Math.min(bm.getWidth() - 1, cx + dx)), yy = Math.max(0, Math.min(bm.getHeight() - 1, cy + dy));
                            if ((bm.getPixel(xx, yy) >>> 24) > 30) n++;
                        }
                        bm.recycle();
                        if (n >= 3) hit.put(g);
                        break;
                    }
                }
                return new JSONObject().put("hit", hit).put("have", have).put("z", zUsed).toString();
            } catch (Throwable t) { return "{\"hit\":[],\"have\":[]}"; }
        }

        /** Geozonen an einem Punkt abfragen (dipul GetFeatureInfo). Ergebnis über window.onGeoInfo */
        @JavascriptInterface
        public void geoInfo(double lat, double lon, String layersJson) {
            new Thread(() -> geoInfoImpl(lat, lon, layersJson)).start();
        }

        /** Stromleitungen/Masten in der Nähe aus OpenStreetMap (Overpass). Ergebnis über window.onPowerInfo */
        @JavascriptInterface
        public void powerInfo(double lat, double lon) {
            new Thread(() -> powerInfoImpl(lat, lon)).start();
        }

        /** DULV-Flugplatzdatenbank (ganz Deutschland, Telefon/PPR, Zeiten, Funk) */
        // ---------- Einsatz-Wächter ----------
        @JavascriptInterface
        public void watchStart(String cfg) {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                    Intent i = new Intent(MainActivity.this, Watch.class).putExtra("cfg", cfg);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                } catch (Exception e) { js("window.onWatch && window.onWatch(" + JSONObject.quote("Start fehlgeschlagen: " + e.getMessage()) + ")"); }
            });
        }
        @JavascriptInterface
        public void watchStop() { try { startService(new Intent(MainActivity.this, Watch.class).setAction("stop")); } catch (Exception ignored) { } }
        @JavascriptInterface
        public boolean watchRunning() { Thread t = Watch.th; return t != null && t.isAlive(); }
        @JavascriptInterface
        public String watchLast() { return Watch.lastInfo; }
        @JavascriptInterface
        public String watchCfg() { Thread t = Watch.th; return t != null && t.isAlive() && Watch.cfg != null ? Watch.cfg : ""; }
        /** Luftverkehr für die Karte: Umkreis um lat/lon (km) – läuft, bis trafficStop() */
        @JavascriptInterface
        public void elevPrefetch(String pts) {   // Geländehöhen der Wiesen einmalig holen und dauerhaft merken (für „über Grund“ beim Luftverkehr)
            new Thread(() -> { try { JSONArray a = new JSONArray(pts); List<double[]> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) { JSONArray q = a.optJSONArray(i); if (q != null) l.add(new double[]{q.optDouble(0), q.optDouble(1)}); }
                Elev.fetch(l); } catch (Exception ignored) { } }).start();
        }
        @JavascriptInterface
        public String elevAt(double lat, double lon) { double e = Elev.cached(lat, lon); return Double.isNaN(e) ? "" : String.valueOf(Math.round(e)); }
        @JavascriptInterface
        public void trafficStart(double lat, double lon, double km) { try { Traffic.uiOn = true; Traffic.start(lat, lon, km); } catch (Throwable e) { Traffic.err = "Start: " + e; } }
        @JavascriptInterface
        public void trafficStop() { Traffic.uiOn = false; Traffic.stopIfIdle(); }
        @JavascriptInterface
        public String traffic() { try { return Traffic.json(); } catch (Throwable e) { return "{\"err\":" + JSONObject.quote(String.valueOf(e)) + ",\"ac\":[]}"; } }
        @JavascriptInterface
        public void ridStart() {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED)
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN", Manifest.permission.ACCESS_FINE_LOCATION}, 78));
            try { Rid.uiOn = true; Rid.start(getApplicationContext()); } catch (Throwable e) { Rid.err = "Start: " + e; }
        }
        @JavascriptInterface
        public void ridStop() { Rid.uiOn = false; Rid.stopIfIdle(); }
        @JavascriptInterface
        public String roleInfo() {   // Stand der Liste Polizei/Rettung
            Traffic.roleDbGet("000000");
            return "{\"n\":" + Traffic.roleN + ",\"t\":" + Traffic.roleT + ",\"busy\":" + Traffic.roleBusy + ",\"err\":" + JSONObject.quote(Traffic.roleErr) + "}";
        }
        @JavascriptInterface
        public void roleUpdate() { Traffic.roleUpdate(getApplicationContext(), true); }
        @JavascriptInterface
        public String rid() { try { if (Rid.running) Rid.retryBle(); return Rid.json(); } catch (Throwable e) { return "{\"err\":" + JSONObject.quote(String.valueOf(e)) + ",\"d\":[]}"; } }
        @JavascriptInterface
        public boolean canOverlay() { return Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(MainActivity.this); }
        @JavascriptInterface
        public void overlayPerm() {
            runOnUiThread(() -> { try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } } });
        }
        /** Probe: Benachrichtigung nach ein paar Sekunden – zum Wechseln in DJI Pilot 2 */
        @JavascriptInterface
        public void notifyTrafTest(int snd) {   // Probe der Luftverkehr-Warnung mit gewähltem Ton
            final Context c = getApplicationContext();
            new Thread(() -> { try { Thread.sleep(3000L); } catch (InterruptedException ignored) { }
                alert(c, 4712, "✈ Test: Hubschrauber 1,2 km NW – kommt näher", "ca. 150 m über Grund · 180 km/h. So sieht die Luftverkehr-Warnung aus.", CH_TRAF[Math.max(0, Math.min(2, snd))]);
                testBeep(snd); }).start();
        }
        void testBeep(int snd) {
            android.media.ToneGenerator tg = null;
            try { if (snd <= 0) return; tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
                if (snd >= 2) { for (int i = 0; i < 3; i++) { tg.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900); Thread.sleep(1000); } }
                else { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(700); tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(600); }
            } catch (Exception ignored) { } finally { if (tg != null) try { tg.release(); } catch (Exception ignored) { } }
        }
        @JavascriptInterface
        public void notifyTest(int sec) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77));
            final Context c = getApplicationContext();
            new Thread(() -> { try { Thread.sleep(Math.max(1, sec) * 1000L); } catch (InterruptedException ignored) { }
                alert(c, 4711, "Test: Mission Kitzrettung", "So sieht eine Warnung des Einsatz-Wächters aus – z. B. „Regen ab 07:15“. Wenn du das über DJI Pilot 2 siehst, funktioniert es."); }).start();
        }

        @JavascriptInterface
        /** Verbindungstest: jeden Datenserver kurz anfragen und Ergebnis (ok/Fehler, Zeit) melden */
        public void netTest() {
            new Thread(() -> {
                JSONArray out = new JSONArray();
                List<String[]> t = new ArrayList<>();
                t.add(new String[]{"OpenStreetMap-Abfrage (nur beim Antippen außerhalb des Gebiets)", OVERPASS[0], "post"});
                t.add(new String[]{"DULV Flugplätze", "https://dulv.de/fmwebphp/Flugplatz/show_all.php", "get"});
                t.add(new String[]{"BKG Landesgrenzen", "https://sgx.geodatenzentrum.de/wfs_vg2500?SERVICE=WFS&REQUEST=GetCapabilities", "get"});
                t.add(new String[]{"dipul", "https://uas-betrieb.de/geoservices/dipul/wms?SERVICE=WMS&REQUEST=GetCapabilities", "get"});
                t.add(new String[]{"Geofabrik (OpenStreetMap-Dateien)", "https://download.geofabrik.de/europe/germany/bremen.poly", "get"});
                t.add(new String[]{"GitHub (Vergleich)", "https://raw.githubusercontent.com/", "get"});
                for (String[] x : t) {
                    long t0 = System.currentTimeMillis(); String res;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(x[1]).openConnection();
                        c.setConnectTimeout(8000); c.setReadTimeout(15000);
                        c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Kitzrettung, nicht-kommerziell)");
                        if ("post".equals(x[2])) {
                            c.setRequestMethod("POST"); c.setDoOutput(true);
                            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                            try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode("[out:json][timeout:10];node(50.38,8.06,50.39,8.07);out 1;", "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
                        }
                        int code = c.getResponseCode(); c.disconnect();
                        res = code < 400 ? "ok" : "HTTP " + code;
                    } catch (Exception e) {
                        String m = String.valueOf(e.getMessage());
                        res = e instanceof javax.net.ssl.SSLException || m.contains("CertPath") || m.contains("Trust anchor") ? "Zertifikat: " + m
                                : e instanceof java.net.UnknownHostException ? "Name nicht auflösbar (DNS)" : e instanceof java.net.SocketTimeoutException ? "Zeitüberschreitung" : m;
                    }
                    try { out.put(new JSONObject().put("name", x[0]).put("res", res).put("ms", System.currentTimeMillis() - t0)); } catch (Exception ignored) { }
                    js("window.onNetTest && window.onNetTest(" + JSONObject.quote(out.toString()) + ",false)");
                }
                js("window.onNetTest && window.onNetTest(" + JSONObject.quote(out.toString()) + ",true)");
            }).start();
        }

        @JavascriptInterface
        public String uaveCatalog() { try { File f = dataFile("uave_katalog.json"); return f.isFile() ? readFile(f) : ""; } catch (Exception e) { return ""; } }

        @JavascriptInterface
        public String rcModel() { return String.valueOf(Build.MODEL) + "|" + String.valueOf(Build.DEVICE) + "|" + String.valueOf(Build.PRODUCT); }

        @JavascriptInterface
        public String lfa() { try { File f = dataFile("lfa.kml"); return f.isFile() ? readFile(f) : ""; } catch (Exception e) { return ""; } }

        @JavascriptInterface
        public String dulv() { try { File f = dulvFile(); return f.isFile() ? readFile(f) : ""; } catch (Exception e) { return ""; } }
        @JavascriptInterface
        public void dulvSync(boolean force) { dulvSyncImpl(force); }

        /** Flugplätze im gespeicherten Gebiet inkl. Frequenzen: {fetched, list:[...]} */
        @JavascriptInterface
        public String airfields() {
            try {
                File f = afFile();
                JSONArray list = f.isFile() ? new JSONArray(readFile(f)) : new JSONArray();
                JSONObject freqs = afFreqFile().isFile() ? new JSONObject(readFile(afFreqFile())) : new JSONObject();
                JSONArray out = new JSONArray();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject el = list.getJSONObject(i);
                    JSONObject t = el.optJSONObject("tags");
                    if (t == null) continue;
                    double lat = el.has("lat") ? el.optDouble("lat") : el.optJSONObject("center") != null ? el.optJSONObject("center").optDouble("lat") : Double.NaN;
                    double lon = el.has("lon") ? el.optDouble("lon") : el.optJSONObject("center") != null ? el.optJSONObject("center").optDouble("lon") : Double.NaN;
                    if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                    String icao = t.optString("icao", "");
                    boolean rth = "landing_site".equals(t.optString("emergency")) && !OsmPbf.AERO.contains(t.optString("aeroway"));   // Rettungshubschrauber-Landestelle
                    boolean pad = "helipad".equals(t.optString("aeroway")) || rth;   // einzelne Landefläche (z. B. Klinik) – wie ein Hubschrauberlandeplatz behandeln
                    if (pad) {   // liegt sie auf einem schon erfassten Flugplatz/Landeplatz: nicht doppelt zeigen
                        boolean dup = false;
                        for (int q = 0; q < list.length() && !dup; q++) { JSONObject o = list.optJSONObject(q); JSONObject ot = o == null ? null : o.optJSONObject("tags");
                            if (ot == null || "helipad".equals(ot.optString("aeroway")) || "landing_site".equals(ot.optString("emergency")) && !OsmPbf.AERO.contains(ot.optString("aeroway"))) continue;
                            double ola = o.has("lat") ? o.optDouble("lat") : o.optJSONObject("center") != null ? o.optJSONObject("center").optDouble("lat") : Double.NaN;
                            double olo = o.has("lon") ? o.optDouble("lon") : o.optJSONObject("center") != null ? o.optJSONObject("center").optDouble("lon") : Double.NaN;
                            if (!Double.isNaN(ola) && Math.hypot((ola - lat) * 111.2, (olo - lon) * 111.2 * Math.cos(Math.toRadians(lat))) < ("heliport".equals(ot.optString("aeroway")) ? 0.4 : 1.5)) dup = true; }
                        if (dup) continue;
                    }
                    out.put(new JSONObject()
                            .put("id", el.optString("type") + "/" + el.optLong("id"))
                            .put("name", t.optString("name", t.optString("short_name", rth ? "Landestelle Rettungshubschrauber" : pad ? "Hubschrauberlandeplatz" : "Flugplatz")))
                            .put("icao", icao)
                            .put("kind", pad ? "heliport" : t.optString("aeroway"))
                            .put("rth", rth)
                            .put("type", t.optString("aerodrome:type", t.optString("aerodrome", "")))
                            .put("ele", t.optString("ele", ""))
                            .put("phone", t.optString("phone", t.optString("contact:phone", "")))
                            .put("website", t.optString("website", t.optString("contact:website", "")))
                            .put("operator", t.optString("operator", ""))
                            .put("lat", lat).put("lon", lon)
                            .put("freqs", icao.isEmpty() ? new JSONArray() : freqs.optJSONArray(icao.toUpperCase(Locale.ROOT)) != null ? freqs.optJSONArray(icao.toUpperCase(Locale.ROOT)) : new JSONArray()));
                }
                // ergänzen: Hubschrauberlandeplätze aus OurAirports, die in OpenStreetMap fehlen (im geladenen Gebiet)
                try {
                    JSONObject bb = new JSONObject(prefs.getString("pbf_bbox", prefs.getString("af_bbox", "{}")));
                    if (bb.has("n")) for (String ln : OA_HELI.split("\n")) {
                        String[] a = ln.split("\\|"); if (a.length < 4) continue;
                        double la = Double.parseDouble(a[2]), lo = Double.parseDouble(a[3]);
                        if (la > bb.getDouble("n") || la < bb.getDouble("s") || lo > bb.getDouble("e") || lo < bb.getDouble("w")) continue;
                        boolean dup = false;
                        for (int q = 0; q < out.length() && !dup; q++) { JSONObject o = out.getJSONObject(q);
                            if (Math.hypot((o.getDouble("lat") - la) * 111.2, (o.getDouble("lon") - lo) * 111.2 * Math.cos(Math.toRadians(la))) < ("heliport".equals(o.optString("kind")) ? 0.35 : 1.0)) dup = true; }
                        if (dup) continue;
                        out.put(new JSONObject().put("id", "oa/" + a[0]).put("name", a[1]).put("icao", "").put("kind", "heliport").put("type", "").put("ele", "").put("phone", "").put("website", "").put("operator", "")
                                .put("lat", la).put("lon", lo).put("freqs", new JSONArray()).put("src", "OurAirports"));
                    }
                } catch (Exception ignored) { }
                return new JSONObject().put("fetched", prefs.getLong("af_fetched", 0)).put("list", out).toString();
            } catch (Exception e) { return "{\"list\":[]}"; }
        }

        /** dipul-Wetter (DFS, ICON-D2) für einen Punkt, nächste 24 h. Ergebnis über window.onDipulWx(id, json) */
        @JavascriptInterface
        public void dipulWeather(String reqId, double lat, double lon) {
            new Thread(() -> dipulWeatherImpl(reqId, lat, lon)).start();
        }

        /** Einfacher GET-Abruf (z. B. Kp-Index), Ergebnis über window.onFetch(id, {ok, code, body}) */
        @JavascriptInterface
        public void fetchText(String reqId, String url) {
            new Thread(() -> {
                JSONObject r = new JSONObject();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "KMZMissionen/1.9 (Kitzrettung, nicht-kommerziell)");
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String body = in != null ? readAll(in) : "";
                    c.disconnect();
                    r.put("ok", code >= 200 && code < 300).put("code", code).put("body", body);
                } catch (Exception e) {
                    try { r.put("ok", false).put("error", friendlyError(e)); } catch (Exception ignored) { }
                }
                js("window.onFetch && window.onFetch(" + JSONObject.quote(reqId) + "," + JSONObject.quote(r.toString()) + ")");
            }).start();
        }

        /** Temporäre Betriebseinschränkungen (NOTAM) im Gebiet: gespeicherte Daten */
        @JavascriptInterface
        public String tileCacheInfo() { return String.valueOf(lastTileBytes); }   // zuletzt gezählter Wert – Zählen läuft im Hintergrund (cacheInfoAsync), sonst hängt die App


        /** Einsatzgebiet vorab laden: {layers:["bmg","bmf","dop"], area:[w,s,e,n], fields:[[w,s,e,n],…], zMin, zArea, zMax} */
        @JavascriptInterface
        public void prefetchStart(String json) { prefetchImpl(json); }

        @JavascriptInterface
        public void prefetchCancel() { prefetchStop = true; }

        @JavascriptInterface
        public boolean prefetchRunning() { return prefetchRun; }

        /** Größe je Datenbereich – im Hintergrund gezählt (bei vielen Kacheln dauert das), Ergebnis über window.onCacheInfo */
        @JavascriptInterface
        public void cacheInfoAsync() {
            // 1. sofort den zuletzt gezählten Stand zeigen (liegt im Cache-Ordner, überlebt Updates)
            try { File lf = cacheCountFile(); if (lf.isFile()) { JSONObject o = new JSONObject(readFile(lf)); o.put("counting", true); o.put("where", dataDir().getAbsolutePath());
                js("window.onCacheInfo && window.onCacheInfo(" + JSONObject.quote(o.toString()) + ")"); } } catch (Exception ignored) { }
            if (cacheCounting) { cacheCountAgain = true; return; }   // läuft schon – danach einmal nachzählen
            cacheCounting = true;
            new Thread(() -> {
                try {
                    do { cacheCountAgain = false; cacheCountOnce(); if (cacheCountAgain) Thread.sleep(30000); } while (cacheCountAgain);   // höchstens alle 30 s neu zählen
                } catch (InterruptedException ignored) {
                } finally { cacheCounting = false; }
            }).start();
        }

        /** Einen Datenbereich löschen (wird beim nächsten Speichern/Abgleich neu geholt). Ergebnis über window.onCacheCleared */
        @JavascriptInterface
        public void pbfForce() { pbfForceOnce = true; }

        @JavascriptInterface
        public void cacheClear(String id) {
            new Thread(() -> {
                for (File f : catFiles(id)) deleteTree(f);
                catReset(id); cacheMarkTouch(id);
                js("window.onCacheCleared && window.onCacheCleared(" + JSONObject.quote(id) + ")");
            }).start();
        }

        @JavascriptInterface
        public void tileCacheClear() {
            new Thread(() -> {
                java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>();
                List<File> dirs = new ArrayList<>();
                for (File r0 : tileRoots()) st.push(r0);
                while (!st.isEmpty()) {
                    File d = st.pop(); dirs.add(d);
                    File[] fs = d.listFiles();
                    if (fs == null) continue;
                    for (File x : fs) { if (x.isDirectory()) st.push(x); else x.delete(); }
                }
                js("window.onTileCache && window.onTileCache()");
            }).start();
        }

        /** Gespeicherte Stromleitungen (OSM) kompakt für die Karte: [{kv, p, n, o, g:[lon,lat,…]}] */
        @JavascriptInterface
        public String osmLines() {
            JSONArray out = new JSONArray();
            try {
                if (!osmFile().isFile()) return "[]";
                JSONArray els = new JSONArray(readFile(osmFile()));
                for (int i = 0; i < els.length(); i++) {
                    JSONObject el = els.getJSONObject(i);
                    if (!"way".equals(el.optString("type"))) continue;
                    JSONObject t = el.optJSONObject("tags");
                    JSONArray g = el.optJSONArray("geometry");
                    if (t == null || g == null || g.length() < 2) continue;
                    if (!t.optString("power").matches("line|minor_line")) continue;   // nur Freileitungen (Erdkabel/Bahn/Windräder separat)
                    JSONArray pts = new JSONArray();
                    for (int k = 0; k < g.length(); k++) {
                        JSONObject pt = g.optJSONObject(k);
                        if (pt != null) pts.put(Math.round(pt.optDouble("lon") * 1e5) / 1e5).put(Math.round(pt.optDouble("lat") * 1e5) / 1e5);
                    }
                    int kv = 0;
                    for (String v : t.optString("voltage").split(";")) { try { kv = Math.max(kv, Integer.parseInt(v.trim()) / 1000); } catch (Exception ignored) { } }
                    out.put(new JSONObject().put("kv", kv).put("p", t.optString("power")).put("n", t.optString("name", t.optString("ref", "")))
                            .put("o", t.optString("operator")).put("r", t.optString("ref")).put("c", t.optString("cables")).put("f", t.optString("frequency"))
                            .put("ci", t.optString("circuits")).put("loc", t.optString("location")).put("g", pts));
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Hindernisse aus OSM: {pts:[{t,h,hub,rot,n,lat,lon}], rails:[{e,n,g}]} – t = wind|mast|tower|chimney */
        @JavascriptInterface
        public String osmObstacles() {
            JSONArray pts = new JSONArray(), rails = new JSONArray(), places = new JSONArray(), ways = new JSONArray();
            try {
                if (osmFile().isFile()) {
                    JSONArray els = new JSONArray(readFile(osmFile()));
                    for (int i = 0; i < els.length(); i++) {
                        JSONObject el = els.getJSONObject(i), t = el.optJSONObject("tags");
                        if (t == null) continue;
                        String ty = el.optString("type");
                        if (t.has("place") && "node".equals(ty)) {
                            places.put(new JSONObject().put("n", t.optString("name")).put("t", t.optString("place"))
                                    .put("lat", el.optDouble("lat")).put("lon", el.optDouble("lon")));
                            continue;
                        }
                        if (t.has("highway")) continue;   // Wege nur auf Anfrage (osmWays) – sonst zu viel auf einmal
                        if (t.has("railway") && "way".equals(ty)) {
                            JSONArray g = el.optJSONArray("geometry"), ln = new JSONArray();
                            if (g == null) continue;
                            for (int k = 0; k < g.length(); k++) { JSONObject p = g.optJSONObject(k); if (p != null) ln.put(Math.round(p.optDouble("lon") * 1e5) / 1e5).put(Math.round(p.optDouble("lat") * 1e5) / 1e5); }
                            String elc = t.optString("electrified");
                            rails.put(new JSONObject().put("e", elc.equals("contact_line") || elc.equals("yes")).put("n", t.optString("name", t.optString("ref", ""))).put("g", ln));
                            continue;
                        }
                        String pw = t.optString("power");
                        if (pw.matches("tower|portal|pole") && "node".equals(ty)) {   // Strommast: nur wenn in OSM eine echte Höhe eingetragen ist
                            double mh = num(t.optString("height"));
                            if (mh > 0) pts.put(new JSONObject().put("t", "pmast").put("h", mh).put("n", t.optString("ref", "")).put("op", t.optString("operator", ""))
                                    .put("lat", el.optDouble("lat")).put("lon", el.optDouble("lon")));
                            continue;
                        }
                        boolean wind = "generator".equals(t.optString("power")) && "wind".equals(t.optString("generator:source"));
                        String mm = t.optString("man_made");
                        if (!wind && mm.isEmpty()) continue;
                        double lat = el.optDouble("lat", Double.NaN), lon = el.optDouble("lon", Double.NaN);
                        JSONArray g = el.optJSONArray("geometry");
                        if (Double.isNaN(lat) && g != null && g.length() > 0) {   // als Fläche kartiert: Mittelpunkt
                            double a2 = 0, b2 = 0; for (int k = 0; k < g.length(); k++) { a2 += g.getJSONObject(k).optDouble("lat"); b2 += g.getJSONObject(k).optDouble("lon"); }
                            lat = a2 / g.length(); lon = b2 / g.length();
                        }
                        if (Double.isNaN(lat)) continue;
                        pts.put(new JSONObject().put("t", wind ? "wind" : mm.replace("communications_tower", "tower"))
                                .put("h", num(t.optString("height"))).put("hub", num(t.optString("generator:hub_height", t.optString("hub_height"))))
                                .put("rot", num(t.optString("rotor:diameter", t.optString("generator:rotor_diameter"))))
                                .put("n", t.optString("name", t.optString("operator", ""))).put("lat", lat).put("lon", lon));
                    }
                }
            } catch (Exception ignored) { }
            try { return new JSONObject().put("pts", pts).put("rails", rails).put("places", places).toString(); } catch (Exception e) { return "{}"; }
        }

        /** Gespeicherte Orts- und Flurnamen [{n,t,lat,lon}] */
        @JavascriptInterface
        public String places() {
            try { return placeFile().isFile() ? readFile(placeFile()) : "[]"; } catch (Exception e) { return "[]"; }
        }

        /** Gespeicherte Grenzen der Bundesländer / RB Gießen im Gebiet */
        @JavascriptInterface
        public String regions() {
            try {
                return new JSONObject().put("fetched", prefs.getLong("reg_fetched", 0))
                        .put("list", regionFile().isFile() ? new JSONArray(readFile(regionFile())) : new JSONArray()).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public String notams() {
            try {
                File f = notamFile();
                return new JSONObject().put("fetched", prefs.getLong("notam_fetched", 0)).put("err", prefs.getString("notam_err", ""))
                        .put("data", f.isFile() ? new JSONObject(readFile(f)) : new JSONObject()).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void notamSync(boolean force) {
            new Thread(() -> {
                try {
                    notamSyncImpl(55.1, 47.2, 15.1, 5.8, force);   // immer ganz Deutschland, unabhängig von Feldern/Geozonen
                } catch (Exception ignored) { }
                js("window.onNotams && window.onNotams()");
            }).start();
        }


        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> {
                try { if (url != null && url.startsWith("http")) openInApp(url); else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { js("window.onGeoInfo && window.onGeoInfo(" + JSONObject.quote("{\"ok\":false,\"error\":\"Kein Browser zum Öffnen gefunden\"}") + ")"); }
            });
        }

        /** Gespeicherte Geozonen als {ebene:[features...]} */
        @JavascriptInterface
        public String geoVectorLayer(String layer) {   // eine Zonenart – die App liest Schicht für Schicht (kein langes Einfrieren)
            try { File f = geoVecFile(layer); return f.isFile() ? readFile(f).replaceAll("(\\.\\d{5})\\d+", "$1") : ""; } catch (Exception e) { return ""; }
        }

        /** Wege (OSM) nur im angefragten Gebiet {n,s,e,w} – für den Standort im Einsatzplan */
        @JavascriptInterface
        public String osmWays(String boxJson) {
            JSONArray out = new JSONArray();
            try {
                JSONObject b = new JSONObject(boxJson);
                double n = b.getDouble("n"), so = b.getDouble("s"), e = b.getDouble("e"), w = b.getDouble("w");
                if (!osmWaysFile().isFile()) return "[]";
                JSONArray els = new JSONArray(readFile(osmWaysFile()));
                for (int i = 0; i < els.length(); i++) {
                    JSONObject el = els.getJSONObject(i), t = el.optJSONObject("tags");
                    if (t == null || !t.has("highway") || !"way".equals(el.optString("type"))) continue;
                    JSONArray g = el.optJSONArray("geometry"); if (g == null) continue;
                    boolean in = false; JSONArray ln = new JSONArray();
                    for (int k = 0; k < g.length(); k++) { JSONObject pt = g.optJSONObject(k); if (pt == null) continue;
                        double la = pt.optDouble("lat"), lo = pt.optDouble("lon");
                        if (la <= n && la >= so && lo <= e && lo >= w) in = true;
                        ln.put(Math.round(lo * 1e5) / 1e5).put(Math.round(la * 1e5) / 1e5); }
                    if (in) out.put(new JSONObject().put("k", t.optString("highway")).put("g", ln));
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        @JavascriptInterface
        public String geoVectors() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String l : GEO_LAYERS) {
                File f = geoVecFile(l);
                if (!f.isFile()) continue;
                try {
                    String t = readFile(f);
                    sb.append(first ? "" : ",").append(JSONObject.quote(l)).append(":").append(t);
                    first = false;
                } catch (Exception ignored) { }
            }
            return sb.append("}").toString();
        }

        /** areaJson: {n,s,e,w} Gesamtgebiet. force = alles neu laden */
        @JavascriptInterface
        public void geoSync(String areaJson, boolean force) {
            new Thread(() -> geoSyncImpl(areaJson, force)).start();
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        public void locate() {
            runOnUiThread(() -> locateImpl());
        }
    }

    // =====================================================================
    // UAV Editor: Anmeldung, Feldliste, Export
    // =====================================================================

    private static final String UAVE_API = "https://api.app.uaveditor.com/graphql";
    private static final String UAVE_SERVICES = "https://services.app.uaveditor.com";
    private String uaveToken;
    private String[] uaveAuthHeader;                       // funktionierende Header-Variante
    private final Map<String, JSONObject> uaveFields = new HashMap<>();
    private volatile boolean uaveBusy = false;
    private volatile boolean uaveCancel = false;   // vom Abbrechen-Knopf gesetzt, wird zwischen zwei Feldern geprüft

    private void uaveEvent(JSONObject ev) {
        js("window.onUave && window.onUave(" + JSONObject.quote(ev.toString()) + ")");
    }

    private void uaveError(String msg) {
        try { uaveEvent(new JSONObject().put("type", "error").put("message", msg)); } catch (Exception ignored) { }
    }

    private void uaveProgress(String text) {
        try { uaveEvent(new JSONObject().put("type", "status").put("text", text)); } catch (Exception ignored) { }
    }

    // ---------- Passwort verschlüsselt im Android-Keystore ----------
    private SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("uave")) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("uave",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry("uave", null)).getSecretKey();
    }

    // ---------- Zugang UAV Editor zusätzlich im Ordner „Kitzrettung Cache“ sichern – übersteht Neuinstallation/App-Wechsel ----------
    // Verschlüsselt (AES-GCM) mit einem festen App-Schlüssel: schützt vor Mitlesen im Dateimanager, ist aber kein Tresor –
    // wer die App zerlegt, könnte ihn ermitteln. Der eigentliche Speicher bleibt der Android-Keystore.
    // ---------- Einstellungen zusätzlich als Datei in „Kitzrettung Cache“ – übersteht Neuinstallation ----------
    private File prefsBackupFile() { return new File(hasFileAccess() ? extDataDir() : dataDir(), "Sonstiges/einstellungen.json"); }
    private static final java.util.Set<String> PREFS_SKIP = new java.util.HashSet<>(Arrays.asList("uave_pw", "uave_restored", "prefs_restored", "geoTapCache"));
    private final android.os.Handler prefsH = new android.os.Handler(Looper.getMainLooper());
    private final Runnable prefsSaveR = () -> new Thread(this::prefsBackupNow).start();
    private void prefsBackupLater() { prefsH.removeCallbacks(prefsSaveR); prefsH.postDelayed(prefsSaveR, 2500); }
    private synchronized void prefsBackupNow() {
        try {
            if (!hasFileAccess()) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                Object v = e.getValue(); if (PREFS_SKIP.contains(e.getKey()) || v == null) continue;
                if (v instanceof String && ((String) v).length() > 20000) continue;   // große Zwischenspeicher nicht mitnehmen
                JSONObject t = new JSONObject();
                if (v instanceof Boolean) t.put("b", v); else if (v instanceof Long) t.put("l", v); else if (v instanceof Integer) t.put("i", v);
                else if (v instanceof Float) t.put("f", ((Float) v).doubleValue()); else if (v instanceof String) t.put("s", v); else continue;
                o.put(e.getKey(), t);
            }
            writeFileAtomic(prefsBackupFile(), new JSONObject().put("t", System.currentTimeMillis()).put("app", appVer()).put("prefs", o).toString());
        } catch (Exception ignored) { }
    }
    /** Nach Neuinstallation: Einstellungen aus der Datei zurückholen (nur fehlende Werte). true = etwas übernommen */
    private boolean prefsRestore() {
        try {
            if (!hasFileAccess() || prefs.getBoolean("prefs_restored", false)) return false;
            File f = prefsBackupFile();
            if (!f.isFile()) { prefs.edit().putBoolean("prefs_restored", true).apply(); return false; }
            JSONObject o = new JSONObject(readFile(f)).getJSONObject("prefs");
            SharedPreferences.Editor ed = prefs.edit(); int n = 0;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next(); if (prefs.contains(k) || PREFS_SKIP.contains(k)) continue;
                JSONObject t = o.getJSONObject(k);
                if (t.has("b")) ed.putBoolean(k, t.getBoolean("b")); else if (t.has("l")) ed.putLong(k, t.getLong("l"));
                else if (t.has("i")) ed.putInt(k, t.getInt("i")); else if (t.has("f")) ed.putFloat(k, (float) t.getDouble("f")); else if (t.has("s")) ed.putString(k, t.getString("s"));
                n++;
            }
            ed.putBoolean("prefs_restored", true).commit();
            dataDirCache = null;
            return n > 0;
        } catch (Exception e) { return false; }
    }

    private File uaveBackupFile() { return new File(hasFileAccess() ? extDataDir() : dataDir(), "Sonstiges/.uave-zugang"); }
    private static javax.crypto.spec.SecretKeySpec backupKey() throws Exception {
        byte[] k = java.security.MessageDigest.getInstance("SHA-256").digest("MissionKitzrettung|UAV-Editor-Zugang|v1|Kitzrettung-Limburg".getBytes(StandardCharsets.UTF_8));
        return new javax.crypto.spec.SecretKeySpec(k, "AES");
    }
    private void uaveBackupSave(String user, String password) {
        try {
            String pw = password;
            if (pw == null || pw.isEmpty()) { String st = prefs.getString("uave_pw", null); if (st != null) pw = decrypt(st); }
            if (user == null || user.trim().isEmpty() || pw == null || pw.isEmpty()) return;
            byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, backupKey(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(new JSONObject().put("u", user.trim()).put("p", pw).toString().getBytes(StandardCharsets.UTF_8));
            writeFileAtomic(uaveBackupFile(), Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP));
        } catch (Exception ignored) { }
    }
    /** Beim Start: kein Zugang in der App, aber Sicherung im Cache-Ordner → übernehmen */
    private void uaveBackupRestore() {
        try {
            if (prefs.contains("uave_pw") && !prefs.getString("uave_user", "").isEmpty()) { if (!uaveBackupFile().isFile()) uaveBackupSave(prefs.getString("uave_user", ""), null); return; }
            File f = uaveBackupFile(); if (!f.isFile()) return;
            String[] p = readFile(f).trim().split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, backupKey(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            JSONObject o = new JSONObject(new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            prefs.edit().putString("uave_user", o.getString("u")).putString("uave_pw", encrypt(o.getString("p"))).putString("uave_restored", "1").commit();
        } catch (Exception ignored) { }
    }

    private String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private String decrypt(String stored) throws Exception {
        String[] p = stored.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    // ---------- HTTP ----------
    private static class Resp {
        int code; byte[] body; String disposition; String contentType;
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    private Resp http(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Origin", "https://app.uaveditor.com");
        c.setRequestProperty("Referer", "https://app.uaveditor.com/");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KMZMissionen/1.1");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        r.body = bos.toByteArray();
        r.disposition = c.getHeaderField("Content-Disposition");
        r.contentType = c.getHeaderField("Content-Type");
        c.disconnect();
        return r;
    }

    private JSONObject gql(String query, JSONObject variables, String[] authHeader) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("app", "uav");
        h.put("lang", "de");
        h.put("x-timezone", TimeZone.getDefault().getID());
        if (authHeader != null) h.put(authHeader[0], authHeader[1]);
        JSONObject body = new JSONObject().put("query", query).put("variables", variables);
        Resp r = http("POST", UAVE_API, h, body.toString().getBytes(StandardCharsets.UTF_8));
        if (r.code >= 400) throw new Exception("UAV Editor antwortet mit Fehler " + r.code);
        return new JSONObject(r.text());
    }

    private static String gqlError(JSONObject res) {
        JSONArray e = res.optJSONArray("errors");
        if (e == null || e.length() == 0) return null;
        return e.optJSONObject(0) != null ? e.optJSONObject(0).optString("message", "Unbekannter Fehler") : "Unbekannter Fehler";
    }

    private static boolean gqlAuthenticated(JSONObject res) {
        JSONArray ext = res.optJSONArray("extensions");
        if (ext != null && ext.length() > 0 && ext.optJSONObject(0) != null) return ext.optJSONObject(0).has("auth");
        JSONObject ex = res.optJSONObject("extensions");
        return ex != null && ex.has("auth");
    }

    private void uaveLogin() throws Exception {
        String user = prefs.getString("uave_user", "");
        String stored = prefs.getString("uave_pw", null);
        if (user.isEmpty() || stored == null) throw new Exception("Bitte zuerst UAV-Editor-Zugangsdaten in den Einstellungen eintragen.");
        String pw = decrypt(stored);
        JSONObject res = gql("query login($app: String!, $login: String!, $password: String!) { auth(app: $app, login: $login, password: $password) { token id } }",
                new JSONObject().put("app", "uav").put("login", user).put("password", pw), null);
        String err = gqlError(res);
        JSONObject auth = res.optJSONObject("data") != null ? res.optJSONObject("data").optJSONObject("auth") : null;
        if (err != null || auth == null || auth.optString("token", "").isEmpty())
            throw new Exception("Anmeldung bei UAV Editor fehlgeschlagen" + (err != null ? ": " + err : ""));
        uaveToken = auth.getString("token");
    }

    private static final String FIELDS_QUERY =
            "query getFields($archive: Boolean, $removed: Boolean, $limit: Int!, $offset: Int!) { "
            + "getFields(archive: $archive, removed: $removed, limit: $limit, offset: $offset) { "
            + "count fields { id name copterProfile lastModified createdAt area flightTime orderData { cuttingDate } } } }";

    private JSONArray uaveFetchFields(boolean archive) throws Exception {
        JSONObject vars = new JSONObject().put("archive", archive).put("removed", false).put("limit", 0).put("offset", 0);
        // Header-Variante für die Anmeldung beim ersten Aufruf ermitteln
        List<String[]> variants = new ArrayList<>();
        if (uaveAuthHeader != null) variants.add(uaveAuthHeader);
        variants.add(new String[]{"login", uaveToken});
        variants.add(new String[]{"authorization", uaveToken});
        variants.add(new String[]{"authorization", "Bearer " + uaveToken});
        String lastErr = null;
        for (String[] v : variants) {
            JSONObject res = gql(FIELDS_QUERY, vars, v);
            String err = gqlError(res);
            JSONObject data = res.optJSONObject("data");
            JSONObject gf = data != null ? data.optJSONObject("getFields") : null;
            if (err == null && gf != null && gqlAuthenticated(res)) {
                uaveAuthHeader = v;
                return gf.optJSONArray("fields") != null ? gf.optJSONArray("fields") : new JSONArray();
            }
            lastErr = err;
        }
        throw new Exception("Feldliste konnte nicht geladen werden" + (lastErr != null ? ": " + lastErr : ""));
    }

    // ---------- Feldgrenzen (Kontur) aus UAV Editor – Feature mit properties.type == 1 im geojson des Feldes ----------
    private File outlineFile() { return dataFile("field_outlines.json"); }
    /** Feldgrenzen aus den JSON-Beigaben des UAV-Editor-Exports, je KMZ-Datei: {datei.kmz: [lon,lat,…]} */
    private File outlineFileByKmz() { return dataFile("field_outlines_kmz.json"); }

    /** Aus der Export-JSON nur die Feldgrenze lesen (Feature mit properties.type == 1); persönliche Angaben werden nicht gespeichert */
    private static JSONArray outlineFromExportJson(byte[] json) {
        try {
            JSONObject d = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONObject gj = d.optJSONObject("geojson");
            JSONArray fs = gj != null ? gj.optJSONArray("features") : null;
            if (fs == null) return null;
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f == null) continue;
                JSONObject p = f.optJSONObject("properties"), g = f.optJSONObject("geometry");
                if (p == null || g == null || p.optInt("type", -1) != 1) continue;
                JSONArray c = g.optJSONArray("coordinates");
                if ("Polygon".equals(g.optString("type")) && c != null) c = c.optJSONArray(0);
                if (c == null || c.length() < 3) continue;
                JSONArray ring = new JSONArray();
                for (int k = 0; k < c.length(); k++) { JSONArray q = c.optJSONArray(k); if (q != null && q.length() >= 2) ring.put(q.getDouble(0)).put(q.getDouble(1)); }
                if (ring.length() >= 6) return ring;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** JSON-Beigaben eines Export-ZIPs auswerten: Feldgrenze je gleichnamiger KMZ speichern */
    private void saveExportOutlines(Map<String, byte[]> jsons, java.util.Collection<String> kmzNames) {
        if (jsons.isEmpty()) return;
        try {
            File of = outlineFileByKmz();
            JSONObject out = of.isFile() ? new JSONObject(readFile(of)) : new JSONObject();
            int n = 0;
            for (String kmz : kmzNames) {
                String base = kmz.replaceAll("(?i)\\.kmz$", "");
                byte[] j = jsons.get(base.toLowerCase(Locale.ROOT));
                if (j == null) continue;
                JSONArray ring = outlineFromExportJson(j);
                if (ring != null) { out.put(kmz, ring); n++; }
            }
            if (n > 0) writeFileAtomic(of, out.toString());
        } catch (Exception ignored) { }
    }

    /** Alle .json-Einträge eines ZIPs: {basisname klein: bytes} */
    private static Map<String, byte[]> zipJsons(byte[] zip) {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = new File(e.getName()).getName();
                if (e.isDirectory() || !n.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                m.put(safeFileName(n).replaceAll("(?i)\\.json$", "").toLowerCase(Locale.ROOT), readBytes(z));
            }
        } catch (Exception ignored) { }
        return m;
    }
    /** Kontur aus altem (nur Ring) oder neuem Format ({r: Ring, n: Name}) */
    private static JSONArray ringOf(Object v) {
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof JSONObject) return ((JSONObject) v).optJSONArray("r");
        return null;
    }

    private final Map<String, JSONObject> uaveMapMeta = new HashMap<>();

    private void uaveFetchOutlines() throws Exception {
        if (uaveAuthHeader == null) return;
        JSONObject out = outlineFile().isFile() ? new JSONObject(readFile(outlineFile())) : new JSONObject();
        int got = 0;
        for (boolean archived : new boolean[]{true, false}) {
            JSONObject vars = new JSONObject().put("fieldsFilter", new JSONObject().put("archived", archived).put("removed", false));
            JSONObject res = gql("query getFieldsMapData($planningId: String, $fieldsFilter: FieldFilterInput, $pilotCoordinationId: String, $pilotGroupId: String, $useFieldCatalogFields: Boolean) { "
                    + "getFieldsMapData(useFieldCatalogFields: $useFieldCatalogFields pilotGroupId: $pilotGroupId planningId: $planningId fieldsFilter: $fieldsFilter pilotCoordinationId: $pilotCoordinationId) }", vars, uaveAuthHeader);
            if (gqlError(res) != null) continue;
            JSONObject data = res.optJSONObject("data");
            JSONObject md = data != null ? data.optJSONObject("getFieldsMapData") : null;
            JSONArray fs = md != null ? md.optJSONArray("fields") : null;
            if (fs == null) continue;
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                String id = f.optString("_id", f.optString("id", ""));
                try {   // Zusatzinfos für den Auswahl-Dialog: Drohnentyp und Planungsjahr(e)
                    JSONObject ms = f.optJSONObject("missionSettings"), p2 = ms != null ? ms.optJSONObject("djiPilot2") : null;
                    JSONArray yrs = new JSONArray(), pi = f.optJSONArray("planningInfos");
                    if (pi != null) for (int k = 0; k < pi.length(); k++) { JSONObject p = pi.optJSONObject(k); if (p != null && !p.optString("planningName").isEmpty()) yrs.put(p.optString("planningName")); }
                    JSONObject cb = f.optJSONObject("contributor"), asl = f.optJSONObject("aboveSeaLevel"), fc = f.optJSONObject("featureCount");
                    String by = cb != null ? (cb.optString("firstname", "") + " " + cb.optString("name", "")).trim() : "";
                    JSONObject mm = new JSONObject().put("dt", p2 != null ? p2.optString("droneType", "") : "")
                            .put("h", p2 != null ? p2.optDouble("height", 0) : 0).put("years", yrs).put("created", f.optString("createdAt", ""))
                            .put("by", by).put("asl", asl != null ? asl.optDouble("value", 0) : 0).put("wp", fc != null ? fc.optInt("waypoints", 0) : 0);
                    JSONArray ctr = f.optJSONArray("center");
                    if (ctr != null && ctr.length() == 2) mm.put("c", ctr);
                    String enc0 = f.optString("polygonLineString", "");
                    if (!enc0.isEmpty()) {   // Kontur für die Kartenvorschau im Auswahl-Dialog
                        JSONArray r = new JSONArray();
                        List<double[]> op = decodePolyline(enc0);
                        boolean latFirst = !op.isEmpty() && Math.abs(op.get(0)[0]) > Math.abs(op.get(0)[1]);
                        for (double[] p : op) { if (latFirst) r.put(p[1]).put(p[0]); else r.put(p[0]).put(p[1]); }
                        mm.put("r", r);
                    }
                    String ls = f.optString("lineString", "");
                    if (!ls.isEmpty()) {   // Flugroute (vereinfacht) für die Vorschau
                        JSONArray r = new JSONArray();
                        List<double[]> pts = decodePolyline(ls);
                        int step = Math.max(1, pts.size() / 120);
                        boolean latFirst = !pts.isEmpty() && Math.abs(pts.get(0)[0]) > Math.abs(pts.get(0)[1]);   // Reihenfolge erkennen (in DE: Breite > Länge)
                        for (int k = 0; k < pts.size(); k += step) { double[] p = pts.get(k); if (latFirst) r.put(p[1]).put(p[0]); else r.put(p[0]).put(p[1]); }
                        mm.put("route", r);
                    }
                    if (!id.isEmpty()) uaveMapMeta.put(id, mm);
                } catch (Exception ignored) { }
                String enc = f.optString("polygonLineString", "");
                if (id.isEmpty() || enc.isEmpty()) continue;
                List<double[]> pts = decodePolyline(enc);   // Feldgrenze, Reihenfolge Breite/Länge
                if (pts.size() < 3) continue;
                JSONArray ring = new JSONArray();
                for (double[] p : pts) ring.put(p[1]).put(p[0]);   // gespeichert als Länge, Breite
                out.put(id, new JSONObject().put("r", ring).put("n", f.optString("name", ""))); got++;
            }
        }
        if (got > 0) writeFileAtomic(outlineFile(), out.toString());
    }

    /** Google-Polyline (Genauigkeit 1e-5) dekodieren */
    private static List<double[]> decodePolyline(String s) {
        List<double[]> out = new ArrayList<>();
        int i = 0, a = 0, b = 0;
        while (i < s.length()) {
            int[] v = new int[2];
            for (int k = 0; k < 2; k++) {
                int r = 0, sh = 0, c;
                do { if (i >= s.length()) return out; c = s.charAt(i++) - 63; r |= (c & 31) << sh; sh += 5; } while (c >= 32);
                v[k] = (r & 1) != 0 ? ~(r >> 1) : (r >> 1);
            }
            a += v[0]; b += v[1];
            out.add(new double[]{a / 1e5, b / 1e5});
        }
        return out;
    }

    private static long parseIso(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        for (String f : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.ROOT);
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                return df.parse(s).getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    /** Mögliche Dateinamen-Anfänge, wie UAV Editor sie beim Export bildet (z. B. "Sobotta-348-_") */
    private static List<String> namePrefixes(String name) {
        List<String> out = new ArrayList<>();
        String t = name.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae")
                .replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        // UAV Editor entfernt Umlaut-Punkte im Dateinamen (Jörg -> Jorg), behält Leerzeichen und schreibt teils klein
        String plain = java.text.Normalizer.normalize(name.replace("ß", "ss"), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        out.add(safeFileName(plain).toLowerCase(Locale.ROOT) + "_");
        out.add(safeFileName(name).toLowerCase(Locale.ROOT) + "_");
        for (String base : new String[]{name, t, plain}) {
            out.add(base.replaceAll("[^A-Za-z0-9_\\-]", "").toLowerCase(Locale.ROOT) + "_");
            out.add(base.replace(' ', '_').replaceAll("[^A-Za-z0-9_\\-]", "").toLowerCase(Locale.ROOT) + "_");
            out.add(base.replace(' ', '-').replaceAll("[^A-Za-z0-9_\\-]", "").toLowerCase(Locale.ROOT) + "_");
        }
        return out;
    }

    /** Mähdatum und Flugzeit eines Feldes in einen Manifest-Eintrag übernehmen */
    private static JSONObject withMeta(JSONObject entry, JSONObject field) throws Exception {
        if (field == null) return entry;
        JSONObject od = field.optJSONObject("orderData");
        String cut = od != null && !od.isNull("cuttingDate") ? od.optString("cuttingDate", "") : "";
        entry.put("cut", cut);
        entry.put("ft", field.optDouble("flightTime", 0));
        entry.put("name", field.optString("name", ""));
        return entry;
    }

    private JSONObject manifest() {
        try { return new JSONObject(prefs.getString("uave_manifest", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void uaveCheckImpl(String srcDir) {
        if (uaveBusy) return;
        uaveBusy = true;
        uaveCancel = false;
        try {
            uaveProgress("Anmeldung bei UAV Editor…");
            uaveLogin();
            uaveProgress("Lade Feldliste…");
            uaveFields.clear();
            for (boolean archive : new boolean[]{true, false}) {
                JSONArray arr;
                try {
                    arr = uaveFetchFields(archive);
                } catch (Exception e) {
                    if (archive) throw e;      // Archiv ist Pflicht, aktive Felder optional
                    continue;
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.getJSONObject(i);
                    if (!"dji-pilot-2".equals(f.optString("copterProfile"))) continue;
                    f.put("_arch", archive);   // für die Anzeige im Auswahl-Dialog (Archiv / aktiv)
                    uaveFields.put(f.getString("id"), f);
                }
            }
            if (!uaveCancel) {
                uaveProgress("Lade Feldgrenzen…");
                try { uaveFetchOutlines(); } catch (Exception ignored) { /* optional – Abgleich läuft trotzdem */ }
            }
            File dir = new File(srcDir);
            File[] local = dir.listFiles();
            JSONObject man = manifest();
            JSONArray result = new JSONArray();
            int missing = 0, changed = 0;
            for (JSONObject f : uaveFields.values()) {
                String id = f.getString("id");
                String name = f.optString("name", id);
                long mod = Math.max(parseIso(f.optString("lastModified", null)), parseIso(f.optString("createdAt", null)));
                String status = "missing";
                File found = null;
                JSONObject m = man.optJSONObject(id);
                if (m != null) {
                    File mf = new File(dir, m.optString("file"));
                    // geändert nur, wenn der UAV Editor einen neueren Stand hat als beim letzten Download (1 min Toleranz)
                    long known = m.has("srv") ? m.optLong("srv", 0) : m.optLong("mod", 0);
                    if (mf.isFile()) { found = mf; status = mod > known + 60000 ? "changed" : "ok"; withMeta(m, f); }
                }
                if (found == null && local != null) {
                    List<String> pre = namePrefixes(name);
                    for (File lf : local) {
                        String ln = lf.getName().toLowerCase(Locale.ROOT);
                        if (!lf.isFile() || !ln.endsWith(".kmz")) continue;
                        for (String p : pre) if (p.length() > 1 && ln.startsWith(p)) { found = lf; break; }
                        if (found != null) break;
                    }
                    if (found != null) {
                        // Datei liegt schon da (z. B. von Hand kopiert): als aktuell übernehmen, nicht neu laden
                        status = "ok";
                        man.put(id, withMeta(new JSONObject().put("file", found.getName()).put("mod", Math.max(found.lastModified(), mod)).put("srv", mod), f));
                    }
                }
                if ("missing".equals(status)) missing++;
                if ("changed".equals(status)) changed++;
                result.put(new JSONObject().put("id", id).put("name", name).put("status", status)
                        .put("file", found != null ? found.getName() : JSONObject.NULL)
                        .put("arch", f.optBoolean("_arch")).put("area", f.optDouble("area", 0)).put("mod", mod)
                        .put("cut", f.optJSONObject("orderData") != null ? f.optJSONObject("orderData").optString("cuttingDate", "") : "")
                        .put("ft", f.optDouble("flightTime", 0))
                        .put("lm", f.optString("lastModified", ""))
                        .put("created", f.optString("createdAt", ""))
                        .put("meta", uaveMapMeta.containsKey(id) ? uaveMapMeta.get(id) : JSONObject.NULL));
            }
            prefs.edit().putString("uave_manifest", man.toString()).apply();
            // Feldliste dauerhaft merken: die Karte zeigt sie (gemischte Ansicht) auch offline
            try { writeFileAtomic(dataFile("uave_katalog.json"), new JSONObject().put("t", System.currentTimeMillis()).put("fields", result).toString()); } catch (Exception ignored) { }
            uaveEvent(new JSONObject().put("type", "list").put("fields", result)
                    .put("total", uaveFields.size()).put("missing", missing).put("changed", changed));
        } catch (Exception e) {
            uaveError(friendlyError(e));
        } finally {
            uaveBusy = false;
        }
    }

    private static String formEncode(Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String safeFileName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return n.isEmpty() ? "Feld" : n;
    }

    private static boolean zipHas(byte[] data, String suffix) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Export eines Feldes anstoßen, auf Fertigstellung warten und KMZ speichern. Gibt den Dateinamen zurück. */
    /** Startet einen Export in UAV Editor, wartet bis fertig und lädt das Ergebnis (KMZ oder ZIP). */
    private Object[] uaveExportRaw(JSONArray fieldIds, String tzOffset, long maxWaitMs) throws Exception {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("app", "uav");
        form.put("token", uaveToken);
        form.put("archiveName", "Export-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()));
        form.put("forceArchive", "");
        form.put("pilotCoordinationId", "");
        form.put("cuttingDay", "");
        form.put("appType", "rehkitz");
        form.put("useDownloadPage", "");
        form.put("timezoneOffset", tzOffset == null || tzOffset.isEmpty() ? "-120" : tzOffset);
        form.put("language", "de");
        form.put("i18n_debug_mode", "");
        JSONArray items = new JSONArray().put(new JSONObject()
                .put("type", "fields")
                .put("customFieldLabelSettings", new JSONObject())
                .put("fieldIds", fieldIds)
                .put("outputs", new JSONArray().put(new JSONObject().put("format", "kml-pilot-2"))));
        form.put("items", items.toString());

        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Resp r = http("POST", UAVE_SERVICES + "/export", h, formEncode(form).getBytes(StandardCharsets.UTF_8));
        if (r.code >= 400) throw new Exception("Export abgelehnt (" + r.code + ")");
        Matcher m = Pattern.compile("exportStateId\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(r.text());
        if (!m.find()) throw new Exception("Export-Auftrag nicht erkannt");
        String stateId = m.group(1);

        // Status abfragen, bis fertig
        JSONObject state = null;
        long until = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < until) {
            if (uaveCancel) throw new Exception("abgebrochen");
            Thread.sleep(800);
            Resp s = http("GET", UAVE_SERVICES + "/export-state/" + stateId + "?t=" + System.currentTimeMillis(), null, null);
            if (s.code >= 400) continue;
            state = new JSONObject(s.text());
            String st = state.optString("status");
            if ("ready".equals(st)) break;
            if ("error".equals(st)) throw new Exception("Export fehlgeschlagen: " + state.optString("message"));
        }
        if (state == null || !"ready".equals(state.optString("status"))) throw new Exception("Export dauert zu lange");

        String dl = state.optString("downloadUrl", "/download-export/" + stateId);
        Resp d = http("GET", dl.startsWith("http") ? dl : UAVE_SERVICES + dl, null, null);
        if (d.code >= 400 || d.body.length == 0) throw new Exception("Download fehlgeschlagen (" + d.code + ")");

        return new Object[]{d, state};
    }

    private String uaveExportOne(String fieldId, String name, File dir, String tzOffset) throws Exception {
        Object[] raw = uaveExportRaw(new JSONArray().put(fieldId), tzOffset, 180000);
        Resp d = (Resp) raw[0];
        JSONObject state = (JSONObject) raw[1];

        // Dateiname: Server-Vorgabe, sonst Feldname
        String fname = null;
        if (d.disposition != null) {
            Matcher fm = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").matcher(d.disposition);
            if (fm.find()) fname = java.net.URLDecoder.decode(fm.group(1), "UTF-8");
        }
        JSONObject result = state.optJSONObject("result");
        if (fname == null && result != null && !result.isNull("filename")) fname = result.optString("filename", null);
        if (fname == null || fname.isEmpty()) fname = name + ".kmz";
        fname = safeFileName(new File(fname).getName());

        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Quellordner kann nicht angelegt werden");

        // Falls der Server ein ZIP mit KMZ-Dateien liefert: KMZ daraus entpacken
        if (!zipHas(d.body, "template.kml") && zipHas(d.body, ".kmz")) {
            String first = null;
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(d.body))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".kmz")) continue;
                    String n = safeFileName(new File(e.getName()).getName());
                    try (OutputStream o = new FileOutputStream(new File(dir, n))) {
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = z.read(buf)) > 0) o.write(buf, 0, k);
                    }
                    if (first == null) first = n;
                }
            }
            if (first == null) throw new Exception("Leeres Export-Archiv");
            saveExportOutlines(zipJsons(d.body), java.util.Collections.singletonList(first));
            return first;
        }
        if (!fname.toLowerCase(Locale.ROOT).endsWith(".kmz")) fname = fname.replaceAll("\\.[A-Za-z0-9]{1,4}$", "") + ".kmz";
        try (OutputStream o = new FileOutputStream(new File(dir, fname))) { o.write(d.body); }
        return fname;
    }

    /** Ab dieser Anzahl werden Felder als ZIP-Pakete exportiert (schneller), darunter einzeln */
    private static final int ZIP_FROM = 11, ZIP_BATCH = 25;

    /** Ein ZIP-Paket exportieren und entpacken. Liefert {feldId: dateiname} für alle zuordenbaren Felder. */
    private Map<String, String> uaveExportZip(List<String> ids, File dir, String tzOffset) throws Exception {
        JSONArray arr = new JSONArray();
        for (String id : ids) arr.put(id);
        Object[] raw = uaveExportRaw(arr, tzOffset, 120000 + 6000L * ids.size());
        Resp d = (Resp) raw[0];
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Quellordner kann nicht angelegt werden");
        // KMZ-Einträge aus dem ZIP lesen (JSON-Beigaben werden ignoriert)
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(d.body))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".kmz")) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536]; int k;
                while ((k = z.read(buf)) > 0) bo.write(buf, 0, k);
                entries.put(safeFileName(new File(e.getName()).getName()), bo.toByteArray());
            }
        }
        if (entries.isEmpty()) throw new Exception("Export-Archiv ohne KMZ");
        // Zuordnung über den Namen: Datei = "<Feldname>_<Fläche>ha_<n>WP.kmz". Längster passender Feldname gewinnt,
        // damit „Egenolf-3-“ und „Egenolf-3-M3T“ nicht verwechselt werden.
        List<String> order = new ArrayList<>(ids);
        java.util.Collections.sort(order, (a, b) -> nameOf(b).length() - nameOf(a).length());
        Map<String, String> out = new HashMap<>();
        Set<String> used = new java.util.HashSet<>();
        for (String id : order) {
            List<String> pres = namePrefixes(nameOf(id));
            String hit = null;
            for (String fn : entries.keySet()) {
                if (used.contains(fn)) continue;
                String l = fn.toLowerCase(Locale.ROOT);
                for (String pre : pres) if (pre.length() > 1 && (l.startsWith(pre) || l.equals(pre.substring(0, pre.length() - 1) + ".kmz"))) { hit = fn; break; }
                if (hit != null) break;
            }
            if (hit == null) continue;   // wird danach einzeln geladen
            used.add(hit);
            File tmp = new File(dir, hit + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(entries.get(hit)); }
            File f = new File(dir, hit);
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            out.put(id, hit);
        }
        saveExportOutlines(zipJsons(d.body), out.values());
        return out;
    }

    private String nameOf(String id) {
        JSONObject f = uaveFields.get(id);
        return f != null ? f.optString("name", id) : id;
    }

    /** Exportiert die Felder in den Ordner – ab 11 Feldern in ZIP-Paketen, sonst einzeln. Liefert {files:{id:datei}, ok, errors} */
    private JSONObject uaveExportLoop(JSONArray ids, File dir, String tzOffset) throws Exception {
        int ok = 0;
        JSONArray errors = new JSONArray();
        JSONObject files = new JSONObject();
        JSONObject man = manifest();
        int total = ids.length();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < total; i++) all.add(ids.getString(i));
        List<String> single = new ArrayList<>();
        int done = 0;
        if (total >= ZIP_FROM) {
            for (int b = 0; b < total && !uaveCancel; b += ZIP_BATCH) {
                List<String> part = all.subList(b, Math.min(total, b + ZIP_BATCH));
                uaveEvent(new JSONObject().put("type", "progress").put("done", done).put("total", total)
                        .put("name", "Paket " + (b / ZIP_BATCH + 1) + " von " + ((total + ZIP_BATCH - 1) / ZIP_BATCH) + " (ZIP, " + part.size() + " Felder)"));
                Map<String, String> got;
                try {
                    try { got = uaveExportZip(part, dir, tzOffset); }
                    catch (Exception first) { if (uaveCancel) throw first; uaveLogin(); got = uaveExportZip(part, dir, tzOffset); }
                } catch (Exception e) {
                    if (uaveCancel) break;
                    got = new HashMap<>();   // ganzes Paket fehlgeschlagen -> einzeln nachladen
                }
                for (String id : part) {
                    String file = got.get(id);
                    if (file == null) { single.add(id); continue; }
                    remember(man, id, file, dir);
                    files.put(id, file);
                    ok++; done++;
                }
            }
        } else single.addAll(all);
        for (String id : single) {
            if (uaveCancel) break;   // sauber zwischen zwei Feldern aufhören – fertige Dateien bleiben
            String name = nameOf(id);
            try {
                uaveEvent(new JSONObject().put("type", "progress").put("done", done).put("total", total).put("name", name));
                String file;
                try {
                    file = uaveExportOne(id, name, dir, tzOffset);
                } catch (Exception first) {
                    if (uaveCancel) break;
                    // Token evtl. abgelaufen: einmal neu anmelden und wiederholen
                    uaveLogin();
                    file = uaveExportOne(id, name, dir, tzOffset);
                }
                remember(man, id, file, dir);
                files.put(id, file);
                ok++;
            } catch (Exception e) {
                if (uaveCancel) break;
                errors.put(name + ": " + friendlyError(e));
            }
            done++;
        }
        return new JSONObject().put("ok", ok).put("errors", errors).put("files", files).put("cancelled", uaveCancel);
    }

    /** Manifest pflegen; alte Datei mit anderem Namen entfernen, damit keine Dubletten entstehen */
    private void remember(JSONObject man, String id, String file, File dir) throws Exception {
        JSONObject old = man.optJSONObject(id);
        if (old != null && !file.equals(old.optString("file"))) {
            File of = new File(dir, old.optString("file"));
            if (of.isFile()) of.delete();
        }
        // Stand merken: Änderungszeit laut UAV Editor (nicht nur die Uhr der RC – die kann falsch gehen, dann wäre jedes Feld immer „geändert“)
        JSONObject uf = uaveFields.get(id);
        long srv = uf != null ? Math.max(parseIso(uf.optString("lastModified", null)), parseIso(uf.optString("createdAt", null))) : 0;
        man.put(id, withMeta(new JSONObject().put("file", file).put("mod", Math.max(System.currentTimeMillis(), srv)).put("srv", srv), uf));
        prefs.edit().putString("uave_manifest", man.toString()).apply();
    }

    private void uaveDownloadImpl(String idsJson, String srcDir, String tzOffset) {
        if (uaveBusy) return;
        uaveBusy = true;
        uaveCancel = false;
        try {
            if (uaveToken == null) { uaveProgress("Anmeldung bei UAV Editor…"); uaveLogin(); }
            JSONObject r = uaveExportLoop(new JSONArray(idsJson), new File(srcDir), tzOffset);
            uaveEvent(r.put("type", "done"));
        } catch (Exception e) {
            uaveError(friendlyError(e));
        } finally {
            uaveBusy = false;
        }
    }

    private void uaveChangeDroneImpl(String idsJson, String droneType, String srcDir, String tzOffset) {
        if (uaveBusy) return;
        uaveBusy = true;
        uaveCancel = false;
        try {
            JSONArray ids = new JSONArray(idsJson);
            uaveProgress("Anmeldung bei UAV Editor…");
            uaveLogin();
            if (uaveAuthHeader == null || uaveFields.isEmpty()) {
                uaveProgress("Lade Feldliste…");
                JSONArray arr = uaveFetchFields(true);
                for (int i = 0; i < arr.length(); i++) uaveFields.put(arr.getJSONObject(i).getString("id"), arr.getJSONObject(i));
            }
            uaveProgress("Ändere Drohnentyp in UAV Editor…");
            JSONObject vars = new JSONObject().put("fieldIds", ids).put("settings", new JSONObject()
                    .put("missionSettings", new JSONObject().put("djiPilot2", new JSONObject().put("droneType", droneType))));
            JSONObject res = gql("mutation bulkPatchFieldSettings($fieldIds: [String]!, $settings: FieldSettingsInput!) { "
                    + "bulkPatchFieldSettings(fieldIds: $fieldIds, settings: $settings) { id } }", vars, uaveAuthHeader);
            String err = gqlError(res);
            if (err != null) throw new Exception("Drohnentyp konnte nicht geändert werden: " + err);
            JSONObject r = uaveExportLoop(ids, new File(srcDir), tzOffset);
            uaveEvent(r.put("type", "droneDone").put("droneType", droneType));
        } catch (Exception e) {
            uaveError(friendlyError(e));
        } finally {
            uaveBusy = false;
        }
    }

    // =====================================================================
    // Geozonen von dipul (WMS) – Kacheln für die Feldumgebung, lokal zwischengespeichert
    // Quelle Geodaten: DFS, BKG. Nutzung nur nicht-kommerziell / intern.
    // =====================================================================

    private static final String DIPUL_WMS = "https://uas-betrieb.de/geoservices/dipul/wms";
    private static final String DIPUL_WFS = "https://uas-betrieb.de/geoservices/dipul/wfs";
    // Alle Geozonen-Ebenen nach § 21h LuftVO (Vektordaten, dipul CC BY-ND 4.0)
    private static final String[] GEO_LAYERS = {
            "flughaefen", "flugplaetze", "kontrollzonen", "flugbeschraenkungsgebiete", "modellflugplaetze",
            "nationalparks", "naturschutzgebiete", "ffh-gebiete", "vogelschutzgebiete",
            "bundesautobahnen", "bundesstrassen", "bahnanlagen", "binnenwasserstrassen", "seewasserstrassen", "schifffahrtsanlagen",
            "stromleitungen", "windkraftanlagen", "umspannwerke", "kraftwerke", "industrieanlagen",
            "justizvollzugsanstalten", "militaerische_anlagen", "labore", "behoerden", "diplomatische_vertretungen",
            "internationale_organisationen", "polizei", "sicherheitsbehoerden", "krankenhaeuser", "freibaeder", "wohngrundstuecke"};
    private static final long DAY = 24L * 3600 * 1000;
    private volatile boolean geoRunning = false;
    private volatile String geoQueuedArea = null;
    private volatile boolean geoQueuedForce = false;
    private volatile String geoLastError = null;

    private File geoVecDir() { return isExt() ? new File(dataDir(), "Geozonen/Zonendaten") : new File(dataDir(), "dipul_vec"); }
    private File geoVecFile(String layer) { return new File(geoVecDir(), layer + ".json"); }

    /** Lädt eine Ebene als GeoJSON im Gebiet. null bei Fehler. */
    private JSONArray wfsFetch(String layer, double n, double s, double e, double w) {
        HttpURLConnection c = null;
        try {
            String url = DIPUL_WFS + "?service=WFS&version=1.1.0&request=GetFeature&typeName=dipul:" + layer
                    + "&outputFormat=application/json&srsName=EPSG:4326&maxFeatures=50000"
                    + "&bbox=" + String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", w, s, e, n) + ",EPSG:4326";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", "KMZMissionen/1.8 (Kitzrettung, nicht-kommerziell)");
            int code = c.getResponseCode();
            if (code != 200) { geoLastError = "dipul WFS antwortet mit " + code; return null; }
            String txt;
            try (InputStream in = c.getInputStream()) { txt = readAll(in); }
            if (!txt.trim().startsWith("{")) {
                geoLastError = txt.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (geoLastError.length() > 200) geoLastError = geoLastError.substring(0, 200);
                return null;
            }
            JSONArray f = new JSONObject(txt).optJSONArray("features");
            return f != null ? f : new JSONArray();
        } catch (Exception ex) {
            geoLastError = ex.getMessage();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void geoInfoImpl(double lat, double lon, String layersJson) {
        JSONObject res = new JSONObject();
        // 1. Wahl: WFS (Vektordaten mit Geometrie – derselbe Dienst wie für die gespeicherten Zonen); die App prüft Punkt-in-Fläche selbst
        try {
            JSONArray la = new JSONArray(layersJson);
            StringBuilder tn = new StringBuilder();
            for (int i = 0; i < la.length(); i++) tn.append(tn.length() > 0 ? "," : "").append("dipul:").append(la.getString(i));
            double d = 0.0006;
            String url = DIPUL_WFS + "?service=WFS&version=1.1.0&request=GetFeature&typeName=" + URLEncoder.encode(tn.toString(), "UTF-8")
                    + "&outputFormat=application/json&srsName=EPSG:4326&maxFeatures=200"
                    + "&bbox=" + String.format(Locale.ROOT, "%.7f,%.7f,%.7f,%.7f", lon - d, lat - d, lon + d, lat + d) + ",EPSG:4326";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
            if (c.getResponseCode() == 200) {
                String body; try (InputStream in = c.getInputStream()) { body = readAll(in); }
                c.disconnect();
                if (body.trim().startsWith("{")) {
                    JSONArray fs = new JSONObject(body).optJSONArray("features");
                    if (fs != null) {
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < fs.length(); i++) {
                            JSONObject f = fs.getJSONObject(i);
                            String id = f.optString("id", "");
                            String layer = id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : id;
                            if (layer.contains(":")) layer = layer.substring(layer.indexOf(':') + 1);
                            out.put(new JSONObject().put("layer", layer).put("props", f.optJSONObject("properties") != null ? f.optJSONObject("properties") : new JSONObject())
                                    .put("geometry", f.opt("geometry")));
                        }
                        res.put("ok", true).put("lat", lat).put("lon", lon).put("features", out).put("src", "wfs");
                        js("window.onGeoInfo && window.onGeoInfo(" + JSONObject.quote(res.toString()) + ")");
                        return;
                    }
                }
            } else c.disconnect();
        } catch (Exception ignored) { /* weiter mit WMS */ }
        res = new JSONObject();
        try {
            JSONArray la = new JSONArray(layersJson);
            StringBuilder layers = new StringBuilder();
            for (int i = 0; i < la.length(); i++) layers.append(layers.length() > 0 ? "," : "").append("dipul:").append(la.getString(i));
            double d = 0.0004;
            String base = DIPUL_WMS + "?service=WMS&version=1.1.1&request=GetFeatureInfo&styles=&srs=EPSG:4326"
                    + "&bbox=" + String.format(Locale.ROOT, "%.7f,%.7f,%.7f,%.7f", lon - d, lat - d, lon + d, lat + d)
                    + "&width=101&height=101&x=50&y=50&feature_count=50"
                    + "&layers=" + URLEncoder.encode(layers.toString(), "UTF-8")
                    + "&query_layers=" + URLEncoder.encode(layers.toString(), "UTF-8");
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "KMZMissionen/1.4 (Kitzrettung, nicht-kommerziell)");
            String text = null;
            JSONArray feats = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "&info_format=application/json").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(20000);
                for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
                if (c.getResponseCode() == 200) {
                    String body;
                    try (InputStream in = c.getInputStream()) { body = readAll(in); }
                    if (body.trim().startsWith("{")) feats = new JSONObject(body).optJSONArray("features");
                    else if (!body.contains("ServiceException")) text = body;   // dipul erlaubt JSON nicht mehr („ForbiddenFormat“) – dann GML
                }
                c.disconnect();
            } catch (Exception ignored) { }
            if (feats == null && text == null) {   // GML: Merkmale je Zone auslesen
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "&info_format=" + URLEncoder.encode("application/vnd.ogc.gml", "UTF-8")).openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(20000);
                    for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
                    if (c.getResponseCode() == 200) {
                        String body; try (InputStream in = c.getInputStream()) { body = readAll(in); }
                        if (!body.contains("ServiceException")) {
                            feats = new JSONArray();
                            Matcher fm = Pattern.compile("(?s)<(?:gml:featureMember|wfs:member)>\\s*<(\\w+):(\\w+)[^>]*>(.*?)</\\1:\\2>").matcher(body);
                            while (fm.find()) {
                                JSONObject pr = new JSONObject();
                                Matcher pm = Pattern.compile("(?s)<" + fm.group(1) + ":(\\w+)>([^<]*)</" + fm.group(1) + ":\\1>").matcher(fm.group(3));
                                while (pm.find()) { String v = pm.group(2).trim(); if (!v.isEmpty()) pr.put(pm.group(1), v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")); }
                                feats.put(new JSONObject().put("id", fm.group(2) + ".x").put("properties", pr));
                            }
                        }
                    }
                    c.disconnect();
                } catch (Exception ignored) { feats = null; }
            }
            if (feats == null && text == null) {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "&info_format=text/plain").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("dipul antwortet mit " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { text = readAll(in); }
                c.disconnect();
                if (text.contains("ServiceException")) throw new Exception("dipul lehnt die Abfrage ab");
            }
            JSONArray out = new JSONArray();
            if (feats != null) for (int i = 0; i < feats.length(); i++) {
                JSONObject f = feats.getJSONObject(i);
                String id = f.optString("id", "");
                String layer = id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : id;
                if (layer.contains(":")) layer = layer.substring(layer.indexOf(':') + 1);
                out.put(new JSONObject().put("layer", layer).put("props", f.optJSONObject("properties") != null ? f.optJSONObject("properties") : new JSONObject()));
            }
            res.put("ok", true).put("lat", lat).put("lon", lon).put("features", out);
            if (text != null) res.put("text", text);
        } catch (Exception e) {
            try { res.put("ok", false).put("error", "Abfrage bei dipul nicht möglich: " + (e.getMessage() != null ? e.getMessage() : "keine Verbindung")); } catch (Exception ignored) { }
        }
        js("window.onGeoInfo && window.onGeoInfo(" + JSONObject.quote(res.toString()) + ")");
    }

    private void powerInfoOnline(double lat, double lon) {
        JSONObject res = new JSONObject();
        try {
            String q = String.format(Locale.ROOT, "[out:json][timeout:20];("
                    + "way(around:200,%1$.6f,%2$.6f)[\"power\"~\"^(line|minor_line|cable)$\"];"
                    + "node(around:350,%1$.6f,%2$.6f)[\"power\"~\"^(tower|pole|portal)$\"];"
                    + ");out tags center 60;", lat, lon);
            byte[] body = ("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "KMZMissionen/1.4 (Kitzrettung, nicht-kommerziell)");
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            if (c.getResponseCode() != 200) throw new Exception("OpenStreetMap antwortet mit " + c.getResponseCode());
            String txt;
            try (InputStream in = c.getInputStream()) { txt = readAll(in); }
            c.disconnect();
            res.put("ok", true).put("elements", new JSONObject(txt).optJSONArray("elements"));
        } catch (Exception e) {
            try { res.put("ok", false).put("error", e.getMessage() != null ? e.getMessage() : "keine Verbindung"); } catch (Exception ignored) { }
        }
        js("window.onPowerInfo && window.onPowerInfo(" + JSONObject.quote(res.toString()) + ")");
    }

    // ---------- Stromleitungen aus OpenStreetMap: Gebiet zwischenspeichern ----------
    private JSONArray osmCache = null;
    private long osmCacheLoaded = 0;

    private File osmFile() { return dataFile("osm_power.json"); }
    private File placeFile() { return dataFile("osm_places.json"); }
    private File osmWaysFile() { return dataFile("osm_ways.json"); }

    /** Orts- und Flurnamen für die Suche: Gebiet der Felder + 25 km, alle 60 Tage oder wenn das Gebiet größer wird */
    private void placeSync(double n, double s, double e, double w, boolean force) {
        double dLa = 25 / 111.2, dLo = 25 / (111.2 * Math.cos(Math.toRadians((n + s) / 2)));
        if (pbfCovers(n, s, e, w) || placeFile().isFile() || n == n) return;   // Orte kommen aus der Geofabrik-Datei – keine Overpass-Abfrage mehr
        n += dLa; s -= dLa; e += dLo; w -= dLo;
        try {
            JSONObject b = new JSONObject(prefs.getString("place_bbox", "{}"));
            boolean covers = b.has("n") && b.getDouble("n") >= n - dLa / 2 && b.getDouble("s") <= s + dLa / 2 && b.getDouble("e") >= e - dLo / 2 && b.getDouble("w") <= w + dLo / 2;
            if (!force && covers && placeFile().isFile() && System.currentTimeMillis() - prefs.getLong("place_fetched", 0) < 60 * DAY) return;
        } catch (Exception ignored) { }
        try {
            catSt("orte", "load");
            String bb = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", s, w, n, e);
            String q = "[out:json][timeout:90];(node[\"place\"~\"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|farm|locality)$\"][\"name\"](" + bb + "););out body;";
            JSONArray els = new JSONObject(overpass(q, 120000)).getJSONArray("elements");
            JSONArray out = new JSONArray();
            for (int i = 0; i < els.length(); i++) {
                JSONObject el = els.getJSONObject(i), t = el.optJSONObject("tags");
                if (t == null || !el.has("lat")) continue;
                out.put(new JSONObject().put("n", t.optString("name")).put("t", t.optString("place"))
                        .put("lat", Math.round(el.getDouble("lat") * 1e5) / 1e5).put("lon", Math.round(el.getDouble("lon") * 1e5) / 1e5));
            }
            if (out.length() > 0) {
                writeFileAtomic(placeFile(), out.toString());
                prefs.edit().putLong("place_fetched", System.currentTimeMillis())
                        .putString("place_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            }
            catSt("orte", out.length() > 0 ? "ok" : "keine Orte in der Antwort");
        } catch (Exception ex) { catSt("orte", errTxt(ex)); }
    }

    private boolean osmCovers(double n, double s, double e, double w) {
        try {
            JSONObject b = new JSONObject(prefs.getString("osm_bbox", "{}"));
            return b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
        } catch (Exception ex) { return false; }
    }

    /** Lädt alle Leitungen/Masten im Gebiet (alle 24 h oder wenn das Gebiet größer wird) */
    private void osmSync(double n, double s, double e, double w, boolean force) {
        // gespeichertes Gebiet behalten: mit dem neuen zusammenfassen, solange es klein bleibt (< ca. 80 km)
        try {
            JSONObject ob = new JSONObject(prefs.getString("osm_bbox", "{}"));
            if (ob.has("n") && osmFile().isFile()) {
                double un = Math.max(n, ob.getDouble("n")), us = Math.min(s, ob.getDouble("s"));
                double ue = Math.max(e, ob.getDouble("e")), uw = Math.min(w, ob.getDouble("w"));
                if (Math.hypot((un - us) * 111.2, (ue - uw) * 111.2 * Math.cos(Math.toRadians((un + us) / 2))) < 80) { n = un; s = us; e = ue; w = uw; }
            }
        } catch (Exception ignored) { }
        long fetched = prefs.getLong("osm_fetched", 0);
        boolean fresh = System.currentTimeMillis() - fetched < 7 * DAY;   // Leitungen ändern sich selten
        boolean oldFormat = prefs.getInt("osm_ver", 1) < 7;   // ältere Daten (ohne Hindernisse/Ortsnamen bzw. ohne RTH-Landestellen): einmal neu auswerten
        if (!force && fresh && !oldFormat && osmCovers(n, s, e, w) && osmFile().isFile() && osmWaysFile().isFile() && prefs.getLong("pbf_t", 0) > 0) return;
        // 1. Wahl: Geofabrik-Datei (zuverlässig) – Overpass nur noch als Ausweg
        Exception pbfErr = null;
        try { if (pbfSync(n, s, e, w, force)) return; }
        catch (Throwable px) {   // auch Speichermangel abfangen – und den Grund sichtbar machen
            pbfErr = px instanceof Exception ? (Exception) px : new Exception(px instanceof OutOfMemoryError ? "zu wenig Arbeitsspeicher beim Auswerten" : px.toString());
            try { java.io.StringWriter sw = new java.io.StringWriter(); px.printStackTrace(new java.io.PrintWriter(sw));
                writeFileAtomic(new File(pbfDir(), "fehler.txt"), new java.util.Date() + "\n" + sw); } catch (Throwable ignored) { }
        }
        // Keine Massenabfragen mehr an die öffentlichen OpenStreetMap-Server (Overpass) – laut OSM-Nutzungsregeln für große Mengen
        // nicht gedacht und derzeit überlastet. Ohne Geofabrik-Datei bleiben die gespeicherten Daten aktiv.
        for (String cat : new String[]{"hindernisse", "feldwege"}) {
            boolean have = "feldwege".equals(cat) ? osmWaysFile().isFile() : osmFile().isFile();
            if (pbfWait != null) catSt(cat, "wlan:" + pbfWait);
            else if (pbfErr != null) catSt(cat, (have ? "alt:" : "") + "OpenStreetMap-Datei: " + pbfMsg(pbfErr));
            else catSt(cat, (have ? "alt:" : "") + "OpenStreetMap-Datei für dieses Gebiet nicht verfügbar");
        }
    }

    /** Alter Weg über Overpass – nur noch, wenn die Geofabrik-Datei (noch) nicht da ist */
    private volatile String ovLoad = "load";   // Statustext, während Overpass als Ausweg läuft
    private static String pbfMsg(Throwable t) { String m = t.getMessage(); return t.getClass().getSimpleName().replace("Exception", "") + (m == null ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m)); }
    private void osmSyncOverpass(double n, double s, double e, double w) {
        catSt("hindernisse", ovLoad); catSt("feldwege", ovLoad);
        try {
            // Hindernisse, Leitungen, Bahn und Ortsnamen in Kacheln von ca. 20 km (große Einzelanfrage lief in Zeitüberschreitung)
            final String[] OBS = {
                    "way[\"power\"~\"^(line|minor_line|cable)$\"]", "node[\"power\"~\"^(tower|portal)$\"]", "node[\"power\"=\"pole\"][\"height\"]",
                    "nwr[\"power\"=\"generator\"][\"generator:source\"=\"wind\"]", "node[\"man_made\"~\"^(mast|tower|chimney|communications_tower)$\"]",
                    "way[\"railway\"~\"^(rail|light_rail)$\"]",
                    "node[\"place\"~\"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|farm|locality)$\"][\"name\"]"};
            Object[] ob = overpassTiles(OBS, n, s, e, w, 20, 5, "hindernisse");
            JSONArray els = (JSONArray) ob[0]; int obBad = (Integer) ob[1], obAll = (Integer) ob[2]; Exception obErr = (Exception) ob[3];
            if (obBad > 0 && osmFile().isFile()) {   // Teilerfolg: mit dem bisher Gespeicherten zusammenführen
                try { java.util.Set<String> seen = new java.util.HashSet<>(); for (int q2 = 0; q2 < els.length(); q2++) { JSONObject el = els.getJSONObject(q2); seen.add(el.optString("type") + el.optLong("id")); }
                    JSONArray old = new JSONArray(readFile(osmFile())); for (int q2 = 0; q2 < old.length(); q2++) { JSONObject el = old.getJSONObject(q2); if (seen.add(el.optString("type") + el.optLong("id"))) els.put(el); } } catch (Exception ignored) { }
            }
            if (obBad == obAll) {   // nichts bekommen: Gespeichertes bleibt, Feldwege trotzdem versuchen
                catSt("hindernisse", (osmFile().isFile() ? "alt:" : "") + errTxt(obErr != null ? obErr : new Exception("Kein OpenStreetMap-Server erreichbar")));
            } else {
                writeFileAtomic(osmFile(), els.toString());
                catSt("hindernisse", obBad == 0 ? "ok" : "alt:" + obBad + " von " + obAll + " Teilen fehlen – " + errTxt(obErr));
            }
            // Feld- und Wirtschaftswege eigene Abfrage: sehr viele Daten – schlägt sie fehl, bleiben die Hindernisse trotzdem gespeichert
            // in Kacheln von ca. 10 km abfragen – kleine Anfragen kommen bei überlasteten Servern viel eher durch
            try {
                double km = Math.max((n - s) * 111.2, (e - w) * 111.2 * Math.cos(Math.toRadians((n + s) / 2)));
                int k = Math.max(1, Math.min(6, (int) Math.ceil(km / 10)));
                JSONArray wys = new JSONArray(); java.util.Set<Long> seenW = new java.util.HashSet<>(); int bad = 0; Exception wErr = null;
                List<int[]> todo = new ArrayList<>();
                for (int iy = 0; iy < k; iy++) for (int ix = 0; ix < k; ix++) todo.add(new int[]{iy, ix});
                // bis zu 3 Durchgänge: fehlgeschlagene Teile nach einer Pause nochmal (Server drosseln bei vielen Anfragen kurz hintereinander)
                for (int pass = 0; pass < 3 && !todo.isEmpty(); pass++) {
                    if (pass > 0) Thread.sleep(pass * 20000L);
                    List<int[]> failed = new ArrayList<>();
                    for (int[] t : todo) {
                        int iy = t[0], ix = t[1];
                        double ts = s + (n - s) * iy / k, tn = s + (n - s) * (iy + 1) / k, tw = w + (e - w) * ix / k, te = w + (e - w) * (ix + 1) / k;
                        String tb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", ts, tw, tn, te);
                        catSt("feldwege", ovLoad);
                        try {
                            JSONArray part = new JSONObject(overpass("[out:json][timeout:90];(way[\"highway\"~\"^(track|path|service|unclassified|tertiary|bridleway|cycleway|footway)$\"](" + tb + "););out tags geom;", 120000)).getJSONArray("elements");
                            for (int q2 = 0; q2 < part.length(); q2++) { JSONObject el = part.getJSONObject(q2); if (seenW.add(el.optLong("id"))) wys.put(el); }
                        } catch (Exception te2) { failed.add(t); wErr = te2; }
                        Thread.sleep(1500);   // freundlich zum Server
                    }
                    todo = failed;
                }
                bad = todo.size();
                // Teilerfolg: neue Wege + bisher gespeicherte Wege (für die fehlenden Teile) zusammenführen – nichts geht verloren
                if (bad > 0 && osmWaysFile().isFile()) {
                    try { JSONArray old = new JSONArray(readFile(osmWaysFile())); for (int q2 = 0; q2 < old.length(); q2++) { JSONObject el = old.getJSONObject(q2); if (seenW.add(el.optLong("id"))) wys.put(el); } } catch (Exception ignored) { }
                }
                if (wys.length() > 0) writeFileAtomic(osmWaysFile(), wys.toString());
                catSt("feldwege", bad == 0 ? "ok" : (osmWaysFile().isFile() ? "alt:" : "") + bad + " von " + (k * k) + " Teilen fehlen – " + errTxt(wErr));
            } catch (Exception we) { catSt("feldwege", (osmWaysFile().isFile() ? "alt:" : "") + errTxt(we)); }
            if (obBad == 0) {   // nur bei vollständigem Erfolg als „aktuell“ merken – sonst beim nächsten Mal erneut versuchen
                prefs.edit().putLong("osm_fetched", System.currentTimeMillis()).putInt("osm_ver", 7)
                        .putString("osm_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            }
            if (obBad < obAll) { osmCache = els; osmCacheLoaded = System.currentTimeMillis(); }
        } catch (Exception ex) { catSt("hindernisse", (osmFile().isFile() ? "alt:" : "") + errTxt(ex)); if ("load".equals(catStatus.get("feldwege"))) catSt("feldwege", (osmWaysFile().isFile() ? "alt:" : "") + errTxt(ex)); }
    }


    // ---------- Einsatz-Wächter: Warnungen als Benachrichtigung – auch wenn DJI Pilot 2 im Vordergrund ist ----------
    static final String CH_ALERT = "einsatz_warn", CH_RUN = "einsatz_lauf", CH_SVC = "luftverkehr_dienst";
    static volatile boolean appFg;   // App gerade im Vordergrund (dann kein Warnfenster über allen Apps – die App zeigt ihren eigenen Hinweis)
    /** Luftverkehr: je Ton-Einstellung ein eigener Kanal (Android erlaubt keinen Tonwechsel an einem bestehenden Kanal) */
    static final String[] CH_TRAF = {"luftverkehr_still", "luftverkehr_ton", "luftverkehr_alarm"};
    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        android.app.NotificationChannel a = new android.app.NotificationChannel(CH_ALERT, "Einsatz-Warnungen", android.app.NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Regen, Böen, Wind in Flughöhe, Scherwind, Kp, NOTAM, Tiefflug"); a.enableVibration(true);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(new android.app.NotificationChannel(CH_RUN, "Einsatz-Wächter läuft", android.app.NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new android.app.NotificationChannel(CH_SVC, "Luftverkehr-Dienst (Hintergrund)", android.app.NotificationManager.IMPORTANCE_MIN));   // von Android vorgeschrieben – so unauffällig wie möglich
        String[] nm3 = {"Luftverkehr – ohne Ton", "Luftverkehr – Hinweiston", "Luftverkehr – Alarmton"};
        for (int i = 0; i < 3; i++) {
            android.app.NotificationChannel t = new android.app.NotificationChannel(CH_TRAF[i], nm3[i], android.app.NotificationManager.IMPORTANCE_HIGH);
            t.setDescription("Luftfahrzeug nahe und tief (Open Glider Network, ADS-B)");
            if (i == 0) { t.setSound(null, null); t.enableVibration(false); }
            else if (i == 2) { t.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()); t.enableVibration(true); }
            else t.enableVibration(true);
            nm.createNotificationChannel(t);
        }
    }
    @SuppressWarnings("deprecation")
    static android.app.Notification.Builder nb(Context c, String ch) { return Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, ch) : new android.app.Notification.Builder(c); }
    static android.app.PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class); i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return android.app.PendingIntent.getActivity(c, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
    @SuppressWarnings("deprecation")
    static void alert(Context c, int id, String title, String text) { alert(c, id, title, text, CH_ALERT); }
    @SuppressWarnings("deprecation")
    static void alert(Context c, int id, String title, String text, String ch) {
        try {
            ensureChannels(c);
            android.app.Notification.Builder b = nb(c, ch).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(openApp(c))
                    .setCategory(android.app.Notification.CATEGORY_ALARM).setWhen(System.currentTimeMillis()).setShowWhen(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(android.app.Notification.PRIORITY_HIGH).setDefaults(android.app.Notification.DEFAULT_ALL);
            ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (Exception ignored) { }
    }

    // ---------- Luftverkehr live: OGN (FLARM/OGN-Tracker, glidernet.org) + ADS-B (adsb.lol / airplanes.live) ----------
    /** Hält die Flugzeuge im Umkreis aktuell – für die Karte und den Einsatz-Wächter. Daten nur im Speicher, nichts wird gespeichert.
     *  OGN-Regeln: Geräte mit „kein Tracking“/„Stealth“ werden verworfen, angezeigt wird nur die Art, keine Kennung. */
    /** Geländehöhen (Copernicus DEM 90 m über Open-Meteo), dauerhaft im App-Speicher gemerkt – ein Raster von ca. 110 × 70 m */
    static final class Elev {
        static File f; static JSONObject c = new JSONObject();
        static synchronized void init(File dir) {
            if (f != null) return; f = new File(dir, "gelaendehoehen.json");
            try { if (f.isFile()) c = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        }
        static String key(double la, double lo) { return String.format(Locale.ROOT, "%.3f,%.3f", la, lo); }
        /** gemerkte Höhe an dieser Stelle oder im Umkreis von ca. 1 km, sonst NaN */
        static synchronized double cached(double la, double lo) {
            double v = c.optDouble(key(la, lo), Double.NaN); if (!Double.isNaN(v)) return v;
            double best = Double.NaN, bd = 1.0;
            for (java.util.Iterator<String> it = c.keys(); it.hasNext(); ) {
                String k = it.next(); int i = k.indexOf(','); if (i < 0) continue;
                try { double d = Math.hypot((Double.parseDouble(k.substring(0, i)) - la) * 111.2, (Double.parseDouble(k.substring(i + 1)) - lo) * 71);
                    if (d < bd) { bd = d; best = c.optDouble(k); } } catch (Exception ignored) { }
            }
            return best;
        }
        /** Höhe holen (erst aus dem Speicher, sonst online und dann merken) */
        static double at(double la, double lo) {
            double v = cached(la, lo); if (!Double.isNaN(v)) return v;
            List<double[]> l = new ArrayList<>(); l.add(new double[]{la, lo}); fetch(l);
            return cached(la, lo);
        }
        /** fehlende Punkte gesammelt holen (bis 100 je Abruf) */
        static void fetch(List<double[]> pts) {
            List<double[]> miss = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
            synchronized (Elev.class) { for (double[] p : pts) { String k = key(p[0], p[1]); if (!c.has(k) && seen.add(k)) miss.add(p); } }
            boolean ch = false;
            for (int i = 0; i < miss.size(); i += 100) {
                StringBuilder la = new StringBuilder(), lo = new StringBuilder();
                List<double[]> part = miss.subList(i, Math.min(miss.size(), i + 100));
                for (double[] p : part) { if (la.length() > 0) { la.append(','); lo.append(','); } la.append(String.format(Locale.ROOT, "%.4f", p[0])); lo.append(String.format(Locale.ROOT, "%.4f", p[1])); }
                try {
                    JSONArray ev = new JSONObject(Watch.get("https://api.open-meteo.com/v1/elevation?latitude=" + la + "&longitude=" + lo)).optJSONArray("elevation");
                    if (ev != null) synchronized (Elev.class) { for (int j = 0; j < part.size() && j < ev.length(); j++) { double e = ev.optDouble(j); if (!Double.isNaN(e)) { c.put(key(part.get(j)[0], part.get(j)[1]), Math.round(e)); ch = true; } } }
                } catch (Exception ignored) { }
            }
            if (ch) synchronized (Elev.class) { try { if (f != null) java.nio.file.Files.write(f.toPath(), c.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { } }
        }
    }

    // ---------- Andere Drohnen: Direct Remote ID (EU-Pflicht, ASTM F3411 / ASD-STAN) – Bluetooth und WLAN, ganz ohne Internet ----------
    static final class Rid {
        /** key (Seriennummer bzw. Funkadresse) -> Zustand */
        static final Map<String, double[]> st = new java.util.concurrent.ConcurrentHashMap<>();   // {lat, lon, altGeo, height, heightType, speed, dir, vs, uaType, t, opLat, opLon, src(0 BLE/1 WLAN)}
        static final Map<String, String[]> ids = new java.util.concurrent.ConcurrentHashMap<>();  // {uasId, operatorId, selfId}
        static final Map<String, String> macKey = new java.util.concurrent.ConcurrentHashMap<>();  // Funkadresse -> Schlüssel
        static volatile boolean uiOn, watchOn, running; static volatile String err = "";
        static volatile long bleN, wifiScans, wifiHits, lastScan, nanN; static volatile int bleState = -1, nanState = -1; static Object nanSes;
        static Context ctx; static Object bleCb; static android.content.BroadcastReceiver wifiRx; static Thread wifiTh;
        static boolean active() { return uiOn || watchOn; }
        static final java.util.UUID ODID = java.util.UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb");

        @SuppressLint("MissingPermission")
        static synchronized void start(Context c) {
            if (running) return; ctx = c.getApplicationContext(); running = true; err = "";
            startBle();
            // WLAN NaN (Wi-Fi Aware) – nur wenn das Gerät es kann
            if (Build.VERSION.SDK_INT >= 26) try {
                if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
                    final android.net.wifi.aware.WifiAwareManager am = (android.net.wifi.aware.WifiAwareManager) ctx.getSystemService(Context.WIFI_AWARE_SERVICE);
                    final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
                    if (am != null && am.isAvailable()) am.attach(new android.net.wifi.aware.AttachCallback() {
                        @Override public void onAttached(android.net.wifi.aware.WifiAwareSession ses) {
                            nanSes = ses; nanState = 2;
                            ses.subscribe(new android.net.wifi.aware.SubscribeConfig.Builder().setServiceName("org.opendroneid.remoteid").build(), new android.net.wifi.aware.DiscoverySessionCallback() {
                                @Override public void onServiceDiscovered(android.net.wifi.aware.PeerHandle peer, byte[] info, List<byte[]> mf) {
                                    try { if (info != null && info.length > 1) { nanN++; parse("nan" + peer.hashCode(), info, 1, 1); } } catch (Throwable ignored) { }   // [Zähler][Nachrichtenpaket]
                                }
                            }, h);
                        }
                        @Override public void onAttachFailed() { nanState = 3; }
                    }, h);
                    else nanState = 1;
                } else nanState = 0;
            } catch (Throwable e) { nanState = 3; }
            // WLAN-Beacon (Android 11+: Informationselemente der Suchergebnisse)
            if (Build.VERSION.SDK_INT >= 30) try {
                final android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                wifiRx = new android.content.BroadcastReceiver() { @Override public void onReceive(Context cx, Intent in) { try { onWifi(wm.getScanResults()); } catch (Throwable ignored) { } } };
                ctx.registerReceiver(wifiRx, new android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                wifiTh = new Thread(() -> {   // Android erlaubt ca. 4 Suchen je 2 Minuten
                    while (running && active()) { try { if (wm.isWifiEnabled() && wm.startScan()) { wifiScans++; lastScan = System.currentTimeMillis(); } Thread.sleep(31000); } catch (InterruptedException ie) { break; } catch (Throwable ignored) { } }
                });
                wifiTh.start();
            } catch (Throwable e) { err += "WLAN: " + e.getMessage() + " "; }
        }
        static volatile long bleTry;
        /** Bluetooth war aus / Suche fehlgeschlagen: alle 20 s neu versuchen (z. B. nachdem Bluetooth eingeschaltet wurde) */
        static synchronized void retryBle() { if (running && bleCb == null && System.currentTimeMillis() - bleTry > 20000) startBle(); }
        @SuppressLint("MissingPermission")
        static synchronized void startBle() {
            bleTry = System.currentTimeMillis(); err = err.replaceAll("(Standort[^.]*)\\. ?", "");
            if (Build.VERSION.SDK_INT >= 23 && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) err += "Standort-Berechtigung fehlt – ohne sie liefert Android keine Funde. ";
            try { android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 28 && lm != null && !lm.isLocationEnabled()) err += "Standort (GPS) ist ausgeschaltet – ohne ihn liefert Android keine Funde. "; } catch (Throwable ignored) { }
            err = err.replaceAll("(kein Bluetooth|Bluetooth ist aus|Bluetooth[^.]*fehlgeschlagen[^.]*|Bluetooth:[^.]*)\\.? ?", "");
            try {
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad == null) { bleState = 0; err += "kein Bluetooth. "; }
                else if (!ad.isEnabled()) { bleState = 1; err += "Bluetooth ist aus. "; }
                else {
                    android.bluetooth.le.BluetoothLeScanner sc = ad.getBluetoothLeScanner();
                    android.bluetooth.le.ScanCallback cb = new android.bluetooth.le.ScanCallback() {
                        @Override public void onScanResult(int type, android.bluetooth.le.ScanResult r) { onBle(r); }
                        @Override public void onBatchScanResults(List<android.bluetooth.le.ScanResult> l) { for (android.bluetooth.le.ScanResult r : l) onBle(r); }
                        @Override public void onScanFailed(int code) { bleState = 3; err += "Bluetooth-Suche fehlgeschlagen (" + code + "). "; }
                    };
                    List<android.bluetooth.le.ScanFilter> f = new ArrayList<>();   // ohne Filter (manche Chips filtern leere Dienstdaten falsch) – sortiert wird in onBle
                    android.bluetooth.le.ScanSettings.Builder sb = new android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY);
                    if (Build.VERSION.SDK_INT >= 26 && ad.isLeCodedPhySupported()) sb.setLegacy(false).setPhy(android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED);
                    sc.startScan(f, sb.build(), cb); bleCb = cb; bleState = 2;
                }
            } catch (Throwable e) { bleState = 3; err += "Bluetooth: " + e.getMessage() + " "; }
        }
        @SuppressLint("MissingPermission")
        static synchronized void stopIfIdle() {
            if (active() || !running) return; running = false;
            try { if (bleCb != null) { android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                bm.getAdapter().getBluetoothLeScanner().stopScan((android.bluetooth.le.ScanCallback) bleCb); } } catch (Throwable ignored) { }
            bleCb = null;
            try { if (wifiRx != null) ctx.unregisterReceiver(wifiRx); } catch (Throwable ignored) { } wifiRx = null;
            Thread t = wifiTh; wifiTh = null; if (t != null) t.interrupt();
            try { if (nanSes != null && Build.VERSION.SDK_INT >= 26) ((android.net.wifi.aware.WifiAwareSession) nanSes).close(); } catch (Throwable ignored) { } nanSes = null; nanState = -1;
            st.clear(); ids.clear(); macKey.clear();
        }
        static void onBle(android.bluetooth.le.ScanResult r) {
            try {
                if (r.getScanRecord() == null) return;
                byte[] d = r.getScanRecord().getServiceData(new android.os.ParcelUuid(ODID));
                if (d == null || d.length < 3 || (d[0] & 0xFF) != 0x0D) return;   // App-Code Open Drone ID
                bleN++;
                parse(r.getDevice().getAddress(), d, 2, 0);
            } catch (Throwable ignored) { }
        }
        static void onWifi(List<android.net.wifi.ScanResult> l) {
            if (Build.VERSION.SDK_INT < 30 || l == null) return;
            for (android.net.wifi.ScanResult r : l) {
                try {
                    for (android.net.wifi.ScanResult.InformationElement ie : r.getInformationElements()) {
                        if (ie.getId() != 221) continue;
                        java.nio.ByteBuffer b = ie.getBytes(); byte[] d = new byte[b.remaining()]; b.get(d);
                        // Herstellerelement: OUI FA:0B:BC, Typ 0x0D, Zähler, dann Nachrichtenpaket
                        if (d.length < 6) continue;
                        int oui = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                        if (oui == 0xFA0BBC && (d[3] & 0xFF) == 0x0D) { wifiHits++; parse(r.BSSID, d, 5, 1); }
                        else if (oui == 0x60601F || oui == 0x481CB9 || oui == 0x34D262 || oui == 0x263712) { if (dji(r.BSSID, d)) wifiHits++; }   // DJI-eigenes „DroneID“ (ältere Modelle)
                    }
                } catch (Throwable ignored) { }
            }
        }
        /** DJI-eigenes DroneID im WLAN-Beacon: [OUI 3][4 Byte][Version 1/2][Daten] – Koordinaten in Bogenmaß × 1e7 */
        static boolean dji(String mac, byte[] d) {
            int ver = d.length > 7 ? u8(d, 7) : 0, o = 8;
            if ((ver != 1 && ver != 2) || d.length < o + 2 + 2 + 16 + 4 + 4 + 2 + 2 + 2 + 2 + 2 + 2) return false;
            String serial = str(d, o + 4, 16);
            int p = o + 20;
            double lon = i32(d, p) / 1e7 * 180 / Math.PI, lat = i32(d, p + 4) / 1e7 * 180 / Math.PI;
            double height = (short) u16(d, p + 10) / 10.0;
            double vx = (short) u16(d, p + 12), vy = (short) u16(d, p + 14);
            double yaw = (short) u16(d, ver == 1 ? p + 22 : p + 18) / 100.0;
            if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01 || Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
            String key = serial.isEmpty() ? mac : serial; macKey.put(mac, key);
            double[] v = st.computeIfAbsent(key, k -> blank());
            v[0] = lat; v[1] = lon; v[3] = height; v[4] = 0; v[5] = Math.hypot(vx, vy) / 100.0; v[6] = ((yaw % 360) + 360) % 360; v[7] = 0; v[8] = 2; v[9] = System.currentTimeMillis(); v[12] = 1;
            if (ver == 2 && d.length >= p + 44) { double pla = i32(d, p + 28) / 1e7 * 180 / Math.PI, plo = i32(d, p + 32) / 1e7 * 180 / Math.PI; if (Math.abs(pla) > 0.01 && Math.abs(pla) <= 90) { v[10] = pla; v[11] = plo; } }
            String[] s0 = ids.computeIfAbsent(key, k -> new String[3]); if (!serial.isEmpty()) s0[0] = serial;
            return true;
        }
        static int u8(byte[] d, int i) { return d[i] & 0xFF; }
        static int u16(byte[] d, int i) { return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8); }
        static int i32(byte[] d, int i) { return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16) | (d[i + 3] << 24); }
        static String str(byte[] d, int i, int n) { StringBuilder b = new StringBuilder(); for (int k = i; k < i + n && k < d.length; k++) { int c = d[k] & 0xFF; if (c == 0) break; if (c >= 32 && c < 127) b.append((char) c); } return b.toString().trim(); }
        /** eine Nachricht (25 Byte) oder ein Nachrichtenpaket ab Position o */
        static void parse(String mac, byte[] d, int o, int src) {
            if (o >= d.length) return;
            int type = u8(d, o) >> 4;
            if (type == 0xF) {   // Paket: [Kopf][Größe][Anzahl][Nachrichten…]
                if (o + 3 > d.length) return;
                int sz = u8(d, o + 1), n = u8(d, o + 2); if (sz < 25) sz = 25;
                for (int k = 0; k < n && o + 3 + (k + 1) * sz <= d.length; k++) one(mac, d, o + 3 + k * sz, src);
            } else for (int p = o; p + 25 <= d.length; p += 25) one(mac, d, p, src);   // ältere Sender: Nachrichten ohne Paket-Kopf hintereinander
        }
        static void one(String mac, byte[] d, int o, int src) {
            if (o + 25 > d.length) return;
            int type = u8(d, o) >> 4;
            String key = macKey.getOrDefault(mac, mac);
            if (type == 0) {   // Basis-ID: Seriennummer / Registrierung + Luftfahrzeugart
                String id = str(d, o + 2, 20); if (id.isEmpty()) return;
                String old = macKey.put(mac, id);
                if (old == null || !old.equals(id)) { double[] v = st.remove(old != null ? old : mac); if (v != null) st.put(id, v); String[] s0 = ids.remove(old != null ? old : mac); if (s0 != null) ids.put(id, s0); }
                key = id;
                double[] v = st.computeIfAbsent(key, k -> blank()); v[8] = u8(d, o + 1) & 0x0F; v[12] = src;
                String[] s = ids.computeIfAbsent(key, k -> new String[3]); s[0] = id;
            } else if (type == 1) {   // Position
                int f = u8(d, o + 1);
                double lat = i32(d, o + 5) * 1e-7, lon = i32(d, o + 9) * 1e-7;
                if (lat == 0 && lon == 0 || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
                double[] v = st.computeIfAbsent(key, k -> blank());
                int dir = u8(d, o + 2); if ((f & 2) != 0) dir += 180; v[6] = dir > 360 ? -1 : dir;
                int sp = u8(d, o + 3); v[5] = sp == 255 ? -1 : (f & 1) != 0 ? sp * 0.75 + 63.75 : sp * 0.25;
                v[7] = d[o + 4] == 126 ? 0 : d[o + 4] * 0.5;   // 126 = unbekannt
                v[0] = lat; v[1] = lon;
                int ag = u16(d, o + 15), h = u16(d, o + 17);
                v[2] = ag == 0 ? Double.NaN : ag * 0.5 - 1000; v[3] = h == 0 ? Double.NaN : h * 0.5 - 1000; v[4] = (f >> 2) & 1;   // 0 über Startpunkt, 1 über Grund
                v[9] = System.currentTimeMillis(); v[12] = src;
            } else if (type == 4) {   // System: Standort der steuernden Person
                double[] v = st.computeIfAbsent(key, k -> blank());
                double la = i32(d, o + 2) * 1e-7, lo = i32(d, o + 6) * 1e-7;
                if (!(la == 0 && lo == 0) && Math.abs(la) <= 90) { v[10] = la; v[11] = lo; }
            } else if (type == 5) {   // Betreiber-Registrierung (öffentlich, z. B. DEU…)
                String[] s = ids.computeIfAbsent(key, k -> new String[3]); s[1] = str(d, o + 2, 20);
            } else if (type == 3) {   // Freitext (z. B. Einsatzzweck)
                String[] s = ids.computeIfAbsent(key, k -> new String[3]); s[2] = str(d, o + 2, 23);
            }
        }
        static double[] blank() { double[] v = new double[13]; Arrays.fill(v, Double.NaN); v[9] = 0; return v; }
        static String json() {
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("{\"run\":").append(running).append(",\"ble\":").append(bleState).append(",\"bleN\":").append(bleN)
                    .append(",\"wifiScans\":").append(wifiScans).append(",\"wifiHits\":").append(wifiHits).append(",\"nan\":").append(nanState).append(",\"nanN\":").append(nanN).append(",\"err\":").append(JSONObject.quote(err.trim())).append(",\"d\":[");
            boolean f = true;
            for (Map.Entry<String, double[]> e : st.entrySet()) {
                double[] v = e.getValue(); if (v[9] == 0 || now - (long) v[9] > 60000) continue;
                String[] s = ids.getOrDefault(e.getKey(), new String[3]);
                if (!f) sb.append(','); f = false;
                sb.append("{\"id\":").append(JSONObject.quote(e.getKey())).append(",\"uas\":").append(JSONObject.quote(s[0] == null ? "" : s[0]))
                        .append(",\"op\":").append(JSONObject.quote(s[1] == null ? "" : s[1])).append(",\"self\":").append(JSONObject.quote(s[2] == null ? "" : s[2]))
                        .append(String.format(Locale.ROOT, ",\"la\":%.6f,\"lo\":%.6f,\"altGeo\":%s,\"h\":%s,\"hRef\":%d,\"ms\":%s,\"trk\":%s,\"vs\":%s,\"ua\":%s,\"age\":%d,\"src\":%d,\"opLa\":%s,\"opLo\":%s}",
                                v[0], v[1], num(v[2]), num(v[3]), Double.isNaN(v[4]) ? 0 : (int) v[4], num(v[5]), num(v[6]), num(v[7]), num(v[8]), (now - (long) v[9]) / 1000, Double.isNaN(v[12]) ? 0 : (int) v[12], num6(v[10]), num6(v[11])));
            }
            return sb.append("]}").toString();
        }
        static String num(double v) { return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.1f", v); }
        static String num6(double v) { return Double.isNaN(v) ? "null" : String.format(Locale.ROOT, "%.6f", v); }
    }

    static final class Traffic {
        static final Map<String, double[]> ac = new java.util.concurrent.ConcurrentHashMap<>();   // id -> {lat, lon, altM, trk, kmh, climb m/s, typ, t, src(0 OGN/1 ADS-B)}
        static final Map<String, String> label = new java.util.concurrent.ConcurrentHashMap<>();   // nur ADS-B: Flugnummer/Kennzeichen (öffentlich)
        static volatile double cLat, cLon, cKm = 30, ground = Double.NaN, qnh = Double.NaN; static volatile long qnhT;
        static volatile boolean uiOn, watchOn; static volatile Thread ogn, adsb; static volatile String err = "", adsbErr = "", adsbSrc = "", apDay = ""; static volatile int apCount;
        static synchronized void start(double lat, double lon, double km) {
            double nk = Math.max(5, Math.min(150, km));
            boolean moved = Math.hypot((lat - cLat) * 111.2, (lon - cLon) * 71) > Math.max(8, nk / 4) || Math.abs(nk - cKm) > Math.max(1, cKm / 4);
            if (!moved && ogn != null && ogn.isAlive()) {   // kleine Verschiebung: Abfrage läuft weiter (spart Neuverbindungen)
                if (adsb == null || !adsb.isAlive()) { adsb = new Thread(Traffic::adsbLoop); adsb.start(); }
                return;
            }
            cLat = lat; cLon = lon; cKm = nk;
            if (moved) { ground = Elev.cached(lat, lon); qnhT = 0; }
            if (ogn == null || !ogn.isAlive() || moved) { Thread o = ogn; ogn = new Thread(Traffic::ognLoop); if (o != null) o.interrupt(); ogn.start(); }
            if (adsb == null || !adsb.isAlive()) { adsb = new Thread(Traffic::adsbLoop); adsb.start(); }
        }
        static synchronized void stopIfIdle() { if (uiOn || watchOn) return; Thread a = ogn, b = adsb; ogn = null; adsb = null; if (a != null) a.interrupt(); if (b != null) b.interrupt(); ac.clear(); label.clear(); }
        static boolean active() { return (uiOn || watchOn); }
        static void ognLoop() {
            Thread me = Thread.currentThread();
            while (ogn == me && active()) {
                try (java.net.Socket so = new java.net.Socket()) {
                    so.connect(new java.net.InetSocketAddress("aprs.glidernet.org", 14580), 15000); so.setSoTimeout(90000);
                    String call = "KITZ" + (100000 + new java.util.Random().nextInt(899999));
                    so.getOutputStream().write(String.format(Locale.ROOT, "user %s pass -1 vers MissionKitzrettung 2.1 filter r/%.4f/%.4f/%d\r\n", call, cLat, cLon, (int) Math.round(cKm)).getBytes(StandardCharsets.US_ASCII));
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream(), StandardCharsets.ISO_8859_1));
                    long lastKa = System.currentTimeMillis(); err = "";
                    String ln;
                    while (ogn == me && active() && (ln = br.readLine()) != null) {
                        if (System.currentTimeMillis() - lastKa > 4 * 60000L) { so.getOutputStream().write("#keepalive\r\n".getBytes(StandardCharsets.US_ASCII)); lastKa = System.currentTimeMillis(); }
                        if (ln.startsWith("#")) continue;
                        try { parseOgn(ln); } catch (Exception ignored) { }
                    }
                } catch (Exception e) { err = "OGN: " + e.getMessage(); }
                try { Thread.sleep(15000); } catch (InterruptedException ie) { break; }
            }
        }
        static final Pattern OGN_POS = Pattern.compile("^([A-Za-z0-9-]+)>([A-Z0-9]+)[^:]*:[/@](\\d{6})h(\\d{2})(\\d{2}\\.\\d{2})([NS])(.)(\\d{3})(\\d{2}\\.\\d{2})([EW])(.)(?:(\\d{3})/(\\d{3}))?/A=(-?\\d{6})(.*)$");
        static void parseOgn(String ln) {
            Matcher m = OGN_POS.matcher(ln); if (!m.find()) return;
            String dest = m.group(2), rest = m.group(15);
            if (dest.startsWith("OGNSDR") || dest.startsWith("APRS")) return;   // Empfangsstationen
            Matcher id = Pattern.compile("\\bid([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})").matcher(rest);
            int typ = 0; String key = m.group(1);
            if (id.find()) { int b = Integer.parseInt(id.group(1), 16); if ((b & 0x80) != 0 || (b & 0x40) != 0) return;   // Stealth / kein Tracking
                typ = (b >> 2) & 0x0F; key = id.group(2).toUpperCase(Locale.ROOT); }
            else if (!dest.startsWith("OG")) return;
            double la = Integer.parseInt(m.group(4)) + Double.parseDouble(m.group(5)) / 60, lo = Integer.parseInt(m.group(8)) + Double.parseDouble(m.group(9)) / 60;
            Matcher w = Pattern.compile("!W(\\d)(\\d)!").matcher(rest);
            if (w.find()) { la += Integer.parseInt(w.group(1)) * 0.001 / 60; lo += Integer.parseInt(w.group(2)) * 0.001 / 60; }
            if ("S".equals(m.group(6))) la = -la; if ("W".equals(m.group(10))) lo = -lo;
            double alt = Integer.parseInt(m.group(14)) * 0.3048, trk = m.group(12) != null ? Integer.parseInt(m.group(12)) : -1, kmh = m.group(13) != null ? Integer.parseInt(m.group(13)) * 1.852 : -1;
            Matcher cl = Pattern.compile("([+-]\\d+)fpm").matcher(rest); double climb = cl.find() ? Integer.parseInt(cl.group(1)) * 0.00508 : 0;
            if (typ == 0) { char sym = m.group(11).charAt(0); typ = sym == '\'' ? 1 : sym == 'X' ? 3 : sym == 'g' ? 7 : sym == '^' ? 8 : sym == 'O' ? 11 : 8; }
            if (typ == 15 || typ == 14) return;   // feste Objekte / Bodenfahrzeuge
            if (dest.startsWith("OGADSB")) key = "A" + key;
            ac.put(key, new double[]{la, lo, alt, trk, kmh, climb, typ, System.currentTimeMillis(), dest.startsWith("OGADSB") ? 1 : 0});
        }
        static void adsbLoop() {
            Thread me = Thread.currentThread();
            // drei freie Dienste mit gleichem Datenformat; wer „zu viele Anfragen“ (HTTP 429) meldet, bekommt eine Pause und der nächste ist dran
            // Reihenfolge = Vorrang: adsb.lol (Grenze nach Serverlast), adsb.fi (1/s), airplanes.live (1/s, kostenlos nur ca. 500 Abfragen/Tag → nur Notreserve)
            String[] hosts = {"https://api.adsb.lol/v2/point/%.4f/%.4f/%d", "https://opendata.adsb.fi/api/v3/lat/%.4f/lon/%.4f/dist/%d", "https://api.airplanes.live/v2/point/%.4f/%.4f/%d"};
            String[] names = {"adsb.lol", "adsb.fi", "airplanes.live"};
            long[] pause = new long[hosts.length], wait = new long[hosts.length];
            int hi = 0;
            while (adsb == me && active()) {
                long now0 = System.currentTimeMillis();
                // immer der vorrangigste freie Dienst (nicht reihum – so verteilt sich nichts unnötig); airplanes.live nur bis 400 Abfragen am Tag
                String day = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date());
                if (!day.equals(apDay)) { apDay = day; apCount = 0; }
                int pick = -1; for (int h = 0; h < hosts.length; h++) { if (now0 < pause[h]) continue; if (h == 2 && apCount >= 400) continue; pick = h; break; }
                if (pick < 0) {   // alle drei pausieren: bis zum frühesten Ende warten
                    long t = Long.MAX_VALUE; for (long pz : pause) t = Math.min(t, pz);
                    try { Thread.sleep(Math.max(1000, Math.min(60000, t - now0))); } catch (InterruptedException ie) { break; }
                    continue;
                }
                hi = pick;
                if (hi == 2) apCount++;
                try {
                    String url = String.format(Locale.ROOT, hosts[hi], cLat, cLon, (int) Math.max(5, Math.min(100, Math.round(cKm / 1.852))));
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Kitzrettung, ehrenamtlich)");
                    int code = c.getResponseCode();
                    if (code == 429 || code == 503) {   // zu viele Anfragen: Pause (Retry-After beachten), bei Wiederholung länger
                        long ra = 0; try { ra = Long.parseLong(c.getHeaderField("Retry-After").trim()) * 1000; } catch (Exception ignored) { }
                        wait[hi] = Math.min(10 * 60000L, Math.max(ra, wait[hi] == 0 ? 60000 : wait[hi] * 2)); pause[hi] = System.currentTimeMillis() + wait[hi];
                        c.disconnect(); throw new Exception("HTTP " + code + " – Pause " + wait[hi] / 1000 + " s");
                    }
                    if (code != 200) { c.disconnect(); pause[hi] = System.currentTimeMillis() + 30000; throw new Exception("HTTP " + code); }
                    String txt; try (InputStream in = c.getInputStream()) { txt = readAll(in); } finally { c.disconnect(); }
                    wait[hi] = 0;
                    JSONObject jo = new JSONObject(txt);
                    JSONArray a = jo.has("ac") ? jo.optJSONArray("ac") : jo.optJSONArray("aircraft"); long now = System.currentTimeMillis();
                    if (a != null) for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i); if (o == null || !o.has("lat")) continue;
                        Object ab = o.opt("alt_baro"); if ("ground".equals(String.valueOf(ab))) continue;
                        // Höhe über NN: bevorzugt GPS-Höhe (alt_geom, bezogen auf das WGS84-Ellipsoid – in Deutschland liegt der Meeresspiegel (Geoid) ca. 47 m darüber),
                        // sonst barometrische Höhe (bezogen auf 1013,25 hPa) mit dem aktuellen Luftdruck (QNH) korrigiert: ca. 8,3 m je hPa
                        double alt = o.has("alt_geom") ? o.optDouble("alt_geom") * 0.3048 - 47
                                : ab instanceof Number ? ((Number) ab).doubleValue() * 0.3048 + (Double.isNaN(qnh) ? 0 : (qnh - 1013.25) * 8.3) : Double.NaN;
                        String cat = o.optString("category", ""); String t = o.optString("t", "");
                        boolean heli = cat.equals("A7") || t.matches("(?i)(EC|H1|H6|BK|AS3|AS5|AS6|R44|R22|R66|B06|B407|B412|B429|NH90|UH|CH|S76|A109|A119|A139|A169|AW|EXPL|MD52|MD60|G2CA|CABR)\\w*");
                        // Jets auch in der „kleinen“ Kategorie A2: Geschäftsreisejets nach Typcode, sonst alles über ca. 9,5 km Höhe
                        boolean jet = cat.startsWith("A") && cat.compareTo("A2") > 0
                                || t.matches("(?i)(C25|C5|C6|C7|CL3|CL6|CL30|CL35|CL60|GLF|GLEX|GL5T|GL6T|GL7T|GALX|G150|G280|FA|F2TH|F900|E35L|E50P|E55P|E545|E550|LJ|H25|HDJT|SF50|PC24|PRM1|BE40|ASTR|J328|CRJ|E1|E2|E7|A3|B7|B3|A2)\\w*")
                                || (alt > 9500 && !heli);
                        int typ = heli ? 3 : cat.equals("B1") ? 1 : jet ? 9 : 8;
                        String key = "A" + o.optString("hex").toUpperCase(Locale.ROOT);
                        double[] old = ac.get(key);
                        if (old != null && old[8] == 0 && now - old[7] < 20000) continue;   // OGN-Meldung ist frischer
                        ac.put(key, new double[]{o.optDouble("lat"), o.optDouble("lon"), alt, o.optDouble("track", -1), o.has("gs") ? o.optDouble("gs") * 1.852 : -1, o.optDouble("baro_rate", 0) * 0.00508, typ, now, 1});
                        // öffentliche ADS-B-Angaben: Rufzeichen | Typcode | Kennzeichen | Typbezeichnung | ICAO-Adresse (keine Halter-/Betreiberdaten)
                        String fl = o.optString("flight", "").trim(), op = o.optString("ownOp", "").trim();
                        Object[] ro = roleOf(o.optString("hex", ""), fl, o.optString("r", "").trim(), op, o.optInt("dbFlags", 0), typ == 3);
                        int role = (Integer) ro[0];
                        // Betreiber nur bei Behörden/Rettung/Militär weitergeben – nie bei Privatleuten
                        label.put(key, fl + "|" + t + "|" + o.optString("r", "").trim() + "|" + o.optString("desc", "").trim() + "|" + o.optString("hex", "").trim() + "|" + role + "|" + (role > 0 ? String.valueOf(ro[1]).replace("|", " ") : ""));
                    }
                    if (Double.isNaN(ground)) ground = Elev.at(cLat, cLon);   // Geländehöhe am Einsatzort (gemerkt bzw. einmal geholt)
                    if (System.currentTimeMillis() - qnhT > 30 * 60000L) try {   // aktueller Luftdruck auf Meereshöhe (QNH) für die barometrischen Höhen
                        JSONObject e = new JSONObject(Watch.get(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=pressure_msl", cLat, cLon)));
                        double q = e.optJSONObject("current").optDouble("pressure_msl"); if (q > 900 && q < 1100) { qnh = q; qnhT = System.currentTimeMillis(); }
                    } catch (Exception ignored) { }
                    adsbErr = ""; adsbSrc = names[hi];
                } catch (Exception e) { adsbErr = "ADS-B (" + names[hi] + "): " + e.getMessage(); if (now0 >= pause[hi]) pause[hi] = System.currentTimeMillis() + 30000; }
                // alte Einträge entfernen
                long now = System.currentTimeMillis();
                for (Map.Entry<String, double[]> e : ac.entrySet()) if (now - (long) e.getValue()[7] > 120000) { ac.remove(e.getKey()); label.remove(e.getKey()); }
                // App offen: alle 10 s, nur im Hintergrund (Warndienst): alle 15 s – weit unter allen Grenzen; dazwischen rechnet die Karte weiter (OGN kommt ohnehin sofort)
                try { Thread.sleep(uiOn ? 10000 : 15000); } catch (InterruptedException ie) { break; }
            }
        }
        static final String[] ROLE_OP = {"Oyonnair", "", "Airlec Air Espace", "Gendarmerie Frankreich", "French Customs", "Unicair GmbH", "Aero-Dienst", "DRF Luftrettung", "Tyrol Air Ambulance", "Polizei Hessen", "ADAC Luftrettung", "Luftwaffe", "Polizei", "Polizei Brandenburg", "Polizei Bayern", "Johanniter Luftrettung", "Polizei Baden-Württemberg", "Bundespolizei", "Heli-Flight", "Polizei Mecklenburg-Vorpommern", "Polizei Nordrhein-Westfalen", "Polizei Niedersachsen", "Polizei Rheinland-Pfalz", "Polizei Sachsen-Anhalt", "Polizei Sachsen", "Polizei Thüringen", "Kohl Medical", "Colombian National Police", "Polizei Österreich", "ARA Flugrettung", "Belgian Coast Guard", "Service dAide Medicale dUrgence", "Föderale Polizei Belgien", "Grenzschutz Polen", "ANWB Medical Air Assistance", "Netherlands Coast Guard", "Polizei Niederlande", "Dutch Caribbean Coastguard", "Aruba Police", "Polish Medical Air Service", "Polish Medical Air Rescue", "Polizei Polen", "Polizei Tschechien", "Swiss Air Ambulance AG", "AAA Alpine Air Ambulance", "Luxembourg Air Ambulance", "Luxembourg Air Rescue", "Polizei Luxemburg"};
        /** ICAO-Adresse (6) + Art (1) + Betreiber-Nr. (2) – aus plane-alert-db (© SDR-Enthusiasts, Open Database License), Polizei/Rettung/SAR in DE und Nachbarländern */
        static final String ROLE_DB = "3989E410039900310139B2EB10239BA4F10239DDE61003AAC002033AAC022033AAC032033AAC042033AAC052033AAC062033AAC072033AAC082033AAC092033AAC0A2033AAC0B2033AAC0C2033AAC0E2033AAC0F2033AAC102033AAC112033AAC122033AAC132033AAC142033AAC152033AAC162033AAC172033AAC182033AAC192033AAC1A2033AAC1B2033AAC1C2033AAC1D2033AAC1E2033AAC202033AAC242033AAC252033AAC282033AAC2A2033AAC382033B7B204043B7B214043B7B224043B7B234043B7B404043B7B414043B7B804043C0E3D1053C84811063C84831063CC57D1073CC9AB1073CD50B1083CD5681053CD8031053CE64E1053D00731083DA0DC2093DD2CA1103DD2F34113DD45E2123DD4F42123DD5422123DD5762133DD5772133DD66F1103DD6CA2143DD6CB2143DD6CC2143DD6CD2143DD6CE2143DD6CF2143DD6D02143DD6D12143DD7202143DD7351153DD7872163DD7942163DD7952163DD7962163DD7972163DD7982163DD7992163DD7B52143DD7B72143DD7BB1103DDC041103DDC4B1073DDC4D1073DDC5C1073DDC611073DDC631073DDC651073DDC661073DDC6A1073DDC6B1073DDC6C1073DDC6D1073DDC6E1073DDC6F1073DDC701073DDC731073DDC741073DDC751073DDC761073DDC771073DDC781073DDC791073DDD6E1073DDDCC2173DDDCE2173DDDCF2173DDDD02173DDDD12173DDDD22173DDDD32173DDDD42173DDDD62173DDDD72173DDDD82173DDDDA2173DDDDB2173DDDDC2173DDDDD2173DDDDE2173DDDDF2173DDDE02173DDDE22173DDDE32173DDDE42173DDDE52173DE0F81183DE2051073DE4F51103DE53C1103DE5842093DE5852093DE5862093DE5FF1103DE71C1103DE8F91073DEBD71153DEDA71103DEF141103DEFEA1103DF0061103DF03F1103DF0491103DF17F2123DF19A2173DF19C2173DF19D2173DF19E2173DF19F2173DF1A02173DF1A12173DF1A22173DF1A32173DF1A42173DF1A52173DF1A72173DF1A82173DF1A92173DF1AA2173DF1AB2173DF1AC2173DF1AD2173DF3B72123DF4722193DF4812193DF73E2203DF7412203DF7422203DF7432203DF7442203DF7452203DF7462203DF81F1103DF8EE2123DF9261103DFB801103DFB8E2213DFB902213DFB922213DFB932213DFE901073E010A2223E010B2223E01731073E01741073E01751073E01771073E02282233E02292233E02351103E023A2233E023B2233E02DF2123E02E02123E02E12123E02E22123E02E32123E02E42123E02E62123E02E82123E02ED1103E02F02123E037C2243E037D2243E037E2243E05832253E05842253E05852253E07162123E09651103E0A2E2173E0A302173E0A312173E0A322173E0A332173E0A342173E0A352173E0A362173E0A372173E0A382173E0A392173E0A3A2173E0A3B2173E0A3C2173E0A3D2173E0A3E2173E0A3F2173E0A402173E0A412173E0A422173E0A432173E0A442173E0A452173E0A462173E0D411103E0F5D1103E0F5E1103E0F5F1103E0F601103E0F761103E0F771103E0F781103E0F901103E0F911103E0FDE1073E0FE01073E0FE11073E12021103E12041103E12051103E12061103E12071103E12091103E120A1103E120C1103E120D1103E120E1103E12101103E12111103E12121103E16782123E16792123E167A1073E167B2123E167C2123E167D2123E167E2123E167F2123E16802123E16812123E16822123E16832123E16841073E16852123E16862123E16872123E16882123E16892123E168A2123E37C81263FF3822274400272284400281084400D01084400E222844032722844032822844032922844041D22844041E2284404AC2284404AF2284404B22284404B52284404B62284408901084408911084409961294409A510844B5AD43044B90613144C1E123244C1E223244C1E323244C1E423244C1E523244C1E823244C1E923244C1EA2324831234334843BB1344844C2134484607435484646435484A91236484A92236484A93236484A94236484A95236484A96236484AC0236484AC1236484B58134484FD6437484FD74374850CA2384853E21344853F61344856944374856F34374857B3134485B2F236485E4913448605B13448605C134488EC0139488EC1139488EC2139488EC3139488FE8139489EC1139489EC2139489EC3139489EC4139489EC5139489EC6139489EC7139489EC8139489EC9139489ECA139489ECB139489ECC139489ECD139489ECE139489ECF139489ED0139489ED1139489ED2139489ED3139489ED4139489ED5139489ED6139489ED7139489ED813948B0DE43348B15E43348B2C813948B2CA13948B2D014048B2D113948C0DE43348C15E43348C4DE43348C65E43348F3A524148F3A624148F3A724148F3A824148F3A924148F3AB24148F3AC24148F3AD24148F3AE241498B21242498B23242498B24242498B25242498B26242498B27242498B28242498B29242498B2E242498B30242498B31242498B32242498B33242498B342424B1A001434B1A011434B1A021434B42F21444B43AA1434B43AB1434B43AC1434B43AD1434B43AF1434B43B01434B43B11434B43C71434B43CB1434B43CD1434B43CE1434B43CF1434B43D01434B43D11434B43D21434B43D41434B43D51434B43D61434B43D71434B44101444D02851454D02871464D02881464D028A1464D028E1454D028F1454D02901454D02931454D02941454D02951454D03F12474D03FB247";
        static Map<String, Integer> roleDb; static List<String> roleOps;
        static File roleFile; static volatile long roleT; static volatile int roleN; static volatile String roleErr = ""; static volatile boolean roleBusy;
        /** eingebaute Liste + (falls vorhanden) die zuletzt geladene, aktuellere Liste aus dem Internet */
        static synchronized void roleLoad() {
            Map<String, Integer> m = new HashMap<>(); List<String> ops = new ArrayList<>(Arrays.asList(ROLE_OP));
            for (int i = 0; i + 9 <= ROLE_DB.length(); i += 9) m.put(ROLE_DB.substring(i, i + 6), Integer.parseInt(ROLE_DB.substring(i + 6, i + 9)));
            roleT = 0;
            try { if (roleFile != null && roleFile.isFile()) {
                    for (String ln : new String(java.nio.file.Files.readAllBytes(roleFile.toPath()), StandardCharsets.UTF_8).split("\n")) {
                        String[] a = ln.split(";", -1); if (a.length < 3 || a[0].length() != 6) continue;
                        int oi = ops.indexOf(a[2]); if (oi < 0) { ops.add(a[2]); oi = ops.size() - 1; }
                        m.put(a[0], Integer.parseInt(a[1]) * 1000 + oi);
                    }
                    roleT = roleFile.lastModified(); } } catch (Exception ignored) { }
            // eingebaute Einträge: Art*100+Nr. → einheitlich Art*1000+Nr.
            for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() < 1000 && e.getValue() >= 100) e.setValue((e.getValue() / 100) * 1000 + e.getValue() % 100);
            roleDb = m; roleOps = ops; roleN = m.size();
        }
        static synchronized int roleDbGet(String hex) {
            if (roleDb == null) roleLoad();
            Integer v = roleDb.get(hex.toUpperCase(Locale.ROOT)); return v == null ? -1 : v;
        }
        static final long[][] ROLE_RNG = {{0x3C0000, 0x3FFFFF}, {0x440000, 0x447FFF}, {0x4B0000, 0x4B7FFF}, {0x480000, 0x487FFF}, {0x448000, 0x44FFFF}, {0x4D0000, 0x4D03FF}, {0x380000, 0x3BFFFF}, {0x458000, 0x45FFFF}, {0x498000, 0x49FFFF}, {0x488000, 0x48FFFF}};
        /** Liste aus plane-alert-db neu holen (Polizei, Luftrettung, SAR in DE und Nachbarländern) – automatisch alle 3 Monate, sonst per Knopf */
        static void roleUpdate(Context c, boolean force) {
            if (roleBusy) return;
            if (roleFile == null) roleFile = new File(c.getFilesDir(), "einsatzflieger.txt");
            if (!force && roleFile.isFile() && System.currentTimeMillis() - roleFile.lastModified() < 90L * 24 * 3600 * 1000) return;
            roleBusy = true;
            new Thread(() -> {
                try {
                    String csv = Watch.get("https://raw.githubusercontent.com/sdr-enthusiasts/plane-alert-db/main/plane-alert-db.csv");
                    java.util.regex.Pattern resc = java.util.regex.Pattern.compile("(?i)ADAC|DRF|Luftrettung|Rega|Swiss Air.?Rescue|OAMTC|ÖAMTC|Christophorus|Johanniter|Ambulan|Rescue|Medical|HEMS|SAMU|Air Zermatt|Heli Austria|ANWB|Lifeliner|Tyrol Air");
                    StringBuilder out = new StringBuilder(); int n = 0;
                    String[] lines = csv.split("\r?\n");
                    for (int li = 1; li < lines.length; li++) {
                        List<String> f = csvRow(lines[li]); if (f.size() < 10) continue;
                        String hex = f.get(0).trim().toUpperCase(Locale.ROOT); if (!hex.matches("[0-9A-F]{6}")) continue;
                        long v = Long.parseLong(hex, 16); boolean in = false; for (long[] r : ROLE_RNG) if (v >= r[0] && v <= r[1]) in = true; if (!in) continue;
                        String reg = f.get(1).trim(), op = f.get(2).trim(), cmpg = f.get(5).trim(), cat = f.get(9).trim();
                        int role = cmpg.equals("Pol") ? 2 : cat.equals("Coastguard") || op.matches("(?i).*(Search and Rescue|\\bSAR\\b|Seenot).*") ? 4 : cat.equals("Flying Doctors") || resc.matcher(op).find() ? 1 : 0;
                        if (role == 0) continue;
                        if (role == 2) { String st = null; for (String[] p : POL_REG) if (reg.toUpperCase(Locale.ROOT).startsWith(p[0])) { st = p[1]; break; }
                            op = st != null ? st : op.equals("Bundespolizei") && hex.startsWith("3") ? "Polizei" : op; }
                        out.append(hex).append(';').append(role).append(';').append(op.replace(";", " ").replace("Police of Bayern", "Polizei Bayern").replace("Austrian Police", "Polizei Österreich")).append('\n'); n++;
                    }
                    if (n < 100) throw new Exception("Liste unvollständig (" + n + ")");   // lieber die alte behalten
                    File tmp = new File(roleFile.getPath() + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8)); tmp.renameTo(roleFile);
                    roleErr = ""; roleLoad();
                } catch (Exception e) { roleErr = String.valueOf(e.getMessage()); }
                finally { roleBusy = false; }
            }).start();
        }
        /** eine CSV-Zeile mit Anführungszeichen-Feldern */
        static List<String> csvRow(String ln) {
            List<String> f = new ArrayList<>(); StringBuilder b = new StringBuilder(); boolean q = false;
            for (int i = 0; i < ln.length(); i++) { char ch = ln.charAt(i);
                if (q) { if (ch == '"') { if (i + 1 < ln.length() && ln.charAt(i + 1) == '"') { b.append('"'); i++; } else q = false; } else b.append(ch); }
                else if (ch == '"') q = true; else if (ch == ',') { f.add(b.toString()); b.setLength(0); } else b.append(ch); }
            f.add(b.toString()); return f;
        }
        /** Kennzeichen-Blöcke der Polizeihubschrauber (Länder + Bundespolizei) – gilt nur für Hubschrauber */
        static final String[][] POL_REG = {{"D-HBP", "Polizei Bayern"}, {"D-HBY", "Polizei Bayern"}, {"D-HPB", "Polizei Bayern"}, {"D-HMU", "Polizei Bayern"}, {"D-HBSI", "Polizei Bayern"}, {"D-HBIV", "Polizei Bayern"},
                {"D-HBW", "Polizei Baden-Württemberg"}, {"D-HBO", "Polizei Baden-Württemberg"}, {"D-HIBC", "Polizei Baden-Württemberg"}, {"D-HHEA", "Polizei Hessen"}, {"D-HHEB", "Polizei Hessen"}, {"D-HHEC", "Polizei Hessen"}, {"D-HHEL", "Polizei Hessen"}, {"D-HAMY", "Polizei Hessen"},
                {"D-HNW", "Polizei Nordrhein-Westfalen"}, {"D-HMT", "Polizei Nordrhein-Westfalen"}, {"D-HAIR", "Polizei Nordrhein-Westfalen"}, {"D-HMV", "Polizei Mecklenburg-Vorpommern"}, {"D-HAY", "Polizei Rheinland-Pfalz"}, {"D-HRP", "Polizei Rheinland-Pfalz"},
                {"D-HBB", "Polizei Brandenburg"}, {"D-HPN", "Polizei Niedersachsen"}, {"D-HSN", "Polizei Sachsen"}, {"D-HSA", "Polizei Sachsen-Anhalt"}, {"D-HTH", "Polizei Thüringen"}, {"D-HARU", "Polizei Hamburg"},
                {"D-HVB", "Bundespolizei"}, {"D-HLT", "Bundespolizei"}, {"D-HEG", "Bundespolizei"}};
        /** Funkrufnamen der Polizeistaffeln (in ADS-B oft abgekürzt, z. B. „EDL1“ = Edelweiss 1) – nur für Hubschrauber */
        static final String[][] POL_CALL = {{"IBIS", "Polizei Hessen"}, {"EDL|EDLW|EDELW\\w*|EDW", "Polizei Bayern"}, {"BUS|BUSS|BUSSARD|BUSARD", "Polizei Baden-Württemberg"}, {"HUM|HUMM|HUMMEL", "Polizei Nordrhein-Westfalen"},
                {"PHX|PHOE|PHOEN|PHOENIX|PHONIX", "Polizei Niedersachsen"}, {"SPE|SPERB|SPERBER", "Polizei Rheinland-Pfalz"}, {"PAS|PASS|PASSAT", "Polizei Sachsen"}, {"IKA|IKAR|IKARUS", "Polizei Sachsen-Anhalt"},
                {"LIB|LIBE|LIBEL|LIBELLE", "Polizei Hamburg"}, {"MER|MERL|MERLIN", "Polizei Mecklenburg-Vorpommern"}, {"PIR|PIRO|PIROL", "Polizei Berlin"}, {"ADL|ADLER", "Polizei Brandenburg"}, {"PHH|PPH|HBT", "Polizei"}};
        /** Art und Betreiber: zuerst die eingebaute Liste (ICAO-Adresse), dann Kennzeichen-Block, Rufname, Betreiberangabe, Militär-Kennung */
        static Object[] roleOf(String hex, String fl, String reg, String op, int dbFlags, boolean heli) {
            int v = roleDbGet(hex);
            if (v >= 0) return new Object[]{v / 1000, roleOps.get(v % 1000)};
            String r = reg.toUpperCase(Locale.ROOT), f = fl.toUpperCase(Locale.ROOT);
            if (heli) {
                for (String[] p : POL_REG) if (r.startsWith(p[0])) return new Object[]{2, p[1]};
                for (String[] p : POL_CALL) if (f.matches("(" + p[0] + ")\\d{0,3}")) return new Object[]{2, p[1]};
            }
            int ro = role(fl, op, dbFlags);
            if (ro == 1 && op.isEmpty() && f.matches("CHX\\d+")) return new Object[]{1, "Luftrettung · Christoph " + f.substring(3)};
            return new Object[]{ro, ro > 0 ? op : ""};
        }
        /** Einsatzart: 1 Rettung, 2 Polizei, 3 Militär, 4 Such- und Rettung (SAR), 0 sonst – aus Rufzeichen, Betreiber und Militär-Kennung der ADS-B-Datenbank */
        static int role(String fl, String op, int dbFlags) {
            String f = fl.toUpperCase(Locale.ROOT), o = op.toUpperCase(Locale.ROOT);
            if (f.startsWith("SAR") || o.contains("SEARCH AND RESCUE") || o.contains("SEENOT")) return 4;
            // Rettung: „Christoph NN“ sendet in ADS-B „CHX“ + Nummer; dazu Rega (SAZ), Ambulanz-/HEMS-Rufzeichen und Betreiber
            if (f.matches("(CHX|CHRIS|CHRISTO|CHRISTOPH|SAZ|AMB|HEMS|RESQ|RESCUE|ITH)\\d.*") || f.startsWith("CHRISTOPH") || o.contains("OAMTC") || o.contains("ÖAMTC") || o.contains("CHRISTOPHORUS") || o.contains("REGA") || o.contains("FLUGRETTUNG") || o.contains("LUFTRETTUNG") || o.contains("ADAC") || o.contains("DRF ") || o.startsWith("DRF")
                    || o.contains("JOHANNITER") || o.contains("RESCUE") || o.contains("HEMS") || o.contains("AMBULAN") || o.contains("NOTARZT") || o.contains("RETTUNG")) return 1;
            // Polizei: Staffel-Kürzel und Funkrufnamen der Länder (Hessen „Ibis“, Bayern „Edelweiss“, BW „Bussard“, NRW „Hummel“ …)
            if (f.matches("(PHH|PPH|HBT)\\d.*") || f.matches("(IBIS|EDELW\\w*|EDLW\\w*|BUSSARD|BUSARD|HUMMEL|PIROL|LIBELLE|LIBEL|MERLIN|PHOENIX|PHONIX|SPERBER|PASSAT|IKARUS)\\d{0,3}")
                    || o.contains("POLIZEI") || o.contains("POLICE") || o.contains("BUNDESPOLIZEI") || o.contains("POLIS") || o.contains("GENDARM")) return 2;
            if ((dbFlags & 1) != 0 || f.startsWith("GAF") || f.startsWith("GAM") || f.startsWith("RCH") || f.startsWith("GNY") || f.startsWith("NATO") || o.contains("BUNDESWEHR") || o.contains("LUFTWAFFE")
                    || o.contains("AIR FORCE") || o.contains("ARMY") || o.contains("NAVY") || o.contains("MILITARY") || o.contains("HEERESFLIEGER") || o.contains("MARINE")) return 3;
            return 0;
        }
        static String roleName(int role, int typ, String art) {
            boolean h = typ == 3;
            switch (role) { case 1: return h ? "Rettungshubschrauber" : "Ambulanzflugzeug"; case 2: return h ? "Polizeihubschrauber" : "Polizeiflugzeug";
                case 3: return h ? "Militärhubschrauber" : typ == 9 ? "Militärjet" : "Militärflugzeug"; case 4: return h ? "SAR-Hubschrauber" : "SAR-Flugzeug"; default: return art; }
        }
        static final String[] TYP = {"Luftfahrzeug", "Segelflugzeug", "Schleppflugzeug", "Hubschrauber", "Fallschirmspringer", "Absetzflugzeug", "Drachen", "Gleitschirm", "Motorflugzeug", "Jet", "Luftfahrzeug", "Ballon", "Luftschiff", "Drohne", "Bodenfahrzeug", "Objekt"};
        static String json() {
            StringBuilder sb = new StringBuilder("{\"q\":").append(Double.isNaN(qnh) ? "null" : String.valueOf(Math.round(qnh))).append(",\"g\":").append(Double.isNaN(ground) ? "null" : String.valueOf(Math.round(ground))).append(",\"err\":").append(JSONObject.quote((err + " " + adsbErr).trim())).append(",\"via\":").append(JSONObject.quote(adsbSrc)).append(",\"n\":").append(ac.size()).append(",\"ac\":[");
            long now = System.currentTimeMillis(); boolean f = true;
            for (Map.Entry<String, double[]> e : ac.entrySet()) {
                double[] v = e.getValue(); if (now - (long) v[7] > 90000) continue;
                if (!f) sb.append(','); f = false;
                String[] L = (label.getOrDefault(e.getKey(), "") + "||||||").split("\\|", -1);
                String lb = (L[0] + " " + L[1]).trim(); if (lb.isEmpty()) lb = L[2];
                int role = 0; try { role = Integer.parseInt(L[5]); } catch (Exception ignored) { }
                if (role > 0 && !L[6].isEmpty()) lb = L[6] + " · " + (L[0].isEmpty() ? L[2] : L[0]);   // z. B. „Polizei Bayern · EDL1“
                sb.append(String.format(Locale.ROOT, "{\"role\":%d,\"op\":%s,", role, JSONObject.quote(L[6])));
                sb.append(String.format(Locale.ROOT, "\"id\":%s,\"la\":%.5f,\"lo\":%.5f,\"alt\":%s,\"trk\":%.0f,\"kmh\":%.0f,\"vs\":%.1f,\"typ\":%d,\"age\":%d,\"src\":%d,\"lb\":%s,\"fl\":%s,\"tc\":%s,\"reg\":%s,\"desc\":%s,\"hex\":%s}",
                        JSONObject.quote(e.getKey()), v[0], v[1], Double.isNaN(v[2]) ? "null" : String.valueOf(Math.round(v[2])), v[3], v[4], v[5], (int) v[6], (now - (long) v[7]) / 1000, (int) v[8],
                        JSONObject.quote(lb), JSONObject.quote(L[0]), JSONObject.quote(L[1]), JSONObject.quote(L[2]), JSONObject.quote(L[3]), JSONObject.quote(L[4])));
            }
            return sb.append("]}").toString();
        }
    }

    public static class Watch extends android.app.Service {
        static volatile Thread th; static volatile String cfg; static volatile String lastInfo = "";
        @Override public android.os.IBinder onBind(Intent i) { return null; }
        @SuppressWarnings("deprecation")
        @Override public int onStartCommand(Intent in, int flags, int startId) {
            if (in != null && "stop".equals(in.getAction())) { Thread t = th; th = null; if (t != null) t.interrupt(); lastInfo = "beendet"; stopForeground(true); stopSelf(); return START_NOT_STICKY; }
            if (in != null && in.getStringExtra("cfg") != null) cfg = in.getStringExtra("cfg");
            if (cfg == null) { stopSelf(); return START_NOT_STICKY; }
            ensureChannels(this); Elev.init(getFilesDir());
            String names = "", until = ""; boolean tOnly = false; String tInfo = "";
            try { JSONObject c = new JSONObject(cfg); names = c.optString("names"); tOnly = c.optBoolean("trafOnly");
                tInfo = "Warnt bei Luftfahrzeugen im Umkreis von " + String.format(Locale.GERMANY, "%.1f", c.optDouble("tKm", 3)).replace(",0", "") + " km unter " + c.optInt("tAlt", 300) + " m über Grund"; until = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new java.util.Date(c.optLong("until"))); } catch (Exception ignored) { }
            android.app.PendingIntent ps = android.app.PendingIntent.getService(this, 1, new Intent(this, Watch.class).setAction("stop"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = nb(this, tOnly ? CH_SVC : CH_RUN).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle(tOnly ? "Luftverkehr-Warnung aktiv" : "Einsatz-Wächter aktiv bis " + until)
                    .setContentText(tOnly ? tInfo : "Prüft alle 5 Minuten Regen, Wind, Scherwind, Kp" + (names.isEmpty() ? "" : " · " + names)).setOngoing(true)
                    .setContentIntent(openApp(this)).addAction(new android.app.Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Beenden", ps).build()).build();
            boolean ridOn = false; try { ridOn = new JSONObject(cfg).optBoolean("rid"); } catch (Exception ignored) { }
            try { if (Build.VERSION.SDK_INT >= 29) {
                    // Drohnen-Suche (Bluetooth/WLAN) braucht im Hintergrund den Standort-Typ
                    try { startForeground(77, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | (ridOn ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0)); }
                    catch (Exception e1) { startForeground(77, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
                } else startForeground(77, n); }
            catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
            Thread t = th;
            if (t == null || !t.isAlive()) { th = new Thread(this::loop); th.start(); }
            return START_STICKY;
        }
        @SuppressWarnings("deprecation")
        void loop() {
            Map<String, Long> sent = new HashMap<>(); Map<String, Double> lastD = new HashMap<>();
            Thread me = Thread.currentThread();
            long nextWx = 0;
            while (th == me) {
                JSONObject c = null;
                try { c = new JSONObject(cfg); } catch (Exception ignored) { }
                if (c == null || System.currentTimeMillis() > c.optLong("until")) break;
                if (!c.optBoolean("trafOnly") && System.currentTimeMillis() >= nextWx) {   // Wetter alle 5 Minuten (nicht im reinen Luftverkehr-Modus)
                    nextWx = System.currentTimeMillis() + 5 * 60000L;
                    try { check(c, sent); lastInfo = "zuletzt geprüft " + new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new java.util.Date()); }
                    catch (Exception e) { lastInfo = "Prüfung fehlgeschlagen: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                }
                if (c.optBoolean("traffic") || c.optBoolean("rid")) { try { checkTraffic(c, sent, lastD); } catch (Exception ignored) { } }   // Luftverkehr / Drohnen alle 5 s
                else if (Traffic.watchOn || Rid.watchOn) { Traffic.watchOn = false; Traffic.stopIfIdle(); Rid.watchOn = false; Rid.stopIfIdle(); trafClearAll(); }
                try { Thread.sleep(c.optBoolean("traffic") || c.optBoolean("rid") ? 5000L : 15000L); } catch (InterruptedException e) { break; }
            }
            Traffic.watchOn = false; Traffic.stopIfIdle(); Rid.watchOn = false; Rid.stopIfIdle(); trafClearAll();
            if (th == me) { th = null; lastInfo = "abgelaufen"; stopForeground(true); stopSelf(); }
        }
        /** Luftfahrzeuge im Warnumkreis und unter der Warnhöhe (über Grund) melden */
        void checkTraffic(JSONObject c, Map<String, Long> sent, Map<String, Double> lastD) {
            double la = c.optDouble("lat"), lo = c.optDouble("lon"), km = c.optDouble("tKm", 3), maxAgl = c.optDouble("tAlt", 500);
            boolean tr = c.optBoolean("traffic");
            // App offen: sie bestimmt das Abfragegebiet (Kartenausschnitt inkl. deines Standorts) – sonst springt die Abfrage hin und her
            if (tr) { Traffic.watchOn = true; if (!Traffic.uiOn) Traffic.start(la, lo, Math.max(25, km * 4)); } else if (Traffic.watchOn) { Traffic.watchOn = false; Traffic.stopIfIdle(); }
            double g = Elev.cached(la, lo); if (Double.isNaN(g)) g = Double.isNaN(Traffic.ground) ? c.optDouble("elev", Double.NaN) : Traffic.ground;
            long now = System.currentTimeMillis(); java.util.Set<String> inZone = new java.util.HashSet<>();
            double bD = 1e9, bBrg = 0; String bKey = null, bT = "", bS = ""; int nT = 0; boolean fresh = false;
            // Mit Warnfenster über allen Apps keine Einträge in der Benachrichtigungszentrale – Fenster + Warnton reichen
            noNotif = c.optBoolean("ovl", true) && (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)); List<double[]> tg = new ArrayList<>();   // Radar: {Peilung, Anteil am Umkreis, Kurs, Art 0 Flz/1 Hubschr./2 Drohne}
            for (Map.Entry<String, double[]> e : tr ? Traffic.ac.entrySet() : Collections.<Map.Entry<String, double[]>>emptySet()) {
                double[] v = e.getValue(); if (now - (long) v[7] > 60000) continue;
                double dy = (v[0] - la) * 111.2, dx = (v[1] - lo) * 111.2 * Math.cos(Math.toRadians(la)), d = Math.hypot(dx, dy);
                Double prev = lastD.put(e.getKey(), d);
                boolean was = trafActive.containsKey(e.getKey());
                double kmLim = was ? km * 1.1 + 0.1 : km;   // kleine Hysterese: Meldung flackert nicht am Rand des Umkreises
                if (d > kmLim) continue;
                // Geländehöhe nicht abrufbar: mit 600 m rechnen (höher als fast alles in Mittelgebirgslagen) → eher zu früh als zu spät warnen
                double agl = Double.isNaN(v[2]) ? Double.NaN : v[2] - (Double.isNaN(g) ? 600 : g);   // Höhe unbekannt → sicherheitshalber warnen …
                if (Double.isNaN(agl) && v[4] > 450) continue;   // … außer bei Reisefluggeschwindigkeit (dann ist es hoch oben)
                if (!Double.isNaN(agl) && agl > maxAgl + (was ? 50 : 0)) continue;
                int typ = (int) v[6]; String art = Traffic.TYP[Math.max(0, Math.min(15, typ))];
                String dir = dir(Math.toDegrees(Math.atan2(dx, dy)));
                boolean near = prev != null && d < prev - 0.05;
                String[] L = (Traffic.label.getOrDefault(e.getKey(), "") + "||||||").split("\\|", -1);
                String lb = (L[0] + " " + L[1]).trim(); if (lb.isEmpty()) lb = L[2];
                art = Traffic.roleName(L[5].matches("[0-4]") ? Integer.parseInt(L[5]) : 0, typ, art);
                // Meldung bleibt stehen, solange das Luftfahrzeug im Umkreis und unter der Warnhöhe ist, und wird laufend aktualisiert (Ton nur beim ersten Mal)
                int id = 1000 + Math.abs(e.getKey().hashCode() % 500);
                if (!was) fresh = true;
                inZone.add(e.getKey()); trafActive.put(e.getKey(), id);
                trafNotify(id, CH_TRAF[Math.max(0, Math.min(2, c.optInt("tSnd", 1)))], (typ == 3 ? "🚁 " : "✈ ") + art + " " + f1(d) + " km " + dir + (near ? " – kommt näher" : ""),
                        (Double.isNaN(agl) ? "Höhe unbekannt" : "ca. " + Math.round(Math.max(0, agl)) + " m über Grund") + (v[4] > 0 ? " · " + Math.round(v[4]) + " km/h" : "") + (lb.isEmpty() ? "" : " · " + lb)
                        + ". Luftraum beobachten, Drohne tief halten bzw. landen.");
                nT++; tg.add(new double[]{Math.toDegrees(Math.atan2(dx, dy)), Math.min(1, d / km), v[3], typ == 3 ? 1 : 0, 0});
                if (d < bD && !ovlMuted(e.getKey())) { bD = d; bKey = e.getKey(); bBrg = Math.toDegrees(Math.atan2(dx, dy));
                    bT = art + " · " + f1(d) + " km" + (near ? " · kommt näher" : "");
                    bS = (Double.isNaN(agl) ? "Höhe unbekannt" : "ca. " + Math.round(Math.max(0, agl)) + " m über Grund") + (v[4] > 0 ? " · " + Math.round(v[4]) + " km/h" : "") + (lb.isEmpty() ? "" : " · " + lb); }
            }
            // andere Drohnen (Remote ID, ohne Internet)
            if (c.optBoolean("rid")) {
                Rid.watchOn = true; Rid.start(this);
                double rKm = c.optDouble("rKm", 1); String own = "," + c.optString("ridOwn", "") + ",";
                for (Map.Entry<String, double[]> e : Rid.st.entrySet()) {
                    double[] v = e.getValue(); if (v[9] == 0 || now - (long) v[9] > 30000 || Double.isNaN(v[0])) continue;
                    if (own.contains("," + e.getKey() + ",")) continue;   // eigene Drohne
                    double dy = (v[0] - la) * 111.2, dx = (v[1] - lo) * 111.2 * Math.cos(Math.toRadians(la)), d = Math.hypot(dx, dy);
                    String k = "rid" + e.getKey(); boolean was = trafActive.containsKey(k);
                    if (d > (was ? rKm * 1.1 + 0.05 : rKm)) continue;
                    Double prev = lastD.put(k, d); boolean near = prev != null && d < prev - 0.02;
                    int id = 1500 + Math.abs(k.hashCode() % 400);
                    if (!was) fresh = true;
                    inZone.add(k); trafActive.put(k, id);
                    String hs = Double.isNaN(v[3]) ? "Höhe unbekannt" : "ca. " + Math.round(v[3]) + " m über " + (v[4] == 1 ? "Grund" : "ihrem Startpunkt");
                    trafNotify(id, CH_TRAF[Math.max(0, Math.min(2, c.optInt("tSnd", 1)))], "🛸 Andere Drohne " + (d < 1 ? Math.round(d * 1000) + " m" : f1(d) + " km") + " " + dir(Math.toDegrees(Math.atan2(dx, dy))) + (near ? " – kommt näher" : ""),
                            hs + (v[5] > 0 ? " · " + Math.round(v[5] * 3.6) + " km/h" : "") + ". Abstimmen bzw. Abstand halten.");
                    nT++; tg.add(new double[]{Math.toDegrees(Math.atan2(dx, dy)), Math.min(1, d / rKm), -1, 2, 0});
                    if (d < bD && !ovlMuted(k)) { bD = d; bKey = k; bBrg = Math.toDegrees(Math.atan2(dx, dy));
                        bT = "Andere Drohne · " + (d < 1 ? Math.round(d * 1000) + " m" : f1(d) + " km") + (near ? " · kommt näher" : "");
                        bS = hs + (v[5] > 0 ? " · " + Math.round(v[5] * 3.6) + " km/h" : ""); }
                }
            } else if (Rid.watchOn) { Rid.watchOn = false; Rid.stopIfIdle(); }
            // Warnfenster über allen Apps (wenn erlaubt und die App nicht selbst vorne ist – dort zeigt sie ihren eigenen Pfeil)
            for (double[] t : tg) if (Math.abs(t[0] - bBrg) < 1e-9) t[4] = 1;   // nächster = hervorgehoben
            if (fresh) beep(c.optInt("tSnd", 1));   // neu im Warnbereich: eigener, gut hörbarer Ton (unabhängig von den Benachrichtigungs-Einstellungen)
            if (bKey != null && c.optBoolean("ovl", true) && !appFg) ovlShow(bKey, bT, bS + (nT > 1 ? " · +" + (nT - 1) + " weitere" : ""), tg, la, lo);
            else ovlHide();
            // aus dem Bereich heraus (oder keine Meldung mehr seit 60 s): Benachrichtigung entfernen
            for (java.util.Iterator<Map.Entry<String, Integer>> it = trafActive.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Integer> a = it.next();
                if (!inZone.contains(a.getKey())) { trafCancel(a.getValue()); it.remove(); }
            }
        }
        final Map<String, Integer> trafActive = new HashMap<>();
        long lastBeep;
        /** Warnton über den Wecker-Lautstärkekanal: 1 = zwei kurze Töne, 2 = Alarm (ca. 3 s) */
        void beep(int snd) {
            if (snd <= 0 || System.currentTimeMillis() - lastBeep < 4000) return; lastBeep = System.currentTimeMillis();
            new Thread(() -> {
                android.media.ToneGenerator tg = null;
                try {
                    tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
                    if (snd >= 2) { for (int i = 0; i < 3; i++) { tg.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900); Thread.sleep(1000); } }
                    else { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(700); tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400); Thread.sleep(600); }
                } catch (Exception ignored) { } finally { if (tg != null) try { tg.release(); } catch (Exception ignored) { } }
            }).start();
        }
        // ---------- Warnfenster über allen Apps (Berechtigung „Über anderen Apps einblenden“) ----------
        android.view.View ovl; android.widget.TextView ovlT, ovlS, ovlH; ArrowView ovlA; String ovlKey; final Map<String, Long> ovlMute = new java.util.concurrent.ConcurrentHashMap<>();
        volatile double hdg = Double.NaN; android.hardware.SensorEventListener sens; float decl = 2.5f;
        boolean ovlMuted(String k) { Long t = ovlMute.get(k); return t != null && System.currentTimeMillis() - t < 3 * 60000L; }
        /** kleines Radar: Mitte = RC, oben = Blickrichtung (mit Kompass) bzw. Norden, Rand = Warnumkreis */
        static class ArrowView extends android.view.View {
            volatile List<double[]> tg = new ArrayList<>(); volatile double hd; boolean compass;
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); final android.graphics.Path a = new android.graphics.Path();
            ArrowView(Context c) { super(c); }
            @Override protected void onDraw(android.graphics.Canvas cv) {
                float w = getWidth(), c = w / 2f, R = c - w * .04f;
                p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0xFF0D1720); cv.drawCircle(c, c, c, p);
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(w * .012f); p.setColor(0x8CFFD000); cv.drawCircle(c, c, R, p);
                p.setColor(0x2EFFFFFF); cv.drawCircle(c, c, (float) (R * Math.sqrt(.5)), p); cv.drawLine(c - R, c, c + R, c, p); cv.drawLine(c, c - R, c, c + R, p);
                double nr = Math.toRadians(-hd);
                p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0xFFFF5252); p.setTextSize(w * .12f); p.setTextAlign(android.graphics.Paint.Align.CENTER); p.setFakeBoldText(true);
                cv.drawText("N", (float) (c + Math.sin(nr) * (R - w * .08f)), (float) (c - Math.cos(nr) * (R - w * .08f)) + w * .04f, p);
                a.reset(); a.moveTo(c, c - w * .065f); a.lineTo(c + w * .045f, c + w * .045f); a.lineTo(c, c + w * .018f); a.lineTo(c - w * .045f, c + w * .045f); a.close(); p.setColor(0xFF4DA3FF); cv.drawPath(a, p);
                for (double[] t : tg) {
                    double r = Math.toRadians(t[0] - hd), rr = Math.max(w * .1, Math.sqrt(t[1]) * R);
                    float x = (float) (c + Math.sin(r) * rr), y = (float) (c - Math.cos(r) * rr), s = w * (t[4] == 1 ? .075f : .055f);
                    cv.save(); cv.translate(x, y);
                    if (t[4] == 1) { p.setStyle(android.graphics.Paint.Style.STROKE); p.setColor(0xFFFFFFFF); p.setStrokeWidth(w * .012f); cv.drawCircle(0, 0, s * 1.6f, p); }
                    p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(0xFFFFD000);
                    if (t[3] == 1) { cv.drawCircle(0, 0, s * .6f, p); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(w * .012f); cv.drawLine(-s, -s, s, s, p); cv.drawLine(-s, s, s, -s, p); }   // Hubschrauber
                    else if (t[3] == 2) { cv.drawRect(-s * .6f, -s * .6f, s * .6f, s * .6f, p); }   // Drohne
                    else { if (t[2] >= 0) cv.rotate((float) (t[2] - hd)); a.reset(); a.moveTo(0, -s); a.lineTo(s * .75f, s * .8f); a.lineTo(0, s * .35f); a.lineTo(-s * .75f, s * .8f); a.close(); cv.drawPath(a, p); }
                    cv.restore();
                }
            }
        }
        float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        volatile List<double[]> ovlTg = new ArrayList<>();
        void ovlShow(String key, String t, String sub, List<double[]> tg, double la, double lo) {
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;
            ovlTg = tg; ovlKey = key;
            try { decl = new android.hardware.GeomagneticField((float) la, (float) lo, 0, System.currentTimeMillis()).getDeclination(); } catch (Exception ignored) { }
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (ovl == null) {
                        android.widget.LinearLayout row = new android.widget.LinearLayout(this); row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL); int pd = (int) dp(10); row.setPadding(pd, pd, (int) dp(6), pd);
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(0xF015202B); bg.setCornerRadius(dp(16)); bg.setStroke((int) dp(2), 0xFFFFD000); row.setBackground(bg);
                        ovlA = new ArrowView(this); row.addView(ovlA, new android.widget.LinearLayout.LayoutParams((int) dp(104), (int) dp(104)));
                        android.widget.LinearLayout col = new android.widget.LinearLayout(this); col.setOrientation(android.widget.LinearLayout.VERTICAL); col.setPadding((int) dp(12), 0, (int) dp(8), 0);
                        ovlT = new android.widget.TextView(this); ovlT.setTextColor(0xFFFFFFFF); ovlT.setTextSize(17); ovlT.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        ovlS = new android.widget.TextView(this); ovlS.setTextColor(0xFFDFE6EE); ovlS.setTextSize(14);
                        ovlH = new android.widget.TextView(this); ovlH.setTextColor(0xFF9FB0C0); ovlH.setTextSize(11);
                        col.addView(ovlT); col.addView(ovlS); col.addView(ovlH);
                        row.addView(col, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        android.widget.TextView x = new android.widget.TextView(this); x.setText("✕"); x.setTextColor(0xFF9FB0C0); x.setTextSize(22); x.setPadding((int) dp(12), (int) dp(4), (int) dp(10), (int) dp(4));
                        x.setOnClickListener(v -> { if (ovlKey != null) ovlMute.put(ovlKey, System.currentTimeMillis()); ovlHide(); });
                        row.addView(x);
                        row.setOnClickListener(v -> { try { openApp(this).send(); } catch (Exception ignored) { } ovlHide(); });
                        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                                (int) Math.min(dp(620), getResources().getDisplayMetrics().widthPixels * .94f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                Build.VERSION.SDK_INT >= 26 ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT);
                        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; lp.y = (int) dp(56);
                        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).addView(row, lp); ovl = row;
                        sensOn();
                    }
                    ovlT.setText("⚠ " + t); ovlS.setText(sub); ovlUpd();
                } catch (Exception ignored) { ovl = null; }
            });
        }
        void ovlUpd() {
            if (ovlA == null) return;
            boolean c = !Double.isNaN(hdg); ovlA.compass = c; ovlA.hd = c ? hdg : 0; ovlA.tg = ovlTg; ovlA.invalidate();
            if (ovlH != null) ovlH.setText("Radar: Rand = Warnumkreis · " + (c ? "oben = deine Blickrichtung" : "kein Kompass – oben = Norden") + " · antippen öffnet die App");
        }
        void ovlHide() {
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                try { if (ovl != null) ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(ovl); } catch (Exception ignored) { }
                ovl = null; ovlA = null; sensOff();
            });
        }
        final float[] rm = new float[9], rr = new float[9], or = new float[3];
        void sensOn() {
            try {
                android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
                android.hardware.Sensor rv = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
                if (rv == null || sens != null) return;
                sens = new android.hardware.SensorEventListener() {
                    @Override public void onSensorChanged(android.hardware.SensorEvent ev) {
                        try {   // wie in der App: Oberkante/Rückseite der RC auf die Waagerechte projiziert, Bildschirmdrehung berücksichtigt
                            android.hardware.SensorManager.getRotationMatrixFromVector(rm, ev.values);
                            int rot = ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
                            int ax = android.hardware.SensorManager.AXIS_X, ay = android.hardware.SensorManager.AXIS_Y, mx = android.hardware.SensorManager.AXIS_MINUS_X, my = android.hardware.SensorManager.AXIS_MINUS_Y;
                            if (rot == android.view.Surface.ROTATION_90) android.hardware.SensorManager.remapCoordinateSystem(rm, ay, mx, rr);
                            else if (rot == android.view.Surface.ROTATION_180) android.hardware.SensorManager.remapCoordinateSystem(rm, mx, my, rr);
                            else if (rot == android.view.Surface.ROTATION_270) android.hardware.SensorManager.remapCoordinateSystem(rm, my, ax, rr);
                            else System.arraycopy(rm, 0, rr, 0, 9);
                            double e = rr[1] - rr[2], n = rr[4] - rr[5], az;
                            if (Math.hypot(e, n) > 0.2) az = Math.toDegrees(Math.atan2(e, n)); else { android.hardware.SensorManager.getOrientation(rr, or); az = Math.toDegrees(or[0]); }
                            double h = (az + decl + 720) % 360;
                            if (Double.isNaN(hdg)) hdg = h; else { double df = ((h - hdg + 540) % 360) - 180; hdg = (hdg + df * 0.2 + 360) % 360; }
                            ovlUpd();
                        } catch (Exception ignored) { }
                    }
                    @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
                };
                sm.registerListener(sens, rv, android.hardware.SensorManager.SENSOR_DELAY_UI);
            } catch (Exception ignored) { }
        }
        void sensOff() { try { if (sens != null) ((android.hardware.SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(sens); } catch (Exception ignored) { } sens = null; hdg = Double.NaN; }
        void trafCancel(int id) { try { ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id); } catch (Exception ignored) { } }
        void trafClearAll() { for (int id : trafActive.values()) trafCancel(id); trafActive.clear(); ovlHide(); }
        @SuppressWarnings("deprecation")
        volatile boolean noNotif;
        void trafNotify(int id, String ch, String title, String text) {
            if (noNotif) { trafCancel(id); return; }
            try {
                android.app.Notification.Builder b = nb(this, ch).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text)
                        .setStyle(new android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(openApp(this)).setOnlyAlertOnce(true)
                        .setCategory(android.app.Notification.CATEGORY_ALARM).setWhen(System.currentTimeMillis()).setShowWhen(true);
                if (Build.VERSION.SDK_INT < 26) b.setPriority(android.app.Notification.PRIORITY_HIGH).setDefaults(android.app.Notification.DEFAULT_ALL);
                ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
            } catch (Exception ignored) { }
        }
        void onceCh(Map<String, Long> sent, String key, String ch, String title, String text, long pause) {
            long now = System.currentTimeMillis(); Long t = sent.get(key);
            if (t != null && now - t < pause) return;
            sent.put(key, now);
            alert(this, 1000 + Math.abs(key.hashCode() % 500), title, text, ch);
        }
        /** Einmal melden – dieselbe Warnung frühestens nach „pause“ wieder */
        void once(Map<String, Long> sent, String key, String title, String text, long pause) {
            long now = System.currentTimeMillis(); Long t = sent.get(key);
            if (t != null && now - t < pause) return;
            sent.put(key, now);
            alert(this, 1000 + Math.abs(key.hashCode() % 500), title, text);
        }
        static String dir(double d) { String[] n = {"N", "NO", "O", "SO", "S", "SW", "W", "NW"}; return n[(int) Math.round(((d % 360) + 360) % 360 / 45) % 8]; }
        static String f1(double v) { return String.format(Locale.GERMANY, "%.1f", v); }
        static String get(String url) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try { c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Kitzrettung, ehrenamtlich)");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { return readAll(in); } } finally { c.disconnect(); }
        }
        void check(JSONObject c, Map<String, Long> sent) throws Exception {
            long now = System.currentTimeMillis();
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
            // feste Zeitpunkte aus der App (Beginn Tiefflug-Betriebszeit, NOTAM an einer Wiese): ca. 10 Minuten vorher melden
            JSONArray ev = c.optJSONArray("ev");
            if (ev != null) for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.getJSONObject(i); long t = e.optLong("t");
                if (t - now <= 11 * 60000L && t - now > -10 * 60000L) once(sent, "ev" + t + e.optString("k"), e.optString("title"), e.optString("txt"), 24 * 3600000L);
            }
            double la = c.getDouble("lat"), lo = c.getDouble("lon"); int h = c.optInt("h", 80) >= 100 ? 120 : 80;
            String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", la, lo)
                    + "&minutely_15=precipitation,wind_gusts_10m&hourly=wind_speed_10m,wind_direction_10m,wind_speed_" + h + "m,wind_direction_" + h + "m"
                    + "&forecast_days=2&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT";
            JSONObject j = new JSONObject(get(url));
            JSONObject m = j.optJSONObject("minutely_15");
            if (m != null) {
                JSONArray t = m.getJSONArray("time"), pr = m.optJSONArray("precipitation"), g = m.optJSONArray("wind_gusts_10m");
                boolean wetNow = false, rainDone = false, gustDone = false;
                for (int k = 0; k < t.length(); k++) {
                    long ts = t.getLong(k) * 1000;
                    if (ts + 15 * 60000L <= now || ts > now + 30 * 60000L) continue;
                    double p = pr != null ? pr.optDouble(k, 0) : 0, gu = g != null ? g.optDouble(k, 0) : 0;
                    boolean cur = ts <= now;
                    if (c.optBoolean("rain", true) && !rainDone && p >= c.optDouble("rainMin", 0.1)) {
                        rainDone = true;
                        if (cur) wetNow = true;
                        else if (!wetNow) once(sent, "regen", "🌧 Regen ab " + hm.format(new java.util.Date(ts)), "Laut Kurzfrist-Vorhersage (DWD) " + f1(p) + " mm in 15 Minuten – Drohne rechtzeitig zurückholen und landen.", 45 * 60000L);
                    }
                    if (!gustDone && gu >= c.optDouble("gust", 10)) { gustDone = true;
                        once(sent, "boeen", "💨 Böen " + f1(gu) + " m/s " + (cur ? "jetzt" : "ab " + hm.format(new java.util.Date(ts))), "Böen in 10 m über eurem Grenzwert von " + f1(c.optDouble("gust", 10)) + " m/s.", 60 * 60000L); }
                }
            }
            JSONObject hr = j.optJSONObject("hourly");
            if (hr != null) {
                JSONArray t = hr.getJSONArray("time"), w10 = hr.optJSONArray("wind_speed_10m"), d10 = hr.optJSONArray("wind_direction_10m"), wh = hr.optJSONArray("wind_speed_" + h + "m"), dh = hr.optJSONArray("wind_direction_" + h + "m");
                for (int k = 0; k < t.length(); k++) {
                    long ts = t.getLong(k) * 1000;
                    if (ts + 3600000L <= now || ts > now + 3600000L) continue;   // jetzige und nächste Stunde
                    double a = w10 != null ? w10.optDouble(k, 0) : 0, b = wh != null ? wh.optDouble(k, 0) : 0, da = d10 != null ? d10.optDouble(k, 0) : 0, db = dh != null ? dh.optDouble(k, 0) : 0;
                    String when = ts <= now ? "jetzt" : "ab " + hm.format(new java.util.Date(ts));
                    if (b >= c.optDouble("wind", 12)) once(sent, "windh", "💨 Wind in " + h + " m: " + f1(b) + " m/s (" + when + ")", "Über eurem Grenzwert von " + f1(c.optDouble("wind", 12)) + " m/s – Rückflug gegen den Wind einplanen, Akku früher zurück.", 60 * 60000L);
                    double dd = Math.abs(((da - db) % 360 + 540) % 360 - 180), dv = Math.abs(b - a);
                    if (c.optBoolean("shearOn", true) && (dv >= c.optDouble("shear", 5) || (dd >= 60 && Math.min(a, b) >= 3)))
                        once(sent, "scher", "🌀 Scherwind " + when, "10 m: " + f1(a) + " m/s aus " + dir(da) + " · " + h + " m: " + f1(b) + " m/s aus " + dir(db)
                                + (dv >= c.optDouble("shear", 5) ? " – " + f1(dv) + " m/s Unterschied" : " – Richtung dreht um " + Math.round(dd) + "°") + ". Beim Steigen und Sinken mit Böen und Versatz rechnen.", 90 * 60000L);
                }
            }
            if (c.optBoolean("kp", true)) {
                try {
                    JSONArray rows = new JSONArray(get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"));
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("UTC"));
                    double mx = 0;
                    for (int i = 0; i < rows.length(); i++) {   // Zeilen als [Zeit, Kp, …] oder als {time_tag, kp}
                        try { JSONArray r = rows.optJSONArray(i); JSONObject o = rows.optJSONObject(i);
                            String tt = r != null ? r.getString(0) : o != null ? o.optString("time_tag") : ""; String kv = r != null ? r.getString(1) : o != null ? o.optString("kp", o.optString("Kp")) : "";
                            long ts = f.parse(tt.replace('T', ' ').replaceAll("Z$", "")).getTime(); if (ts + 3 * 3600000L < now || ts > now + 3 * 3600000L) continue;
                            mx = Math.max(mx, Double.parseDouble(kv)); } catch (Exception ignored) { } }
                    if (mx >= c.optDouble("kpMin", 5)) once(sent, "kp", "🧭 Kp-Index " + f1(mx), "Starke geomagnetische Störung – GPS und Kompass können ungenau werden. Drohne im Blick behalten, ATTI-Modus beherrschen.", 3 * 3600000L);
                } catch (Exception ignored) { }
            }
        }
    }

    // ---------- OpenStreetMap-Daten aus der Geofabrik-Datei (statt Overpass) ----------
    // Overpass-Server sind oft überlastet; Geofabrik bietet dieselben Daten als fertige Datei je Bundesland bzw. Regierungsbezirk.
    // Die RC lädt die Datei einmal (am besten im WLAN), wertet sie selbst aus und behält sie für spätere Gebietsänderungen.
    private static final String GF = "https://download.geofabrik.de/europe/germany/";
    private final Object pbfLock = new Object();
    private volatile String pbfWait = null;   // Hinweis, wenn der große Download aufs WLAN wartet
    private volatile boolean pbfForceOnce = false;   // „Neu holen“: großen Download auch über mobile Daten erlauben
    private File pbfDir() { File d = new File(dataDir(), isExt() ? "OSM-Rohdaten" : "osmraw"); d.mkdirs(); return d; }
    private File pbfFile(String path) { return new File(pbfDir(), path.replace('/', '_') + ".osm.pbf"); }
    @SuppressWarnings("deprecation")
    private boolean metered() {
        try { android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE); return cm != null && cm.isActiveNetworkMetered(); }
        catch (Exception e) { return false; }
    }
    private boolean pbfCovers(double n, double s, double e, double w) {
        try {
            if (prefs.getLong("pbf_t", 0) == 0) return false;
            JSONObject b = new JSONObject(prefs.getString("pbf_bbox", "{}"));
            return b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
        } catch (Exception ex) { return false; }
    }
    private void pbfSt(String txt, int pct) { String v = "load:" + txt + "|" + pct; catSt("hindernisse", v); catSt("feldwege", v); }

    /** Geofabrik-Gebiete: Pfad, Bundesland, Name, grober Rahmen (W, S, O, N). In NRW, Baden-Württemberg und Bayern die Regierungsbezirke (kleinere Dateien).
     *  Die genaue Grenze kommt als .poly-Datei ebenfalls von Geofabrik – alles aus einer Quelle. */
    private static final Object[][] GF_REG = {
            {"schleswig-holstein", "SH", "Schleswig-Holstein", 7.8, 53.3, 11.4, 55.1}, {"hamburg", "HH", "Hamburg", 8.4, 53.35, 10.35, 54.0},
            {"niedersachsen", "NI", "Niedersachsen", 6.6, 51.25, 11.65, 53.95}, {"bremen", "HB", "Bremen", 8.45, 53.0, 9.0, 53.65},
            {"hessen", "HE", "Hessen", 7.75, 49.35, 10.25, 51.7}, {"rheinland-pfalz", "RP", "Rheinland-Pfalz", 6.1, 48.95, 8.55, 50.95},
            {"saarland", "SL", "Saarland", 6.35, 49.1, 7.45, 49.65}, {"berlin", "BE", "Berlin", 13.05, 52.3, 13.8, 52.7},
            {"brandenburg", "BB", "Brandenburg", 11.25, 51.35, 14.8, 53.6}, {"mecklenburg-vorpommern", "MV", "Mecklenburg-Vorpommern", 10.55, 53.1, 14.45, 54.7},
            {"sachsen", "SN", "Sachsen", 11.85, 50.15, 15.05, 51.7}, {"sachsen-anhalt", "ST", "Sachsen-Anhalt", 10.55, 50.9, 13.2, 53.05},
            {"thueringen", "TH", "Thüringen", 9.85, 50.2, 12.7, 51.65},
            {"nordrhein-westfalen/duesseldorf-regbez", "NW", "RB Düsseldorf", 5.9, 50.95, 7.4, 51.9}, {"nordrhein-westfalen/koeln-regbez", "NW", "RB Köln", 5.85, 50.3, 7.8, 51.2},
            {"nordrhein-westfalen/muenster-regbez", "NW", "RB Münster", 6.35, 51.4, 8.35, 52.4}, {"nordrhein-westfalen/detmold-regbez", "NW", "RB Detmold", 7.9, 51.4, 9.5, 52.55},
            {"nordrhein-westfalen/arnsberg-regbez", "NW", "RB Arnsberg", 6.85, 50.7, 9.0, 51.8},
            {"baden-wuerttemberg/stuttgart-regbez", "BW", "RB Stuttgart", 8.6, 48.35, 10.55, 49.8}, {"baden-wuerttemberg/karlsruhe-regbez", "BW", "RB Karlsruhe", 7.85, 48.25, 9.65, 49.65},
            {"baden-wuerttemberg/freiburg-regbez", "BW", "RB Freiburg", 7.45, 47.5, 9.05, 48.5}, {"baden-wuerttemberg/tuebingen-regbez", "BW", "RB Tübingen", 8.35, 47.5, 10.3, 48.75},
            {"bayern/unterfranken", "BY", "Unterfranken", 8.95, 49.45, 10.95, 50.6}, {"bayern/oberfranken", "BY", "Oberfranken", 10.35, 49.55, 12.3, 50.6},
            {"bayern/mittelfranken", "BY", "Mittelfranken", 9.85, 48.85, 11.6, 49.85}, {"bayern/oberpfalz", "BY", "Oberpfalz", 11.2, 48.75, 13.1, 50.05},
            {"bayern/niederbayern", "BY", "Niederbayern", 11.55, 48.1, 13.9, 49.25}, {"bayern/oberbayern", "BY", "Oberbayern", 10.7, 47.35, 13.15, 49.15},
            {"bayern/schwaben", "BY", "Schwaben", 9.9, 47.25, 11.3, 49.05}};

    /** Genaue Grenze eines Gebiets (Geofabrik .poly, lon/lat-Ringe; Löcher weggelassen) – einmal laden, dann gespeichert */
    private List<double[]> gfPoly(String path) {
        File f = new File(pbfDir(), path.replace('/', '_') + ".poly");
        String t = null;
        try { if (f.isFile() && f.length() > 50) t = readFile(f); } catch (Exception ignored) { }
        if (t == null && online()) {
            try { t = httpText(GF + path + ".poly", 30000); if (t.contains("END")) writeFileAtomic(f, t); else t = null; } catch (Exception ignored) { t = null; }
        }
        if (t == null) return null;
        List<double[]> rings = new ArrayList<>(); List<Double> cur = null; boolean hole = false; boolean first = true;
        for (String ln : t.split("\\r?\\n")) {
            String x = ln.trim(); if (x.isEmpty()) continue;
            if (first) { first = false; continue; }   // erste Zeile: Name
            if (cur == null) { if (x.equals("END")) break; cur = new ArrayList<>(); hole = x.startsWith("!"); continue; }
            if (x.equals("END")) { if (!hole && cur.size() >= 6) { double[] r = new double[cur.size()]; for (int i = 0; i < r.length; i++) r[i] = cur.get(i); rings.add(r); } cur = null; continue; }
            String[] v = x.split("\\s+"); if (v.length < 2) continue;
            try { cur.add(Double.parseDouble(v[0])); cur.add(Double.parseDouble(v[1])); } catch (Exception ignored) { }
        }
        return rings.isEmpty() ? null : rings;
    }

    /** Welche Geofabrik-Dateien decken das Gebiet (+3 km) ab? Erst grober Rahmen, dann genaue Grenze */
    private List<String[]> pbfRegions(double n, double s, double e, double w) {
        double m = 3 / 111.2, mo = 3 / (111.2 * Math.cos(Math.toRadians((n + s) / 2)));
        double bn = n + m, bs = s - m, be = e + mo, bw = w - mo;
        List<String[]> l = new ArrayList<>();
        Set<String> only = new java.util.HashSet<>(); for (String x : prefs.getString("osmStates", "").split(",")) if (!x.trim().isEmpty()) only.add(x.trim());
        for (Object[] g : GF_REG) {
            if (!only.isEmpty() && !only.contains((String) g[1])) continue;   // in den Einstellungen festgelegte Bundesländer
            double gw = (Double) g[3], gs = (Double) g[4], ge = (Double) g[5], gn = (Double) g[6];
            if (gw > be || ge < bw || gs > bn || gn < bs) continue;   // Rahmen berühren sich nicht
            List<double[]> rings = gfPoly((String) g[0]);
            boolean hit = rings == null;   // Grenze nicht ladbar: Rahmen reicht (lieber eine Datei zu viel)
            if (rings != null) for (double[] a : rings) {
                int np = a.length / 2;
                for (int q = 0; q < np && !hit; q++) { double x = a[2 * q], y = a[2 * q + 1]; if (y <= bn && y >= bs && x <= be && x >= bw) hit = true; }
                for (int gy = 0; gy <= 4 && !hit; gy++) for (int gx = 0; gx <= 4 && !hit; gx++) {
                    double py = bs + (bn - bs) * gy / 4, px = bw + (be - bw) * gx / 4; boolean c = false;
                    for (int q = 0, r = np - 1; q < np; r = q++) { double xi = a[2 * q], yi = a[2 * q + 1], xj = a[2 * r], yj = a[2 * r + 1];
                        if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c; }
                    if (c) hit = true;
                }
                if (hit) break;
            }
            if (hit) l.add(new String[]{(String) g[0], (String) g[2]});
        }
        return l;
    }

    /** Geofabrik-Datei laden – setzt abgebrochene Downloads fort */
    private void pbfDownload(String path, String name, File f) throws Exception {
        File part = new File(f.getPath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        if (have > 0 && System.currentTimeMillis() - part.lastModified() > 2 * DAY) { part.delete(); have = 0; }   // Geofabrik erneuert täglich
        String etag = prefs.getString("pbf_etag_" + path, "");
        HttpURLConnection c = (HttpURLConnection) new URL(GF + path + "-latest.osm.pbf").openConnection();
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, ehrenamtlich, nicht-kommerziell)");
            if (have > 0 && !etag.isEmpty()) { c.setRequestProperty("Range", "bytes=" + have + "-"); c.setRequestProperty("If-Range", etag); }
            int code = c.getResponseCode();
            if (code == 200) have = 0; else if (code != 206) throw new Exception("Geofabrik antwortet mit HTTP " + code);
            long total = have + c.getContentLengthLong();
            String et = c.getHeaderField("ETag"); if (et != null) prefs.edit().putString("pbf_etag_" + path, et).apply();
            if (pbfDir().getUsableSpace() < total - have + 300L * 1024 * 1024) throw new Exception("Zu wenig freier Speicher auf der RC (nötig ca. " + ((total - have) >> 20) + " MB)");
            byte[] buf = new byte[1 << 16]; long got = have; int lastPct = -1;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, have > 0)) {
                int k; while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); got += k;
                    int pct = total > 0 ? (int) (got * 100 / total) : 0;
                    if (pct != lastPct) { lastPct = pct; pbfSt("OpenStreetMap " + name + " wird geladen · " + (got >> 20) + " von " + (total >> 20) + " MB", pct); } }
            }
            if (total > 0 && part.length() < total) throw new Exception("Download unterbrochen – wird beim nächsten Mal fortgesetzt");
        } finally { c.disconnect(); }
        f.delete();
        if (!part.renameTo(f)) throw new Exception("Datei konnte nicht gespeichert werden");
        f.setLastModified(System.currentTimeMillis());
    }

    /** true = Daten kommen aus der Geofabrik-Datei (aktuell oder gerade neu ausgewertet); false = Overpass als Ausweg */
    private boolean pbfSync(double n, double s, double e, double w, boolean force) throws Exception {
        synchronized (pbfLock) {
            pbfWait = null;
            if (pbfForceOnce) { force = true; pbfForceOnce = false; }
            List<String[]> regs = pbfRegions(n, s, e, w);
            if (regs.isEmpty()) return false;
            boolean met = metered();
            List<File> files = new ArrayList<>(); List<String> names = new ArrayList<>(); long newest = 0, need = 0;
            StringBuilder src = new StringBuilder();
            for (String[] r : regs) {
                File f = pbfFile(r[0]);
                long age = f.isFile() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
                boolean want = !f.isFile() || age > 60 * DAY || (force && age > 3 * DAY);
                if (want && online() && (!met || force)) {
                    try { pbfDownload(r[0], r[1], f); }
                    catch (Exception ex) { if (!f.isFile()) throw ex; }   // alte Datei bleibt nutzbar
                }
                if (!f.isFile()) { need++; continue; }
                files.add(f); names.add(r[1]); newest = Math.max(newest, f.lastModified()); src.append(r[0]).append(';');
            }
            if (need > 0) {
                if (met && !force) pbfWait = regs.size() == 1 ? regs.get(0)[1] : regs.size() + " Gebiete";
                return false;
            }
            boolean same = src.toString().equals(prefs.getString("pbf_src", "")) && prefs.getLong("pbf_t", 0) >= newest;
            if (same && prefs.getInt("osm_ver", 1) >= 7 && pbfCovers(n, s, e, w) && osmFile().isFile() && osmWaysFile().isFile()) return true;
            pbfExtract(files, names, n, s, e, w);
            prefs.edit().putString("pbf_src", src.toString()).putLong("pbf_t", newest).apply();
            return true;
        }
    }

    private void pbfExtract(List<File> files, List<String> names, double n, double s, double e, double w) throws Exception {
        double mLa = 3 / 111.2, mLo = 3 / (111.2 * Math.cos(Math.toRadians((n + s) / 2)));
        double pLa = 25 / 111.2, pLo = 25 / (111.2 * Math.cos(Math.toRadians((n + s) / 2)));
        OsmPbf x = new OsmPbf(getCacheDir());   // Hilfsdatei im internen Speicher (mmap auf dem freigegebenen Speicher ist unzuverlässig)
        x.nN = OsmPbf.d7(n + mLa); x.nS = OsmPbf.d7(s - mLa); x.nE = OsmPbf.d7(e + mLo); x.nW = OsmPbf.d7(w - mLo);
        x.pN = OsmPbf.d7(n + pLa); x.pS = OsmPbf.d7(s - pLa); x.pE = OsmPbf.d7(e + pLo); x.pW = OsmPbf.d7(w - pLo);
        File tp = new File(osmFile().getPath() + ".neu"), tw = new File(osmWaysFile().getPath() + ".neu"), ta = new File(afFile().getPath() + ".neu");
        for (File t : new File[]{tp, tw, ta}) t.getParentFile().mkdirs();
        long sum = 0; for (File f : files) sum += f.length();
        final long all = sum;
        try {
            x.power = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new FileOutputStream(tp), StandardCharsets.UTF_8), 1 << 16);
            x.ways = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new FileOutputStream(tw), StandardCharsets.UTF_8), 1 << 16);
            x.af = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new FileOutputStream(ta), StandardCharsets.UTF_8), 1 << 16);
            final long[] base = {0};
            for (int i = 0; i < files.size(); i++) {
                final String nm = names.get(i);
                x.process(files.get(i), d -> { int pct = all > 0 ? (int) ((base[0] + d) * 100 / all) : 0; pbfSt("OpenStreetMap " + nm + " wird ausgewertet", pct); });
                base[0] += files.get(i).length();
            }
            x.power.write(x.pF ? "[]" : "]"); x.ways.write(x.wF ? "[]" : "]"); x.af.write(x.aF ? "[]" : "]");
        } finally {
            for (java.io.Writer wr : new java.io.Writer[]{x.power, x.ways, x.af}) try { if (wr != null) wr.close(); } catch (Exception ignored) { }
        }
        if (x.cntPower + x.cntWays == 0) { tp.delete(); tw.delete(); ta.delete(); throw new Exception("In der OpenStreetMap-Datei nichts für dieses Gebiet gefunden"); }
        if (!tp.renameTo(osmFile()) && !(osmFile().delete() && tp.renameTo(osmFile()))) throw new Exception("Speichern fehlgeschlagen");
        if (!tw.renameTo(osmWaysFile()) && !(osmWaysFile().delete() && tw.renameTo(osmWaysFile()))) throw new Exception("Speichern fehlgeschlagen");
        if (!ta.renameTo(afFile())) { afFile().delete(); ta.renameTo(afFile()); }
        StringBuilder pl = new StringBuilder("["); for (int i = 0; i < x.places.size(); i++) { if (i > 0) pl.append(','); pl.append(x.places.get(i)); } pl.append(']');
        if (!x.places.isEmpty()) writeFileAtomic(placeFile(), pl.toString());
        long now = System.currentTimeMillis();
        JSONObject bb = new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w);
        SharedPreferences.Editor ed = prefs.edit().putLong("osm_fetched", now).putInt("osm_ver", 7).putString("osm_bbox", bb.toString()).putString("pbf_bbox", bb.toString())
                .putLong("af_fetched", now).putString("af_bbox", bb.toString());
        if (!x.places.isEmpty()) ed.putLong("place_fetched", now).putString("place_bbox", new JSONObject().put("n", n + pLa).put("s", s - pLa).put("e", e + pLo).put("w", w - pLo).toString());
        ed.apply();
        osmCache = null;
        String stand = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new java.util.Date(files.get(0).lastModified()));
        cacheMarkTouch("hindernisse"); cacheMarkTouch("feldwege");
        catSt("hindernisse", "ok, " + x.cntPower + " Objekte · OpenStreetMap-Stand " + stand);
        catSt("feldwege", "ok, " + x.cntWays + " Wege · OpenStreetMap-Stand " + stand);
    }

    /** OpenStreetMap-Rohdaten (Geofabrik, Format PBF) lesen und nur das für die Kitzrettung Wichtige im Gebiet herausfiltern –
     *  ohne Zusatzbibliothek. Ergebnis im selben Format wie bisher von Overpass (type/id/tags/geometry bzw. lat/lon/center). */
    static final class OsmPbf {
        interface Progress { void on(long bytesDone); }
        static final class Pb {
            final byte[] b; int p; final int end;
            Pb(byte[] b, int off, int len) { this.b = b; p = off; end = off + len; }
            boolean more() { return p < end; }
            long varint() { long r = 0; int s = 0; while (true) { byte x = b[p++]; r |= (long) (x & 0x7f) << s; if (x >= 0) return r; s += 7; } }
            long sint() { long v = varint(); return (v >>> 1) ^ -(v & 1); }
            int len() { return (int) varint(); }
            void skip(int wt) { switch (wt) { case 0: varint(); break; case 1: p += 8; break; case 2: { int L = len(); p += L; } break; case 5: p += 4; break; default: throw new RuntimeException("PBF: unbekannter Feldtyp " + wt); } }
        }
        static final Set<String> MM = new java.util.HashSet<>(Arrays.asList("mast", "tower", "chimney", "communications_tower"));
        static final Set<String> AERO = new java.util.HashSet<>(Arrays.asList("aerodrome", "heliport", "airstrip", "helipad"));
        static final Set<String> PLACE = new java.util.HashSet<>(Arrays.asList("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "isolated_dwelling", "farm", "locality"));
        static final Set<String> HW = new java.util.HashSet<>(Arrays.asList("track", "path", "service", "unclassified", "tertiary", "bridleway", "cycleway", "footway"));
        static final Set<String> HWKEEP = new java.util.HashSet<>(Arrays.asList("highway", "name", "ref", "surface", "tracktype", "access", "motor_vehicle", "agricultural", "service"));
        static final Set<String> NKEYS = new java.util.HashSet<>(Arrays.asList("power", "man_made", "place", "aeroway", "emergency"));
        static final Set<String> WKEYS = new java.util.HashSet<>(Arrays.asList("power", "man_made", "aeroway", "railway", "highway", "emergency"));

        java.io.Writer power, ways, af; boolean pF = true, wF = true, aF = true;
        final List<String> places = new ArrayList<>();
        final java.util.HashSet<Long> seen = new java.util.HashSet<>();
        int nN, nS, nE, nW, pN, pS, pE, pW;   // Gebiet für Knoten / für Ortsnamen (1e-7 Grad)
        int cntPower, cntWays, cntAf;
        final File tmpDir;
        // Knotenspeicher (Datei, nach ID sortiert) – damit auch große Gebiete wenig Arbeitsspeicher brauchen
        File nsFile; java.io.DataOutputStream nsOut; long nsCount, nsLast = Long.MIN_VALUE, nsMin = Long.MAX_VALUE, nsMax = Long.MIN_VALUE;
        java.io.RandomAccessFile nsRaf; java.nio.MappedByteBuffer nsMap; boolean stop;
        // je Block
        String[] st; boolean[] kN, kW; long latOff, lonOff; int gran;
        long[] refs = new long[2048];

        OsmPbf(File tmpDir) { this.tmpDir = tmpDir; }

        static int d7(double deg) { return (int) Math.round(deg * 1e7); }
        static String c6(int v) { return Double.toString(Math.round(v / 10.0) / 1e6); }

        void process(File f, Progress pr) throws Exception {
            stop = false; nsCount = 0; nsLast = Long.MIN_VALUE; nsMin = Long.MAX_VALUE; nsMax = Long.MIN_VALUE; nsMap = null; nsRaf = null;
            nsFile = new File(tmpDir, "knoten.tmp"); nsFile.delete();
            nsOut = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(nsFile), 1 << 16));
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            long done = 0, lastRep = 0;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 17))) {
                while (!stop) {
                    int hl;
                    try { hl = in.readInt(); } catch (java.io.EOFException eof) { break; }
                    if (hl <= 0 || hl > 64 * 1024) throw new Exception("OSM-Datei beschädigt (Kopf)");
                    byte[] hb = new byte[hl]; in.readFully(hb);
                    String type = null; int ds = 0;
                    Pb p = new Pb(hb, 0, hl);
                    while (p.more()) { int t = (int) p.varint(), fn = t >>> 3, wt = t & 7;
                        if (fn == 1 && wt == 2) { int L = p.len(); type = new String(hb, p.p, L, StandardCharsets.UTF_8); p.p += L; }
                        else if (fn == 3 && wt == 0) ds = (int) p.varint(); else p.skip(wt); }
                    if (ds <= 0 || ds > 64 * 1024 * 1024) throw new Exception("OSM-Datei beschädigt (Block)");
                    byte[] bb = new byte[ds]; in.readFully(bb);
                    done += 4 + hl + ds;
                    if (pr != null && done - lastRep > (4 << 20)) { lastRep = done; pr.on(done); }
                    if (!"OSMData".equals(type)) continue;
                    byte[] raw = null; int rawSize = 0, zOff = -1, zLen = 0;
                    Pb q = new Pb(bb, 0, ds);
                    while (q.more()) { int t = (int) q.varint(), fn = t >>> 3, wt = t & 7;
                        if (fn == 1 && wt == 2) { int L = q.len(); raw = Arrays.copyOfRange(bb, q.p, q.p + L); rawSize = L; q.p += L; }
                        else if (fn == 2 && wt == 0) rawSize = (int) q.varint();
                        else if (fn == 3 && wt == 2) { zLen = q.len(); zOff = q.p; q.p += zLen; }
                        else if (wt == 2 && fn >= 4) throw new Exception("OSM-Datei mit unbekannter Kompression");
                        else q.skip(wt); }
                    if (zOff >= 0) {
                        raw = new byte[rawSize]; inf.reset(); inf.setInput(bb, zOff, zLen);
                        int got = 0; while (got < rawSize) { int k = inf.inflate(raw, got, rawSize - got); if (k == 0 && (inf.finished() || inf.needsInput())) break; got += k; }
                        if (got != rawSize) throw new Exception("OSM-Datei beschädigt (Entpacken)");
                    }
                    if (raw != null) block(raw, rawSize);
                }
            } finally {
                inf.end();
                try { if (nsOut != null) nsOut.close(); } catch (Exception ignored) { }
                nsOut = null; nsMap = null;
                try { if (nsRaf != null) nsRaf.close(); } catch (Exception ignored) { }
                nsRaf = null; nsFile.delete();
            }
            if (pr != null) pr.on(f.length());
        }

        void nodesDone() throws Exception {
            if (nsOut == null) return;
            nsOut.close(); nsOut = null;
            if (nsCount * 16 > Integer.MAX_VALUE) throw new Exception("Gebiet zu groß für die Auswertung");
            if (nsCount > 0) { nsRaf = new java.io.RandomAccessFile(nsFile, "r"); nsMap = nsRaf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, nsCount * 16); }
        }
        int find(long id) {
            if (nsMap == null || id < nsMin || id > nsMax) return -1;
            long lo = 0, hi = nsCount - 1;
            while (lo <= hi) { long mid = (lo + hi) >>> 1; long v = nsMap.getLong((int) (mid * 16)); if (v < id) lo = mid + 1; else if (v > id) hi = mid - 1; else return (int) mid; }
            return -1;
        }

        void block(byte[] b, int len) throws Exception {
            Pb p = new Pb(b, 0, len); List<int[]> groups = new ArrayList<>(); int stS = -1, stL = 0;
            gran = 100; latOff = 0; lonOff = 0;
            while (p.more()) { int t = (int) p.varint(), fn = t >>> 3, wt = t & 7;
                if (fn == 1 && wt == 2) { stL = p.len(); stS = p.p; p.p += stL; }
                else if (fn == 2 && wt == 2) { int L = p.len(); groups.add(new int[]{p.p, L}); p.p += L; }
                else if (fn == 17 && wt == 0) gran = (int) p.varint();
                else if (fn == 19 && wt == 0) latOff = p.varint();
                else if (fn == 20 && wt == 0) lonOff = p.varint();
                else p.skip(wt); }
            List<String> sl = new ArrayList<>();
            if (stS >= 0) { Pb ps = new Pb(b, stS, stL); while (ps.more()) { int t = (int) ps.varint(); if ((t >>> 3) == 1 && (t & 7) == 2) { int L = ps.len(); sl.add(new String(b, ps.p, L, StandardCharsets.UTF_8)); ps.p += L; } else ps.skip(t & 7); } }
            st = sl.toArray(new String[0]); kN = new boolean[st.length]; kW = new boolean[st.length];
            for (int i = 0; i < st.length; i++) { kN[i] = NKEYS.contains(st[i]); kW[i] = WKEYS.contains(st[i]); }
            for (int[] g : groups) {
                Pb pg = new Pb(b, g[0], g[1]);
                while (pg.more()) { int t = (int) pg.varint(), fn = t >>> 3, wt = t & 7;
                    if (wt != 2) { pg.skip(wt); continue; }
                    int L = pg.len(), s0 = pg.p; pg.p += L;
                    if (fn == 2) dense(b, s0, L);
                    else if (fn == 1) node(b, s0, L);
                    else if (fn == 3) { nodesDone(); way(b, s0, L); }
                    else if (fn == 4) { stop = true; return; }   // Relationen kommen zuletzt – brauchen wir nicht
                }
            }
        }

        void storeNode(long id, int la, int lo) throws Exception {
            if (nsOut == null) return;   // Knoten nach Wegen: Datei nicht sortiert – ignorieren
            if (id <= nsLast) throw new Exception("OSM-Datei nicht nach ID sortiert");
            nsLast = id; if (id < nsMin) nsMin = id; if (id > nsMax) nsMax = id;
            nsOut.writeLong(id); nsOut.writeInt(la); nsOut.writeInt(lo); nsCount++;
        }
        boolean inN(int la, int lo) { return la <= nN && la >= nS && lo <= nE && lo >= nW; }
        boolean inP(int la, int lo) { return la <= pN && la >= pS && lo <= pE && lo >= pW; }

        void dense(byte[] b, int off, int len) throws Exception {
            int idS = -1, idE = 0, laS = -1, laE = 0, loS = -1, loE = 0, kvS = -1, kvE = 0;
            Pb p = new Pb(b, off, len);
            while (p.more()) { int t = (int) p.varint(), fn = t >>> 3, wt = t & 7;
                if (wt == 2) { int L = p.len(), s0 = p.p; p.p += L;
                    if (fn == 1) { idS = s0; idE = s0 + L; } else if (fn == 8) { laS = s0; laE = s0 + L; } else if (fn == 9) { loS = s0; loE = s0 + L; } else if (fn == 10) { kvS = s0; kvE = s0 + L; } }
                else p.skip(wt); }
            if (idS < 0 || laS < 0 || loS < 0) return;
            Pb pi = new Pb(b, idS, idE - idS), pa = new Pb(b, laS, laE - laS), po = new Pb(b, loS, loE - loS), pk = kvS >= 0 ? new Pb(b, kvS, kvE - kvS) : null;
            long id = 0, la = 0, lo = 0;
            while (pi.more()) {
                id += pi.sint(); la += pa.sint(); lo += po.sint();
                int lat7 = (int) ((latOff + gran * la) / 100), lon7 = (int) ((lonOff + gran * lo) / 100);
                boolean in = inN(lat7, lon7);
                if (in) storeNode(id, lat7, lon7);
                if (pk == null || !pk.more()) continue;
                int k0 = pk.p; boolean any = false;
                while (pk.more()) { int k = (int) pk.varint(); if (k == 0) break; pk.varint(); if (k < kN.length && kN[k]) any = true; }
                if (!any || !(in || inP(lat7, lon7))) continue;
                Map<String, String> tg = new java.util.LinkedHashMap<>();
                Pb pt = new Pb(b, k0, kvE - k0);
                while (pt.more()) { int k = (int) pt.varint(); if (k == 0) break; int v = (int) pt.varint(); if (k < st.length && v < st.length) tg.put(st[k], st[v]); }
                nodeOut(id, lat7, lon7, tg, in);
            }
        }
        void node(byte[] b, int off, int len) throws Exception {
            long id = 0, la = 0, lo = 0; int kS = -1, kE = 0, vS = -1, vE = 0;
            Pb p = new Pb(b, off, len);
            while (p.more()) { int t = (int) p.varint(), fn = t >>> 3, wt = t & 7;
                if (fn == 1 && wt == 0) id = p.sint(); else if (fn == 8 && wt == 0) la = p.sint(); else if (fn == 9 && wt == 0) lo = p.sint();
                else if (fn == 2 && wt == 2) { int L = p.len(); kS = p.p; kE = p.p + L; p.p += L; }
                else if (fn == 3 && wt == 2) { int L = p.len(); vS = p.p; vE = p.p + L; p.p += L; }
                else p.skip(wt); }
            int lat7 = (int) ((latOff + gran * la) / 100), lon7 = (int) ((lonOff + gran * lo) / 100);
            boolean in = inN(lat7, lon7);
            if (in) storeNode(id, lat7, lon7);
            if (kS < 0 || vS < 0 || !(in || inP(lat7, lon7))) return;
            Map<String, String> tg = new java.util.LinkedHashMap<>(); boolean any = false;
            Pb pk = new Pb(b, kS, kE - kS), pv = new Pb(b, vS, vE - vS);
            while (pk.more() && pv.more()) { int k = (int) pk.varint(), v = (int) pv.varint(); if (k < st.length && v < st.length) { tg.put(st[k], st[v]); if (kN[k]) any = true; } }
            if (any) nodeOut(id, lat7, lon7, tg, in);
        }

        void nodeOut(long id, int la, int lo, Map<String, String> t, boolean in) throws Exception {
            String pw = t.get("power"), mm = t.get("man_made"), ae = t.get("aeroway"), pl = t.get("place");
            boolean obs = pw != null && (pw.equals("tower") || pw.equals("portal") || (pw.equals("pole") && t.containsKey("height")) || (pw.equals("generator") && "wind".equals(t.get("generator:source"))))
                    || mm != null && MM.contains(mm);
            boolean place = pl != null && PLACE.contains(pl) && t.containsKey("name");
            boolean air = ae != null && AERO.contains(ae) || "landing_site".equals(t.get("emergency"));   // auch Rettungshubschrauber-Landestellen
            if (!seen.add(id * 4)) return;
            if (place) {
                places.add("{\"n\":" + JSONObject.quote(t.get("name")) + ",\"t\":" + JSONObject.quote(pl) + ",\"lat\":" + Math.round(la / 100.0) / 1e5 + ",\"lon\":" + Math.round(lo / 100.0) / 1e5 + "}");
            }
            if (!in) return;
            if (obs || place) { StringBuilder sb = new StringBuilder(); sb.append("{\"type\":\"node\",\"id\":").append(id).append(",\"lat\":").append(c6(la)).append(",\"lon\":").append(c6(lo)).append(','); tags(sb, t, false); sb.append('}'); power.write(pF ? "[" : ","); power.write(sb.toString()); pF = false; cntPower++; }
            if (air) { StringBuilder sb = new StringBuilder(); sb.append("{\"type\":\"node\",\"id\":").append(id).append(",\"lat\":").append(c6(la)).append(",\"lon\":").append(c6(lo)).append(','); tags(sb, t, false); sb.append('}'); af.write(aF ? "[" : ","); af.write(sb.toString()); aF = false; cntAf++; }
        }

        static void tags(StringBuilder sb, Map<String, String> t, boolean hw) {
            sb.append("\"tags\":{"); boolean f = true;
            for (Map.Entry<String, String> e : t.entrySet()) {
                if (hw && !HWKEEP.contains(e.getKey())) continue;
                if (!hw && (e.getKey().startsWith("source") || e.getKey().startsWith("note") || e.getKey().startsWith("fixme") || e.getKey().startsWith("FIXME"))) continue;
                if (!f) sb.append(','); f = false;
                sb.append(JSONObject.quote(e.getKey())).append(':').append(JSONObject.quote(e.getValue()));
            }
            sb.append('}');
        }

        void way(byte[] b, int off, int len) throws Exception {
            long id = 0; int kS = -1, kE = 0, vS = -1, vE = 0, rS = -1, rE = 0;
            Pb p = new Pb(b, off, len);
            while (p.more()) { int t = (int) p.varint(), fn = t >>> 3, wt = t & 7;
                if (fn == 1 && wt == 0) id = p.varint();
                else if (wt == 2 && (fn == 2 || fn == 3 || fn == 8)) { int L = p.len(); if (fn == 2) { kS = p.p; kE = p.p + L; } else if (fn == 3) { vS = p.p; vE = p.p + L; } else { rS = p.p; rE = p.p + L; } p.p += L; }
                else p.skip(wt); }
            if (kS < 0 || vS < 0 || rS < 0 || nsMap == null) return;
            Pb pk = new Pb(b, kS, kE - kS); boolean any = false;
            while (pk.more()) { int k = (int) pk.varint(); if (k < kW.length && kW[k]) { any = true; break; } }
            if (!any) return;
            Map<String, String> t = new java.util.LinkedHashMap<>();
            pk = new Pb(b, kS, kE - kS); Pb pv = new Pb(b, vS, vE - vS);
            while (pk.more() && pv.more()) { int k = (int) pk.varint(), v = (int) pv.varint(); if (k < st.length && v < st.length) t.put(st[k], st[v]); }
            String pw = t.get("power"), mm = t.get("man_made"), ae = t.get("aeroway"), rw = t.get("railway"), hw = t.get("highway");
            int kind = 0;   // 1 = Hindernis/Leitung/Bahn, 3 = Flugplatz, 4 = Weg
            if (pw != null && (pw.equals("line") || pw.equals("minor_line") || pw.equals("cable") || (pw.equals("generator") && "wind".equals(t.get("generator:source"))))) kind = 1;
            else if (mm != null && MM.contains(mm)) kind = 1;
            else if (rw != null && (rw.equals("rail") || rw.equals("light_rail"))) kind = 1;
            else if (ae != null && AERO.contains(ae) || "landing_site".equals(t.get("emergency"))) kind = 3;
            else if (hw != null && HW.contains(hw)) kind = 4;
            if (kind == 0) return;
            int n = 0; long r = 0; Pb pr = new Pb(b, rS, rE - rS);
            while (pr.more()) { r += pr.sint(); if (n == refs.length) refs = Arrays.copyOf(refs, n * 2); refs[n++] = r; }
            if (n == 0) return;
            if (kind == 4) {   // Wege sind kurz: erst Stichprobe, ob sie überhaupt im Gebiet liegen
                boolean hit = false; for (int i = 0; i < n && !hit; i += 4) hit = find(refs[i]) >= 0; if (!hit) hit = find(refs[n - 1]) >= 0;
                if (!hit) return;
            }
            StringBuilder g = new StringBuilder(); int cnt = 0; long sla = 0, slo = 0;
            for (int i = 0; i < n; i++) {
                int ix = find(refs[i]); if (ix < 0) continue;
                int la = nsMap.getInt(ix * 16 + 8), lo = nsMap.getInt(ix * 16 + 12);
                sla += la; slo += lo;
                if (kind != 3) { if (cnt > 0) g.append(','); g.append("{\"lat\":").append(c6(la)).append(",\"lon\":").append(c6(lo)).append('}'); }
                cnt++;
            }
            if (kind == 3 ? cnt == 0 : cnt < 2) return;
            if (!seen.add(id * 4 + 1)) return;
            StringBuilder sb = new StringBuilder(); sb.append("{\"type\":\"way\",\"id\":").append(id).append(',');
            if (kind == 3) sb.append("\"center\":{\"lat\":").append(c6((int) (sla / cnt))).append(",\"lon\":").append(c6((int) (slo / cnt))).append("},");
            tags(sb, t, kind == 4);
            if (kind != 3) sb.append(",\"geometry\":[").append(g).append(']');
            sb.append('}');
            if (kind == 1) { power.write(pF ? "[" : ","); power.write(sb.toString()); pF = false; cntPower++; }
            else if (kind == 3) { af.write(aF ? "[" : ","); af.write(sb.toString()); aF = false; cntAf++; }
            else { ways.write(wF ? "[" : ","); ways.write(sb.toString()); wF = false; cntWays++; }
        }
    }

    // ---------- Kartenkacheln dauerhaft auf der RC speichern + Einsatzgebiet vorab laden ----------
    // Karte/Grau: basemap.de (BKG, CC BY 4.0, keine Zugriffsbeschränkung) – Vorab-Download erlaubt.
    // Luftbild: amtliche Orthophotos DOP20 Hessen (frei nutzbar) und Rheinland-Pfalz (dl-de/by-2-0), als Kacheln zusammengesetzt.
    // Außerhalb HE/RP: Esri World Imagery nur beim Ansehen (kein Vorab-Download – Nutzungsbedingungen).
    private static final String BMV_TILES = "/gdz_basemapde_vektor/tiles/v2/bm_web_de_3857/";
    private static final long TILE_FRESH = 365L * 24 * 3600 * 1000, TILE_MAX_BYTES = 12L * 1024 * 1024 * 1024;
    // ---------- Webseiten (dipul, Infos …) in der App statt im Browser ----------
    private android.widget.FrameLayout rootView;
    private android.widget.LinearLayout pageBox;
    private WebView pageWeb;
    private android.widget.TextView pageTitle;
    @SuppressLint("SetJavaScriptEnabled")
    private android.view.View pageExt;
    /** heller, abgerundeter Knopf wie „Zurück zur Karte“ in der App */
    private android.widget.TextView pillButton(String t, float dp) {
        android.widget.TextView b = new android.widget.TextView(this);
        b.setText(t); b.setTextSize(16); b.setTextColor(0xff0b63c5); b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(0xffe6effb); bg.setCornerRadius(12 * dp); b.setBackground(bg);
        b.setClickable(true); return b;
    }
    private void openInApp(String url) {
        if (pageBox == null) {
            float dp = getResources().getDisplayMetrics().density;
            pageBox = new android.widget.LinearLayout(this);
            pageBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            pageBox.setBackgroundColor(0xffffffff);
            // Kopfleiste wie in der App: Logo, Titel, rechts „Zurück zur App“ (hell, abgerundet)
            android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xffffffff);
            if (Build.VERSION.SDK_INT >= 21) bar.setElevation(3 * dp);
            bar.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
            android.widget.ImageView logo = new android.widget.ImageView(this);
            try { logo.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName())); } catch (Exception ignored) { }
            pageTitle = new android.widget.TextView(this);
            pageTitle.setTextColor(0xff15202b); pageTitle.setTextSize(18); pageTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); pageTitle.setSingleLine(true);
            pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pageTitle.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
            android.widget.TextView ext = pillButton("Im Browser", dp);
            ext.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl != null ? pdfUrl : pageWeb.getUrl()))); } catch (Exception ignored) { } });
            pageExt = ext;
            android.widget.TextView back = pillButton("Zurück zur App", dp);
            back.setOnClickListener(v -> closeInApp());
            bar.addView(logo, new android.widget.LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
            bar.addView(pageTitle, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            android.widget.LinearLayout.LayoutParams lpE = new android.widget.LinearLayout.LayoutParams(-2, -2); lpE.setMargins(0, 0, (int) (8 * dp), 0);
            bar.addView(ext, lpE);
            bar.addView(back);
            pageWeb = new WebView(this);
            WebSettings ws = pageWeb.getSettings();
            ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setLoadWithOverviewMode(true); ws.setUseWideViewPort(true);
            ws.setBuiltInZoomControls(true); ws.setDisplayZoomControls(false); ws.setAllowFileAccess(true);
            // PDF-Links (auch ohne .pdf in der Adresse) kommen als Download – selbst anzeigen
            pageWeb.setDownloadListener((u, ua, cd, mime, len) -> { if ((mime != null && mime.contains("pdf")) || isPdfUrl(u)) showPdf(u); });
            pageWeb.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    String u = req.getUrl().toString();
                    if (isPdfUrl(u)) { showPdf(u); return true; }
                    if (u.startsWith("http")) return false;   // Links bleiben in der App
                    try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception ignored) { }
                    return true;
                }
                @Override
                public void onPageFinished(WebView v, String u) {
                    if (pageTitle == null) return;
                    String h = Uri.parse(u).getHost(), t = v.getTitle();
                    if (h != null && (h.contains("flightradar24") || h.contains("adsb.lol") || h.contains("airplanes.live"))) {   // Flugradar: kurzer Titel, z. B. „Flugradar · D-HHEB“
                        String k = t == null ? "" : t.split(" - | \\| ")[0].trim(); pageTitle.setText("Flugradar" + (k.isEmpty() || k.length() > 24 || k.toLowerCase(Locale.ROOT).contains("flightradar") ? "" : " · " + k));
                    } else pageTitle.setText(t != null && !t.isEmpty() ? t : u);
                }
            });
            pageBox.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
            pageBox.addView(pageWeb, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            rootView.addView(pageBox, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        pageBox.setVisibility(android.view.View.VISIBLE);
        pageBox.bringToFront();
        if (isPdfUrl(url)) { showPdf(url); return; }
        pdfUrl = null;
        // Flugradar-Seiten (Flightradar24, adsb.lol): ohne „Im Browser“ – bleibt alles in der App
        String host = Uri.parse(url).getHost(); boolean radar = host != null && (host.contains("flightradar24") || host.contains("adsb.lol") || host.contains("airplanes.live"));
        if (pageExt != null) pageExt.setVisibility(radar ? android.view.View.GONE : android.view.View.VISIBLE);
        pageTitle.setText(radar ? "Flugradar" : url);
        pageWeb.loadUrl(url);
    }
    // ---------- PDFs in der App: WebView kann keine PDFs – Android-PdfRenderer macht Seitenbilder (bleiben offline gespeichert) ----------
    private volatile String pdfUrl = null;
    private static boolean isPdfUrl(String u) { if (u == null) return false; String l = u.toLowerCase(Locale.ROOT); int q = l.indexOf('?'); if (q > 0) l = l.substring(0, q); return l.endsWith(".pdf"); }
    private static String sha1(String t) { try { java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1"); StringBuilder b = new StringBuilder(); for (byte x : md.digest(t.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { return String.valueOf(t.hashCode()); } }
    private void pdfMsg(String title, String html) {
        runOnUiThread(() -> { if (pageTitle != null) pageTitle.setText(title);
            pageWeb.loadDataWithBaseURL(null, "<html><head><meta name='viewport' content='width=device-width'></head><body style='font:18px sans-serif;padding:30px;color:#15202b'>" + html + "</body></html>", "text/html", "UTF-8", null); });
    }
    private void showPdf(String url) {
        pdfUrl = url;
        String name = Uri.parse(url).getLastPathSegment(); if (name == null) name = "Dokument.pdf";
        final String nm = name;
        pdfMsg(nm, "<b>PDF wird geladen…</b><br><span style='color:#667'>" + nm + "</span>");
        new Thread(() -> {
            try {
                File dir = new File(dataDir(), "Dokumente/" + sha1(url)); dir.mkdirs();
                File pdf = new File(dir, "dokument.pdf");
                boolean fresh = pdf.isFile() && pdf.length() > 0 && System.currentTimeMillis() - pdf.lastModified() < 30L * 24 * 3600 * 1000;
                if (!fresh && online()) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
                    if (c.getResponseCode() == 200) {
                        byte[] b; try (InputStream in = c.getInputStream()) { b = readBytes(in); }
                        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
                            try (OutputStream o = new FileOutputStream(new File(dir, "dokument.tmp"))) { o.write(b); }
                            File[] old = dir.listFiles(); if (old != null) for (File f : old) if (f.getName().endsWith(".png")) f.delete();   // neue Fassung: Seitenbilder neu machen
                            new File(dir, "dokument.tmp").renameTo(pdf);
                        }
                    }
                    c.disconnect();
                }
                if (!pdf.isFile() || pdf.length() == 0) { pdfMsg(nm, "<b>PDF nicht verfügbar</b><br>" + (online() ? "Der Server hat kein PDF geliefert." : "Offline und noch nicht gespeichert – einmal mit Internet öffnen, dann geht es auch offline.") + "<br><br>Oben „Im Browser“ versucht es mit einem anderen Programm."); return; }
                // Seiten als Bilder (einmal erzeugt, danach sofort da)
                android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(fd);
                int n = Math.min(r.getPageCount(), 80);
                StringBuilder h = new StringBuilder("<html><head><meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=6'><style>body{margin:0;background:#525659}img{display:block;width:100%;max-width:1100px;margin:10px auto;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5)}p{color:#fff;font:15px sans-serif;text-align:center}</style></head><body>");
                for (int i = 0; i < n; i++) {
                    File png = new File(dir, "s" + (i + 1) + ".png");
                    if (!png.isFile() || png.length() == 0) {
                        if (i % 3 == 0) pdfMsg(nm, "<b>PDF wird aufbereitet…</b><br>Seite " + (i + 1) + " von " + n);
                        android.graphics.pdf.PdfRenderer.Page pg = r.openPage(i);
                        int w = 1400, hh = Math.max(1, Math.round(w * pg.getHeight() / (float) pg.getWidth()));
                        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888);
                        bm.eraseColor(0xffffffff);
                        pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        try (OutputStream o = new FileOutputStream(png)) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
                        bm.recycle();
                    }
                    h.append("<img src='s").append(i + 1).append(".png'>");
                }
                if (r.getPageCount() > n) h.append("<p>… weitere ").append(r.getPageCount() - n).append(" Seiten – vollständig über „Im Browser“</p>");
                r.close(); fd.close();
                h.append("</body></html>");
                final String html = h.toString(), base = "file://" + dir.getAbsolutePath() + "/";
                runOnUiThread(() -> { if (pageTitle != null) pageTitle.setText(nm); pageWeb.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null); });
            } catch (Throwable t) {
                pdfMsg(nm, "<b>PDF konnte nicht angezeigt werden</b><br>" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + "<br><br>Oben „Im Browser“ öffnet es mit einem anderen Programm.");
            }
        }).start();
    }
    private void closeInApp() {
        if (pageBox == null) return;
        pageBox.setVisibility(android.view.View.GONE); pdfUrl = null;
        try { pageWeb.loadUrl("about:blank"); pageWeb.clearHistory(); } catch (Exception ignored) { }
    }
    // ---------- Ablage der heruntergeladenen Daten (Karten, Geozonen, Wetter …) ----------
    // Sobald die Speicher-Freigabe da ist, liegen sie im Ordner „Kitzrettung Cache“ im internen Speicher (neben „UAV-Editor“) –
    // so bleiben sie auch bei Neuinstallation der App erhalten. Vorher (und bis zum Umzug) im App-eigenen Speicher.
    private volatile File dataDirCache = null;
    private File extDataDir() { return new File(Environment.getExternalStorageDirectory(), "Kitzrettung Cache"); }
    private File dataDir() {
        File c = dataDirCache; if (c != null) return c;
        // Sobald Dateizugriff da ist, IMMER „Kitzrettung Cache“ – auch direkt nach Neuinstallation/Update, wenn der Umzugs-Merker fehlt.
        // Alte Reste im App-Speicher zieht dataMigrate() nach.
        if (prefs != null && hasFileAccess()) {
            File e = extDataDir();
            if (e.isDirectory() || e.mkdirs()) return dataDirCache = e;
        }
        return super.getFilesDir();
    }
    /** Wohin eine Datei aus dem App-Speicher im Ordner „Kitzrettung Cache“ kommt */
    private static String migTarget(String rel) {
        rel = rel.replace('\\', '/');
        if (rel.startsWith("tiles/")) { String k = rel.substring(6); return tileCat(k) + "/" + k; }
        if (rel.startsWith("dipul_vec/")) return "Geozonen/Zonendaten/" + rel.substring(10);
        if (DATA_CAT.containsKey(rel)) return DATA_CAT.get(rel) + "/" + rel;
        return "Sonstiges/" + rel;
    }
    private volatile boolean dataMoving = false;
    /** Einmaliger Umzug der Daten in „Kitzrettung Cache“ (im Hintergrund, Datei für Datei, setzt nach Abbruch fort) */
    private void dataMigrate() {
        if (dataMoving || prefs.getBoolean("data_ext_done", false) || !hasFileAccess()) return;
        dataMoving = true;
        new Thread(() -> {
            try {
                File src = super.getFilesDir(), dst = extDataDir();
                if (!dst.isDirectory() && !dst.mkdirs()) return;
                try { new File(dst, ".nomedia").createNewFile(); } catch (Exception ignored) { }   // Kartenkacheln nicht in der Galerie zeigen
                int moved = 0;
                java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>(); st.push(src);
                String base = src.getAbsolutePath();
                while (!st.isEmpty()) {
                    File d = st.pop(); File[] fs = d.listFiles(); if (fs == null) continue;
                    for (File f : fs) {
                        if (f.isDirectory()) { st.push(f); continue; }
                        File t = new File(dst, migTarget(f.getAbsolutePath().substring(base.length() + 1)));
                        try {
                            if (!t.exists()) {   // im Cache-Ordner Vorhandenes hat Vorrang (überlebt Updates) – nie überschreiben
                                t.getParentFile().mkdirs();
                                File tmp = new File(t.getPath() + ".mv");
                                try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(tmp)) { byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) o.write(b, 0, r); }
                                if (!tmp.renameTo(t)) { t.delete(); tmp.renameTo(t); }
                            }
                            f.delete(); moved++;
                        } catch (Exception ignored) { }
                    }
                }
                prefs.edit().putBoolean("data_ext_done", true).apply();
                dataDirCache = null;
                final int m = moved;
                if (m > 0) js("window.onDataMoved && window.onDataMoved(" + m + ")");
            } finally { dataMoving = false; }
        }).start();
    }
    // ---------- Datenbereiche im Cache: einzeln löschen/neu holen; bei App-Änderungen per Versionsnummer automatisch aufräumen ----------
    private volatile long lastTileBytes = 0;
    private static final String[][] CACHE_CATS = {
            {"karte", "Karte (Grau/Farbe)"}, {"luftbild", "Luftbild"}, {"geokacheln", "Geozonen-Bilder (dipul)"}, {"zonendaten", "Geozonen-Daten (zum Antippen)"},
            {"feldwege", "Feldwege"}, {"hindernisse", "Hindernisse & Leitungen"}, {"windraeder", "Windräder"}, {"orte", "Orte & Landesgrenzen"},
            {"flugplaetze", "Flugplätze"}, {"wiesen", "Feldgrenzen der Wiesen"}, {"osmroh", "OpenStreetMap-Rohdaten (Geofabrik)"}};
    /** Version je Bereich: erhöhen, wenn sich das gespeicherte Format/der Umfang ändert – dann räumt die App diesen Bereich beim Start auf und holt ihn neu */
    private static final Map<String, Integer> CACHE_VER = new HashMap<>();
    static {
        CACHE_VER.put("karte", 1); CACHE_VER.put("luftbild", 1); CACHE_VER.put("geokacheln", 1); CACHE_VER.put("zonendaten", 2);
        CACHE_VER.put("feldwege", 1); CACHE_VER.put("hindernisse", 1); CACHE_VER.put("windraeder", 1); CACHE_VER.put("orte", 1);
        CACHE_VER.put("flugplaetze", 1); CACHE_VER.put("wiesen", 1); CACHE_VER.put("osmroh", 1);
    }
    private List<File> catFiles(String id) {
        List<File> l = new ArrayList<>();
        switch (id) {
            case "karte": l.add(tileFile("bmg")); l.add(tileFile("bmf")); l.add(tileFile("osm")); l.add(tileFile("bmv")); break;
            case "luftbild": l.add(tileFile("dop3")); l.add(tileFile("sat")); break;
            case "geokacheln": l.add(tileFile("dipul")); break;
            case "zonendaten": l.add(geoVecDir()); break;
            case "feldwege": l.add(osmWaysFile()); break;
            case "hindernisse": l.add(osmFile()); break;
            case "windraeder": l.add(dataFile("mastr_wind.json")); break;
            case "orte": l.add(placeFile()); l.add(regionFile()); break;
            case "flugplaetze": l.add(afFile()); l.add(afFreqFile()); l.add(dulvFile()); break;
            case "wiesen": l.add(outlineFile()); l.add(outlineFileByKmz()); l.add(dataFile("uave_katalog.json")); break;
            case "osmroh": l.add(pbfDir()); break;
        }
        return l;
    }
    /** Merker zurücksetzen, damit der Bereich beim nächsten Speichern/Abgleich wirklich neu geholt wird */
    private void catReset(String id) {
        SharedPreferences.Editor e = prefs.edit();
        switch (id) {
            case "zonendaten": e.remove("geo_vec_bbox").remove("geo_vec_fetched"); break;
            case "feldwege": case "hindernisse": e.remove("osm_fetched").remove("osm_bbox").remove("pbf_t"); break;
            case "osmroh": e.remove("pbf_t").remove("pbf_src"); break;
            case "orte": e.remove("place_fetched").remove("place_bbox").remove("reg_fetched").remove("pbf_t"); break;
            case "flugplaetze": e.remove("af_fetched").remove("af_bbox").remove("dulv_t").remove("pbf_t"); break;
            case "wiesen": e.remove("uave_outlines_fetched"); break;
        }
        e.apply();   // nur dieser Bereich – das gespeicherte Einsatzgebiet bleibt gültig (sonst würde die App beim nächsten Start alles neu laden)
    }
    private static void sizeOf(File f, long[] r) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) { r[0] += f.length(); r[1]++; return; }
        java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>(); st.push(f);
        while (!st.isEmpty()) { File[] fs = st.pop().listFiles(); if (fs == null) continue;
            for (File x : fs) { if (x.isDirectory()) st.push(x); else { r[0] += x.length(); r[1]++; } } }
    }
    // Versions-Merker liegt IM Cache-Ordner („Versionen.json“) – bleibt bei Neuinstallation erhalten und wird bei jedem Start schnell verglichen
    private volatile boolean cacheCounting = false, cacheCountAgain = false;
    private File cacheCountFile() { return new File(dataDir(), "Sonstiges/speicherstand.json"); }
    /** Alle Bereiche zählen; nach jedem Bereich Zwischenstand an die App – Fehler in einem Bereich halten den Rest nicht auf */
    private void cacheCountOnce() {
        JSONArray out = new JSONArray(); long total = 0, tiles = 0;
        JSONObject marks = cacheMarks();
        JSONObject prev = null; try { File lf = cacheCountFile(); if (lf.isFile()) prev = new JSONObject(readFile(lf)); } catch (Exception ignored) { }
        for (int i = 0; i < CACHE_CATS.length; i++) {
            String[] c = CACHE_CATS[i];
            long[] r = {0, 0};
            try { for (File f : catFiles(c[0])) sizeOf(f, r); } catch (Exception ignored) { }
            total += r[0];
            if (c[0].equals("karte") || c[0].equals("luftbild") || c[0].equals("geokacheln")) tiles += r[0];
            try {
                JSONObject m = marks.optJSONObject(c[0]);
                out.put(new JSONObject().put("id", c[0]).put("name", c[1]).put("bytes", r[0]).put("files", r[1]).put("ver", CACHE_VER.getOrDefault(c[0], 1))
                        .put("have", m != null ? m.optInt("ver", 0) : 0).put("t", m != null ? m.optLong("t", 0) : 0).put("st", catStatus.containsKey(c[0]) ? catStatus.get(c[0]) : ""));
                // Zwischenstand: schon gezählte Bereiche neu, der Rest vom letzten Mal (markiert als „zählt noch“)
                JSONArray show = new JSONArray(out.toString());
                JSONArray pl = prev != null ? prev.optJSONArray("list") : null;
                for (int k = i + 1; k < CACHE_CATS.length; k++) {
                    JSONObject old = null;
                    if (pl != null) for (int q = 0; q < pl.length(); q++) if (CACHE_CATS[k][0].equals(pl.getJSONObject(q).optString("id"))) old = pl.getJSONObject(q);
                    show.put((old != null ? old : new JSONObject().put("id", CACHE_CATS[k][0]).put("name", CACHE_CATS[k][1]).put("bytes", 0).put("files", 0)).put("pending", true));
                }
                if (i < CACHE_CATS.length - 1)
                    js("window.onCacheInfo && window.onCacheInfo(" + JSONObject.quote(new JSONObject().put("list", show).put("total", total).put("counting", true).put("where", dataDir().getAbsolutePath()).toString()) + ")");
            } catch (Exception ignored) { }
        }
        lastTileBytes = tiles;
        try {
            JSONObject res = new JSONObject().put("list", out).put("total", total).put("where", dataDir().getAbsolutePath()).put("counted", System.currentTimeMillis());
            try { writeFileAtomic(cacheCountFile(), res.toString()); } catch (Exception ignored) { }
            js("window.onCacheInfo && window.onCacheInfo(" + JSONObject.quote(res.toString()) + ")");
        } catch (Exception ignored) { }
    }
    private File cacheMarkFile() { return new File(dataDir(), "Versionen.json"); }
    private JSONObject cacheMarks() { try { File f = cacheMarkFile(); return f.isFile() ? new JSONObject(readFile(f)) : new JSONObject(); } catch (Exception e) { return new JSONObject(); } }
    private String appVer() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }
    /** Beim Start: Bereiche, deren gespeicherte Version nicht zur App passt, aufräumen (danach automatisch neu holen) */
    private void cacheVersionCheck() {
        new Thread(() -> {
            try {
                JSONObject m = cacheMarks(); boolean ch = false;
                for (String[] c : CACHE_CATS) {
                    String id = c[0]; int want = CACHE_VER.getOrDefault(id, 1);
                    JSONObject e = m.optJSONObject(id);
                    int have = e != null ? e.optInt("ver", 0) : prefs.getInt("cv_" + id, want);   // ohne Merker: bisherige Daten gelten als passend
                    if (have < want) { for (File f : catFiles(id)) deleteTree(f); catReset(id); }
                    if (e == null || have != want) { m.put(id, new JSONObject().put("ver", want).put("t", System.currentTimeMillis()).put("app", appVer())); ch = true; }
                    prefs.edit().putInt("cv_" + id, want).apply();
                }
                if (ch) writeFileAtomic(cacheMarkFile(), m.toString(1));
            } catch (Exception ignored) { }
        }).start();
    }
    /** Merker eines Bereichs nach Löschen/Neu-Laden auffrischen */
    private synchronized void cacheMarkTouch(String id) {
        try { JSONObject m = cacheMarks(); m.put(id, new JSONObject().put("ver", CACHE_VER.getOrDefault(id, 1)).put("t", System.currentTimeMillis()).put("app", appVer())); writeFileAtomic(cacheMarkFile(), m.toString(1)); } catch (Exception ignored) { }
    }
    private File tileDir() { return new File(dataDir(), "tiles"); }   // nur App-Speicher (alte Ablage)
    // Ordnung im Ordner „Kitzrettung Cache“: je Art ein eigener Unterordner
    private static final Map<String, String> DATA_CAT = new HashMap<>();
    static {
        String[][] m = {{"mastr_wind.json", "Windraeder"}, {"field_outlines.json", "Wiesen"}, {"field_outlines_kmz.json", "Wiesen"}, {"osm_power.json", "Hindernisse"},
                {"osm_places.json", "Orte"}, {"osm_ways.json", "Feldwege"}, {"regions.json", "Orte"}, {"airfields.json", "Flugplaetze"}, {"airfield_freqs.json", "Flugplaetze"}, {"dulv_airfields.json", "Flugplaetze"}, {"notams.json", "Geozonen"}, {"lfa.kml", "Geozonen"}, {"uave_katalog.json", "Wiesen"}, {"geofabrik_regionen.json", "Orte"}};
        for (String[] x : m) DATA_CAT.put(x[0], x[1]);
    }
    private boolean isExt() { return !dataDir().equals(super.getFilesDir()); }
    private File dataFile(String name) { return isExt() ? new File(dataDir(), (DATA_CAT.containsKey(name) ? DATA_CAT.get(name) : "Sonstiges") + "/" + name) : new File(dataDir(), name); }
    private static String tileCat(String key) {
        if (key.equals("dipul") || key.startsWith("dipul/")) return "Geozonen/Kacheln";
        if (key.equals("dop3") || key.equals("sat") || key.startsWith("dop3/") || key.startsWith("sat/")) return "Luftbild";
        return "Karte";
    }
    /** Kachel-Datei zu einem Schlüssel wie „bmg/14/8600/5500.png“ */
    private File tileFile(String key) { return isExt() ? new File(dataDir(), tileCat(key) + "/" + key) : new File(tileDir(), key); }
    /** Alle Ordner mit Kacheln (für Größe, Aufräumen, Leeren) */
    private File[] tileRoots() { return isExt() ? new File[]{new File(dataDir(), "Karte"), new File(dataDir(), "Luftbild"), new File(dataDir(), "Geozonen/Kacheln")} : new File[]{tileDir()}; }
    private static final String BM = "https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/";
    private static final String DOP_HE = "https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=he_dop20_rgb&STYLES=&CRS=EPSG:3857&FORMAT=image/png&TRANSPARENT=TRUE&WIDTH=256&HEIGHT=256&BBOX=";
    private static final String DOP_RP = "https://geo4.service24.rlp.de/wms/rp_dop20.fcgi?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=rp_dop20&STYLES=&CRS=EPSG:3857&FORMAT=image/png&TRANSPARENT=TRUE&WIDTH=256&HEIGHT=256&BBOX=";

    /** Kachel-Schlüssel (Datei im Speicher) und Typ aus der Kachel-Adresse; null = keine Kartenkachel */
    private static String[] tileKey(Uri u) {
        String host = u.getHost();
        if (host == null) return null;
        List<String> seg = u.getPathSegments();
        int n = seg.size();
        if (host.equals("sgx.geodatenzentrum.de") && n >= 5 && u.getPath().contains("/wmts_basemapde/")) {   // .../{layer}/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png
            String lay = u.getPath().contains("_grau/") ? "bmg" : "bmf";
            return new String[]{lay + "/" + seg.get(n - 3) + "/" + seg.get(n - 1).replace(".png", "") + "/" + seg.get(n - 2) + ".png", "image/png"};
        }
        if (host.equals("sgx.geodatenzentrum.de") && u.getPath() != null && u.getPath().startsWith("/gdz_basemapde_vektor/")) {   // Vektorkarte: Kacheln, Stil, Symbole, Schriften
            String pth = u.getPath(), mime = pth.endsWith(".pbf") ? "application/x-protobuf" : pth.endsWith(".json") ? "application/json" : pth.endsWith(".png") ? "image/png" : "application/octet-stream";
            return new String[]{"bmv" + pth.replaceAll("[^A-Za-z0-9/._,+-]", "_"), mime};
        }
        if (host.equals("dipul.local") && n == 3) {   // Geozonen als Bild (dipul WMS), je Zonengruppe g
            String g = u.getQueryParameter("g");
            if (g == null || g.isEmpty()) return null;
            if (g.contains(",")) {   // mehrere Gruppen: aus den gespeicherten Einzelbildern zu einem Bild zusammensetzen
                String[] gs = g.split(","); java.util.Arrays.sort(gs);
                for (String x : gs) if (!ZONE_GROUPS.containsKey(x)) return null;
                return new String[]{"dipul/c/" + android.text.TextUtils.join("+", gs) + "/" + seg.get(0) + "/" + seg.get(1) + "/" + seg.get(2) + ".png", "image/png"};
            }
            if (!ZONE_GROUPS.containsKey(g)) return null;
            return new String[]{"dipul/" + g + "/" + seg.get(0) + "/" + seg.get(1) + "/" + seg.get(2) + ".png", "image/png"};
        }
        if (host.equals("dop.local") && n == 3) return new String[]{"dop3/" + seg.get(0) + "/" + seg.get(1) + "/" + seg.get(2) + ".jpg", "image/jpeg"};
        if (host.equals("tile.openstreetmap.org") && n == 3) return new String[]{"osm/" + seg.get(0) + "/" + seg.get(1) + "/" + seg.get(2), "image/png"};
        if (host.equals("server.arcgisonline.com") && n >= 3 && u.getPath().contains("/tile/"))   // .../tile/{z}/{y}/{x}
            return new String[]{"sat/" + seg.get(n - 3) + "/" + seg.get(n - 1) + "/" + seg.get(n - 2) + ".jpg", "image/jpeg"};
        return null;
    }

    private WebResourceResponse tileFromCache(Uri u) {
        try {
            String[] km = tileKey(u);
            if (km == null) return null;
            File f = tileFile(km[0]);
            boolean have = f.isFile() && f.length() > 0;
            if (!have) viewReqAt = System.currentTimeMillis();   // Ansicht braucht Netz: Vorab-Download kurz pausieren
            boolean on = online();
            long fresh = km[0].startsWith("dipul/") ? Long.MAX_VALUE / 2 : km[0].startsWith("bmv/") && km[0].endsWith(".json") ? 7L * 24 * 3600 * 1000 : TILE_FRESH;   // Geozonen: nur auf Knopfdruck neu (dipulClear)
            if (have && (!on || System.currentTimeMillis() - f.lastModified() < fresh)) return tileResp(f, km[1]);
            if (!on && km[0].startsWith("dipul/c/")) {   // offline: aus gespeicherten Einzelbildern zusammensetzen
                List<String> sg = u.getPathSegments();
                byte[] cb = dipulCombined(Integer.parseInt(sg.get(0)), Integer.parseInt(sg.get(1)), Integer.parseInt(sg.get(2)), u.getQueryParameter("g").split(","), false, f);
                if (cb != null) return wr(km[1], new ByteArrayInputStream(cb));
            }
            if (!on) return new WebResourceResponse(km[1], null, 404, "offline", CORS, new ByteArrayInputStream(new byte[0]));
            byte[] b = null;
            try {
                if (km[0].startsWith("dipul/c/")) {
                    List<String> seg = u.getPathSegments();
                    byte[] cb = dipulCombined(Integer.parseInt(seg.get(0)), Integer.parseInt(seg.get(1)), Integer.parseInt(seg.get(2)), u.getQueryParameter("g").split(","), on, f);
                    if (cb != null) return wr(km[1], new ByteArrayInputStream(cb));
                } else if (km[0].startsWith("dipul/")) {
                    List<String> seg = u.getPathSegments();
                    b = dipulTile(Integer.parseInt(seg.get(0)), Integer.parseInt(seg.get(1)), Integer.parseInt(seg.get(2)), u.getQueryParameter("g"));
                } else if (km[0].startsWith("dop3/")) {
                    List<String> seg = u.getPathSegments();
                    DOP_PARTIAL.set(false);
                    b = dopTile(Integer.parseInt(seg.get(0)), Integer.parseInt(seg.get(1)), Integer.parseInt(seg.get(2)), u.getQueryParameter("s"));
                    if (b != null && DOP_PARTIAL.get()) return wr("image/jpeg", new ByteArrayInputStream(b));   // unvollständig: zeigen, nicht speichern
                    if (b == null) {   // außerhalb HE/RP: Esri nur zum Ansehen
                        byte[] e = httpBytes("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" + seg.get(0) + "/" + seg.get(2) + "/" + seg.get(1));
                        if (e != null) return wr("image/jpeg", new ByteArrayInputStream(e));
                    }
                } else if (km[0].startsWith("bmv/")) b = httpRaw(u.toString());
                else b = httpBytes(u.toString());
            } catch (Exception netErr) { /* Netz weg mitten im Laden */ }
            if (b != null && b.length > 0) { saveTile(f, b); return wr(km[1], new ByteArrayInputStream(b)); }
            return have ? tileResp(f, km[1]) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Eine Kartenkachel (Web-Mercator) mit allen Geozonen-Ebenen vom dipul-Kartendienst, transparent */
    private static final Map<String, String[]> ZONE_GROUPS = new HashMap<>();
    static {
        ZONE_GROUPS.put("luft", new String[]{"flughaefen", "flugplaetze", "kontrollzonen", "flugbeschraenkungsgebiete", "modellflugplaetze"});
        ZONE_GROUPS.put("natur", new String[]{"nationalparks", "naturschutzgebiete", "ffh-gebiete", "vogelschutzgebiete"});
        ZONE_GROUPS.put("verkehr", new String[]{"bundesautobahnen", "bundesstrassen", "bahnanlagen", "binnenwasserstrassen", "seewasserstrassen", "schifffahrtsanlagen"});
        ZONE_GROUPS.put("energie", new String[]{"stromleitungen", "windkraftanlagen", "umspannwerke", "kraftwerke", "industrieanlagen", "labore"});
        ZONE_GROUPS.put("sicher", new String[]{"justizvollzugsanstalten", "militaerische_anlagen", "behoerden", "diplomatische_vertretungen", "internationale_organisationen", "polizei", "sicherheitsbehoerden", "krankenhaeuser", "freibaeder"});
        ZONE_GROUPS.put("wohn", new String[]{"wohngrundstuecke"});
    }
    private static byte[] dipulTile(int z, int x, int y, String group) throws Exception {
        double R = 20037508.342789244, sz = 2 * R / (1 << z);
        double minx = -R + x * sz, maxx = minx + sz, maxy = R - y * sz, miny = maxy - sz;
        String[] gl = ZONE_GROUPS.get(group);
        if (gl == null) return null;
        StringBuilder ls = new StringBuilder();
        for (String l : gl) ls.append(ls.length() > 0 ? "," : "").append("dipul:").append(l);
        String url = DIPUL_WMS + "?service=WMS&version=1.1.1&request=GetMap&styles=&format=image/png&transparent=true&srs=EPSG:3857&width=256&height=256"
                + "&bbox=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", minx, miny, maxx, maxy)
                + "&layers=" + URLEncoder.encode(ls.toString(), "UTF-8");
        return httpBytes(url, 8000, 15000);
    }

    /** Mehrere Zonengruppen zu einem Bild: Einzelbilder aus dem Speicher (fehlende online holen und speichern), übereinander zeichnen */
    private byte[] dipulCombined(int z, int x, int y, String[] groups, boolean online, File saveTo) {
        try {
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            int n = 0;
            // fehlende Gruppen gleichzeitig bei dipul holen (nicht nacheinander) – sonst dauert jede Kachel sehr lange
            java.util.Map<String, java.util.concurrent.Future<byte[]>> fut = new HashMap<>();
            for (String g : groups) {
                File gf = tileFile("dipul/" + g + "/" + z + "/" + x + "/" + y + ".png");
                if (online && !(gf.isFile() && gf.length() > 0)) fut.put(g, GEO_POOL.submit(() -> { byte[] b = dipulTile(z, x, y, g); if (b != null && b.length > 0) saveTile(gf, b); return b; }));
            }
            for (String g : groups) {
                File gf = tileFile("dipul/" + g + "/" + z + "/" + x + "/" + y + ".png");
                byte[] b = null;
                java.util.concurrent.Future<byte[]> fu = fut.get(g);
                if (fu != null) { try { b = fu.get(12, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable t) { b = null; } }   // nicht ewig blockieren – sonst kommt auch die Grundkarte nicht mehr durch
                else if (gf.isFile() && gf.length() > 0) try (InputStream in = new FileInputStream(gf)) { b = readBytes(in); }
                if (b == null) continue;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm == null) continue;
                cv.drawBitmap(bm, null, new android.graphics.Rect(0, 0, 256, 256), null); bm.recycle(); n++;
            }
            if (n == 0) { out.recycle(); return null; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo); out.recycle();
            byte[] res = bo.toByteArray();
            if (n == groups.length && saveTo != null) saveTile(saveTo, res);   // nur vollständige Bilder speichern
            return res;
        } catch (Throwable t) { return null; }
    }

    private static void saveTile(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    private static byte[] httpBytes(String url) throws Exception { return httpBytes(url, 10000, 30000); }
    /** Beliebige Datei (Vektorkachel, JSON) – ohne Bild-Prüfung */
    private static byte[] httpRaw(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung Limburg e.V., nicht-kommerziell)");
            int code = c.getResponseCode();
            if (code == 204) return new byte[0];
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) { return readBytes(in); }
        } finally { c.disconnect(); }
    }
    private static byte[] httpBytes(String url, int conn, int read) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(conn); c.setReadTimeout(read);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung Limburg e.V., nicht-kommerziell)");
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType();
            if (ct != null && !ct.startsWith("image/")) return null;   // WMS-Fehlermeldung statt Bild
            try (InputStream in = c.getInputStream()) { return readBytes(in); }
        } finally { c.disconnect(); }
    }

    /** Luftbild-Kachel aus den Orthophotos von RP (unten) und HE (oben) zusammensetzen; null = an dieser Stelle kein DOP */
    private static final java.util.concurrent.ExecutorService GEO_POOL = java.util.concurrent.Executors.newFixedThreadPool(12);
    private static final java.util.concurrent.ExecutorService DOP_POOL = java.util.concurrent.Executors.newFixedThreadPool(8);
    private static final java.util.concurrent.ExecutorService DOP_PF_POOL = java.util.concurrent.Executors.newFixedThreadPool(6);   // eigener Pool fürs Vorladen, damit die Ansicht nicht warten muss
    static final ThreadLocal<Boolean> DOP_PREFETCH = ThreadLocal.withInitial(() -> false);

    static final ThreadLocal<Boolean> DOP_PARTIAL = ThreadLocal.withInitial(() -> false);
    /** states: "HE", "RP" oder "HE,RP" (Kachel an der Landesgrenze) – nur die nötigen Dienste, parallel */
    private static byte[] dopTile(int z, int x, int y, String states) throws Exception {
        double R = 20037508.342789244, size = 2 * R / (1 << z);
        double x0 = -R + x * size, y1 = R - y * size, x1 = x0 + size, y0 = y1 - size;
        String bbox = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", x0, y0, x1, y1);
        if (states == null || states.isEmpty() || states.equals("auto")) states = "HE,RP";
        boolean he = states.contains("HE"), rp = states.contains("RP");
        boolean both = he && rp;
        java.util.concurrent.ExecutorService dp = DOP_PREFETCH.get() ? DOP_PF_POOL : DOP_POOL;
        java.util.concurrent.Future<byte[]> fRp = both ? dp.submit(() -> httpBytes(DOP_RP + bbox)) : null;
        java.util.concurrent.Future<byte[]> fHe = both ? dp.submit(() -> httpBytes(DOP_HE + bbox)) : null;
        // nur ein Land: das JPEG des Landesdienstes direkt übernehmen (keine Transparenz, kein Umrechnen – schnell und farbecht)
        if (!(he && rp)) {
            byte[] b = null;
            try { b = httpBytes((he ? DOP_HE : DOP_RP).replace("FORMAT=image/png&TRANSPARENT=TRUE", "FORMAT=image/jpeg") + bbox); } catch (Exception ignored) { }
            if (b == null) return null;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
            boolean ok = bm != null && hasContent(bm);
            if (bm != null) bm.recycle();
            return ok ? b : null;
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        cv.drawColor(0xffd9d9d0);
        boolean any = false;
        DOP_PARTIAL.set(false);
        for (java.util.concurrent.Future<byte[]> fu : new java.util.concurrent.Future[]{fRp, fHe}) {
            byte[] b = null;
            try { b = fu.get(40, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
            if (b == null) { DOP_PARTIAL.set(true); continue; }   // ein Landesdienst fehlt: anzeigen, aber nicht speichern (sonst bleibt ein grauer Fleck)
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bm == null) continue;
            if (hasContent(bm)) { cv.drawBitmap(bm, null, new android.graphics.Rect(0, 0, 256, 256), null); any = true; }
            bm.recycle();
        }
        if (!any) { out.recycle(); return null; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bo);
        out.recycle();
        return bo.toByteArray();
    }

    /** Für den Vorab-Download: nur vollständige Luftbilder (beide Landesdienste geantwortet) */
    private static byte[] dopFull(int z, int x, int y) throws Exception {
        DOP_PARTIAL.set(false); DOP_PREFETCH.set(true);
        byte[] b;
        try { b = dopTile(z, x, y, "HE,RP"); } finally { DOP_PREFETCH.set(false); }
        return DOP_PARTIAL.get() ? null : b;
    }

    /** Einmalig: früher gespeicherte Luftbilder mit grauem Fleck (ein Landesdienst fehlte) löschen – werden neu geladen */
    private void dopCleanup() {
        if (prefs.getBoolean("dop_clean1", false)) return;
        new Thread(() -> {
            int del = 0;
            java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>();
            File root = tileFile("dop3"); if (root.isDirectory()) st.push(root);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = 4;
            while (!st.isEmpty()) {
                File d = st.pop(); File[] fs = d.listFiles(); if (fs == null) continue;
                for (File f : fs) {
                    if (f.isDirectory()) { st.push(f); continue; }
                    try {
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                        if (bm == null) { f.delete(); del++; continue; }
                        int w = bm.getWidth(), h = bm.getHeight(), grey = 0, tot = 0;
                        for (int yy = 0; yy < h; yy += 2) for (int xx = 0; xx < w; xx += 2) {
                            int p = bm.getPixel(xx, yy), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255; tot++;
                            if (Math.abs(r - 0xd9) < 7 && Math.abs(g - 0xd9) < 7 && Math.abs(bl - 0xd0) < 7) grey++;
                        }
                        bm.recycle();
                        if (grey * 10 > tot) { f.delete(); del++; }   // mehr als 10 % Füllgrau = unvollständig
                    } catch (Throwable ignored) { }
                }
            }
            prefs.edit().putBoolean("dop_clean1", true).apply();
            if (del > 0) { prefs.edit().remove("esBox5").apply(); js("window.onDopCleaned && window.onDopCleaned(" + del + ")"); }
        }).start();
    }

    /** Hat das Bild sichtbare (nicht transparente, nicht rein weiße) Pixel? Stichprobe 16×16 */
    private static boolean hasContent(android.graphics.Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight(), hits = 0;
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
            int p = bm.getPixel(Math.min(w - 1, i * w / 16 + w / 32), Math.min(h - 1, j * h / 16 + h / 32));
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (a > 40 && !(r > 250 && g > 250 && b > 250)) hits++;
        }
        return hits > 3;
    }

    // ---------- Vorab-Download ----------
    private volatile boolean prefetchStop = false, prefetchRun = false;
    private volatile long viewReqAt = 0;

    private void prefetchImpl(String json) {
        if (prefetchRun) return;
        prefetchRun = true; prefetchStop = false;
        new Thread(() -> {
            final int[] cnt = new int[4];   // geladen, schon da, Fehler, Gesamt
            final long[] bytes = new long[1];
            try {
                JSONObject p = new JSONObject(json);
                JSONArray layers = p.optJSONArray("layers"), area = p.optJSONArray("area"), fields = p.optJSONArray("fields");
                if (layers == null) layers = new JSONArray();
                int zMin = p.optInt("zMin", 8), zArea = p.optInt("zArea", 15), zMax = p.optInt("zMax", 18);
                List<int[]> list = new ArrayList<>();
                JSONArray tiers = p.optJSONArray("tiers");   // [[zVon, zBis, [[w,s,e,n], …]], …] – z. B. Deutschland grob, Einsatzgebiet mittel, Wiesen fein
                if (tiers != null) {
                    for (int ti = 0; ti < tiers.length(); ti++) {
                        JSONArray tr = tiers.getJSONArray(ti); JSONArray bx = tr.getJSONArray(2);
                        for (int z = tr.getInt(0); z <= tr.getInt(1); z++) {
                            java.util.Set<Long> seen = new java.util.HashSet<>();
                            for (int i = 0; i < bx.length(); i++) {
                                JSONArray b = bx.getJSONArray(i);
                                int xa = lon2tile(b.getDouble(0), z), xb = lon2tile(b.getDouble(2), z), ya = lat2tile(b.getDouble(3), z), yb = lat2tile(b.getDouble(1), z);
                                for (int x = xa; x <= xb; x++) for (int y = ya; y <= yb; y++) if (seen.add(((long) x << 32) | y)) list.add(new int[]{z, x, y});
                            }
                        }
                    }
                }
                if (tiers == null && area != null) for (int z = zMin; z <= zMax; z++) {
                    java.util.Set<Long> seen = new java.util.HashSet<>();
                    List<double[]> boxes = new ArrayList<>();
                    if (z <= zArea) boxes.add(new double[]{area.getDouble(0), area.getDouble(1), area.getDouble(2), area.getDouble(3)});
                    else if (fields != null) for (int i = 0; i < fields.length(); i++) { JSONArray b = fields.getJSONArray(i); boxes.add(new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)}); }
                    for (double[] b : boxes) {
                        int xa = lon2tile(b[0], z), xb = lon2tile(b[2], z), ya = lat2tile(b[3], z), yb = lat2tile(b[1], z);
                        for (int x = xa; x <= xb; x++) for (int y = ya; y <= yb; y++) if (seen.add(((long) x << 32) | y)) list.add(new int[]{z, x, y});
                    }
                }
                // Mehrere Aufträge in einem Lauf: je Auftrag eigene Ebenen und Zoomstufen (Kartenhintergrund, Geozonen-Bilder)
                JSONArray jobs = p.optJSONArray("jobs");
                List<Object[]> work = new ArrayList<>();
                if (jobs != null) {
                    for (int ji = 0; ji < jobs.length(); ji++) {
                        JSONObject jb = jobs.getJSONObject(ji); JSONArray jl = jb.getJSONArray("layers"), tr = jb.getJSONArray("tiers");
                        List<int[]> tl = new ArrayList<>();
                        for (int ti = 0; ti < tr.length(); ti++) {
                            JSONArray t = tr.getJSONArray(ti); JSONArray bx = t.getJSONArray(2);
                            for (int z = t.getInt(0); z <= t.getInt(1); z++) {
                                java.util.Set<Long> seen = new java.util.HashSet<>();
                                for (int i = 0; i < bx.length(); i++) {
                                    JSONArray b = bx.getJSONArray(i);
                                    int xa = lon2tile(b.getDouble(0), z), xb = lon2tile(b.getDouble(2), z), ya = lat2tile(b.getDouble(3), z), yb = lat2tile(b.getDouble(1), z);
                                    for (int x = xa; x <= xb; x++) for (int y = ya; y <= yb; y++) if (seen.add(((long) x << 32) | y)) tl.add(new int[]{z, x, y});
                                }
                            }
                        }
                        for (int li = 0; li < jl.length(); li++) for (int[] t : tl) work.add(new Object[]{jl.getString(li), t});
                    }
                } else for (int li = 0; li < layers.length(); li++) for (int[] t : list) work.add(new Object[]{layers.getString(li), t});
                cnt[3] = work.size();
                // Fortschritt je Datenbereich (für die Liste „Offline-Daten“ in den Einstellungen)
                java.util.LinkedHashMap<String, int[]> pc = new java.util.LinkedHashMap<>();
                for (Object[] w : work) { String c = pfCat((String) w[0]); int[] a = pc.get(c); if (a == null) pc.put(c, a = new int[3]); a[0]++; }
                pfCats = pc; pfCur = null;
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(4);
                final long[] lastEv = {0};
                {
                    for (Object[] w : work) {
                        final String lay = (String) w[0]; final int[] t = (int[]) w[1];
                        ex.submit(() -> {
                            if (prefetchStop) return;
                            final String pcat = pfCat(lay);
                            // Vorrang für die Karte, die gerade angesehen wird: solange sie Kacheln lädt, wartet der Hintergrund-Download
                            for (int wt = 0; wt < 50 && !prefetchStop && System.currentTimeMillis() - viewReqAt < 2500; wt++) try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
                            try {
                                boolean geo = lay.startsWith("geo:");
                                boolean vec = lay.equals("bmv");   // Vektorkarte basemap.de (z = Kachelstufe 512 px, max. 15)
                                String key = lay.equals("dop") ? "dop3/" + t[0] + "/" + t[1] + "/" + t[2] + ".jpg"
                                        : geo ? "dipul/" + lay.substring(4) + "/" + t[0] + "/" + t[1] + "/" + t[2] + ".png"
                                        : vec ? "bmv" + BMV_TILES + t[0] + "/" + t[1] + "/" + t[2] + ".pbf"
                                        : lay + "/" + t[0] + "/" + t[1] + "/" + t[2] + ".png";
                                File f = tileFile(key);
                                if (f.isFile() && f.length() > 0) { synchronized (cnt) { cnt[1]++; } }
                                else {
                                    if (lay.equals("dop") && t[0] < 12) { synchronized (cnt) { cnt[1]++; } return; }   // Luftbild-Übersicht kommt von Esri (nur beim Ansehen)
                                    if (vec && t[0] > 15) { synchronized (cnt) { cnt[1]++; } return; }
                                    byte[] b = vec ? httpRaw("https://sgx.geodatenzentrum.de" + BMV_TILES + t[0] + "/" + t[1] + "/" + t[2] + ".pbf")
                                            : geo ? dipulTile(t[0], t[1], t[2], lay.substring(4))
                                            : lay.equals("dop") ? dopFull(t[0], t[1], t[2])
                                            : httpBytes(BM + (lay.equals("bmg") ? "de_basemapde_web_raster_grau" : "de_basemapde_web_raster_farbe") + "/default/GLOBAL_WEBMERCATOR/" + t[0] + "/" + t[2] + "/" + t[1] + ".png");
                                    if (vec && b != null && b.length == 0) { synchronized (cnt) { cnt[1]++; } }   // leere Kachel (nichts da) – in Ordnung
                                    else if (b != null && b.length > 0) { saveTile(f, b); synchronized (cnt) { cnt[0]++; bytes[0] += b.length; } }
                                    else synchronized (cnt) { cnt[2]++; int[] a = pfCats.get(pcat); if (a != null) a[2]++; }
                                }
                            } catch (Exception e) { synchronized (cnt) { cnt[2]++; int[] a = pfCats.get(pcat); if (a != null) a[2]++; } }
                            synchronized (cnt) { int[] a = pfCats.get(pcat); if (a != null) a[1]++; pfCur = pcat; }
                            long now = System.currentTimeMillis();
                            if (now - lastEv[0] > 700) { lastEv[0] = now; prefetchEvent(cnt, bytes[0], false); }
                        });
                    }
                }
                ex.shutdown();
                while (!ex.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { if (prefetchStop) ex.shutdownNow(); }
            } catch (Exception e) {
                js("window.onPrefetch && window.onPrefetch(" + JSONObject.quote(new JSONObject().toString()) + ")");
            } finally {
                prefetchRun = false;
                if (!prefetchStop) for (String c : pfCats.keySet()) cacheMarkTouch(c);
                prefetchEvent(cnt, bytes[0], true);
            }
        }).start();
    }

    private volatile Map<String, int[]> pfCats = new HashMap<>();
    /** Lade-Status der Datenbereiche (Feldwege, Hindernisse, Orte …): „load“, „ok“ oder Fehlertext – für die Liste „Offline-Daten“ */
    private final Map<String, String> catStatus = new java.util.concurrent.ConcurrentHashMap<>();
    private void catSt(String id, String st) {
        catStatus.put(id, st == null ? "Fehler" : st);
        if ("ok".equals(st)) cacheMarkTouch(id);
        try {
            JSONObject o = new JSONObject(); for (Map.Entry<String, String> e : catStatus.entrySet()) o.put(e.getKey(), e.getValue());
            js("window.onCacheStatus && window.onCacheStatus(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) { }
    }
    private static String errTxt(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("resolve host") || m.contains("UnknownHost")) return "Server nicht erreichbar";
        if (m.contains("CertPath") || m.contains("Trust anchor") || e instanceof javax.net.ssl.SSLException) return "sichere Verbindung zum Server nicht möglich (Zertifikat)";
        if (m.toLowerCase(Locale.ROOT).contains("timeout") || m.contains("timed out")) return "Server antwortet nicht (Zeitüberschreitung)";
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
    private volatile String pfCur = null;
    private static String pfCat(String lay) { return lay.equals("dop") ? "luftbild" : lay.startsWith("geo:") ? "geokacheln" : "karte"; }
    private void prefetchEvent(int[] cnt, long bytes, boolean fin) {
        try {
            JSONObject o;
            synchronized (cnt) {
                o = new JSONObject().put("done", cnt[0]).put("have", cnt[1]).put("fail", cnt[2]).put("total", cnt[3])
                        .put("bytes", bytes).put("finished", fin).put("cancelled", prefetchStop);
                JSONObject cs = new JSONObject();
                for (Map.Entry<String, int[]> e : pfCats.entrySet()) cs.put(e.getKey(), new JSONArray().put(e.getValue()[1]).put(e.getValue()[0]).put(e.getValue()[2]));
                o.put("cats", cs); if (pfCur != null) o.put("cur", pfCur);
            }
            js("window.onPrefetch && window.onPrefetch(" + JSONObject.quote(o.toString()) + ")");
        } catch (Exception ignored) { }
    }

    private static int lon2tile(double lon, int z) { return Math.max(0, Math.min((1 << z) - 1, (int) Math.floor((lon + 180) / 360 * (1 << z)))); }
    private static int lat2tile(double lat, int z) {
        double r = Math.toRadians(lat);
        return Math.max(0, Math.min((1 << z) - 1, (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z))));
    }

    private static WebResourceResponse tileResp(File f, String mime) throws Exception {
        return wr(mime, new FileInputStream(f));
    }
    /** Antwort mit CORS-Kopf – die Kartenbibliothek (MapLibre) lädt per fetch und braucht ihn */
    private static final Map<String, String> CORS = new HashMap<>();
    static { CORS.put("Access-Control-Allow-Origin", "*"); CORS.put("Cache-Control", "no-cache"); }
    private static WebResourceResponse wr(String mime, InputStream in) { return new WebResourceResponse(mime, null, 200, "OK", CORS, in); }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[32768]; int k;
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        return bo.toByteArray();
    }

    /** Speicher begrenzen: über 12 GB die am längsten nicht aktualisierten Kacheln löschen */
    private void tileCacheTrim() {
        try {
            List<File> all = new ArrayList<>();
            java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>();
            for (File r0 : tileRoots()) st.push(r0);
            long total = 0;
            while (!st.isEmpty()) {
                File[] fs = st.pop().listFiles();
                if (fs == null) continue;
                for (File x : fs) { if (x.isDirectory()) st.push(x); else { all.add(x); total += x.length(); } }
            }
            if (total <= TILE_MAX_BYTES) return;
            Collections.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File x : all) { if (total <= TILE_MAX_BYTES * 8 / 10) break; total -= x.length(); x.delete(); }
        } catch (Exception ignored) { }
    }

    /** Größe des Kachelspeichers für die Einstellungen */
    private long tileCacheBytes() {
        long total = 0;
        java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>();
        for (File r0 : tileRoots()) st.push(r0);
        while (!st.isEmpty()) {
            File[] fs = st.pop().listFiles();
            if (fs == null) continue;
            for (File x : fs) { if (x.isDirectory()) st.push(x); else total += x.length(); }
        }
        return total;
    }

    // ---------- Bundesländer + Regierungsbezirk Gießen (OSM-Grenzen, vereinfacht, offline) ----------
    private File regionFile() { return dataFile("regions.json"); }

    private void regionSync(double n, double s, double e, double w, boolean force) {
        try {
            boolean covers = false;
            try {
                JSONObject b = new JSONObject(prefs.getString("reg_bbox", "{}"));
                covers = b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
            } catch (Exception ignored) { }
            boolean fresh = System.currentTimeMillis() - prefs.getLong("reg_fetched", 0) < 60 * DAY;   // Grenzen ändern sich kaum
            boolean hasLand = false;   // früherer Fehler: BKG-Daten ohne Bundesländer gespeichert – dann neu holen
            try { hasLand = regionFile().isFile() && readFile(regionFile()).contains("\"lvl\":\"4\""); } catch (Exception ignored) { }
            if (!force && fresh && covers && hasLand) return;
            catSt("orte", "load");
            // amtliche Grenzen vom BKG – keine Overpass-Abfrage mehr
            boolean bkgOk;
            try { bkgOk = regionSyncBkg(n, s, e, w); }
            catch (Exception bx) { catSt("orte", (regionFile().isFile() ? "alt:" : "") + "Landesgrenzen (BKG): " + errTxt(bx)); return; }
            if (bkgOk) { catSt("orte", "ok"); return; }
            if (n == n) { catSt("orte", (regionFile().isFile() ? "alt:" : "") + "Landesgrenzen (BKG): keine Daten"); return; }
            // Schnell: nur Grenzstücke im Gebiet + Zugehörigkeit der Gebietsmitte (statt kompletter Landesgrenzen)
            String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
            double clat = (n + s) / 2, clon = (e + w) / 2;
            String q = "[out:json][timeout:90];"
                    + "rel[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(4|5)$\"](" + bb + ")->.r;"
                    + ".r out body;"
                    + "way(r.r)(" + bb + ");out geom;"
                    + String.format(Locale.ROOT, "is_in(%.6f,%.6f)->.a;", clat, clon)
                    + "area.a[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(4|5)$\"];out tags;";
            JSONArray els = new JSONObject(overpass(q, 120000)).getJSONArray("elements");
            Map<Long, JSONArray> wayGeom = new HashMap<>();
            Set<Long> centerIn = new java.util.HashSet<>();
            List<JSONObject> rels = new ArrayList<>();
            for (int i = 0; i < els.length(); i++) {
                JSONObject el = els.getJSONObject(i);
                String ty = el.optString("type");
                if ("way".equals(ty)) {
                    JSONArray g = el.optJSONArray("geometry");
                    if (g == null || g.length() < 2) continue;
                    double[] la = new double[g.length()], lo = new double[g.length()];
                    int c = 0;
                    for (int k = 0; k < g.length(); k++) { JSONObject pt = g.optJSONObject(k); if (pt == null) continue; la[c] = pt.getDouble("lat"); lo[c] = pt.getDouble("lon"); c++; }
                    if (c < 2) continue;
                    boolean[] keep = new boolean[c]; keep[0] = keep[c - 1] = true;
                    simplify(la, lo, 0, c - 1, 0.0001, keep);   // ca. 10 m
                    JSONArray ln = new JSONArray();
                    for (int k = 0; k < c; k++) if (keep[k]) ln.put(Math.round(lo[k] * 1e5) / 1e5).put(Math.round(la[k] * 1e5) / 1e5);
                    wayGeom.put(el.getLong("id"), ln);
                } else if ("area".equals(ty)) {
                    centerIn.add(el.getLong("id") - 3600000000L);
                } else if ("relation".equals(ty)) rels.add(el);
            }
            JSONArray out = new JSONArray();
            for (JSONObject r : rels) {
                JSONObject t = r.optJSONObject("tags");
                if (t == null) continue;
                String lvl = t.optString("admin_level"), name = t.optString("name"), code = t.optString("ISO3166-2");
                if ("4".equals(lvl) && !code.startsWith("DE-")) continue;
                if ("5".equals(lvl) && !name.matches(".*Gie(ß|ss)en.*")) continue;
                JSONArray mem = r.optJSONArray("members"), lines = new JSONArray();
                if (mem != null) for (int k = 0; k < mem.length(); k++) {
                    JSONObject m = mem.getJSONObject(k);
                    if (!"way".equals(m.optString("type"))) continue;
                    JSONArray ln = wayGeom.get(m.getLong("ref"));
                    if (ln != null) lines.put(ln);
                }
                boolean in = centerIn.contains(r.getLong("id"));
                if (lines.length() == 0 && !in) continue;   // weder Grenze im Gebiet noch Mitte drin
                out.put(new JSONObject().put("lvl", lvl).put("code", code).put("name", name)
                        .put("ref", new JSONArray().put(clat).put(clon)).put("refIn", in)
                        .put("bb", new JSONArray().put(w).put(s).put(e).put(n)).put("lines", lines));
            }
            if (out.length() == 0) return;   // nichts überschreiben, wenn die Antwort leer ist
            writeFileAtomic(regionFile(), out.toString());
            prefs.edit().putLong("reg_fetched", System.currentTimeMillis())
                    .putString("reg_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
        } catch (Exception ex) {
            // Ausweg: amtliche Verwaltungsgrenzen des BKG (VG2500, vereinfacht) – unabhängig von OpenStreetMap
            try { if (regionSyncBkg(n, s, e, w)) { catSt("orte", "ok"); return; } } catch (Exception ignored) { }
            catSt("orte", (regionFile().isFile() ? "alt:" : "") + "Landesgrenzen: " + errTxt(ex));
        }
    }

    private static final String[] BKG_LAND = {"", "DE-SH", "DE-HH", "DE-NI", "DE-HB", "DE-NW", "DE-HE", "DE-RP", "DE-BW", "DE-BY", "DE-SL", "DE-BE", "DE-BB", "DE-MV", "DE-SN", "DE-ST", "DE-TH"};
    /** Länder + Regierungsbezirke aus dem BKG-WFS (GeoJSON oder GML) im gleichen Format wie die OSM-Variante */
    private boolean regionSyncBkg(double n, double s, double e, double w) throws Exception {
        double clat = (n + s) / 2, clon = (e + w) / 2;
        JSONArray out = new JSONArray();
        for (String[] ly : new String[][]{{"vg2500_lan", "4"}, {"vg2500_rbz", "5"}}) {
            List<Object[]> feats = bkgFeatures(ly[0]);   // {name, rs, List<double[] ring(lon,lat,...)>}
            for (Object[] f : feats) {
                String name = (String) f[0], rs = (String) f[1];
                @SuppressWarnings("unchecked") List<double[]> rings = (List<double[]>) f[2];
                String lvl = ly[1], code = "";
                if ("4".equals(lvl)) { int k = -1; try { k = Integer.parseInt(rs.length() >= 2 ? rs.substring(0, 2) : rs); } catch (Exception ignored) { } if (k < 1 || k > 16) continue; code = BKG_LAND[k]; }
                else { if (!name.matches(".*Gie(ß|ss)en.*")) continue; name = name.startsWith("Regierungsbezirk") ? name : "Regierungsbezirk " + name; }
                boolean in = false; JSONArray lines = new JSONArray();
                for (double[] r : rings) {
                    // Punkt-im-Polygon für die Gebietsmitte
                    boolean c = false; for (int i = 0, j = r.length / 2 - 1; i < r.length / 2; j = i++) {
                        double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
                        if ((yi > clat) != (yj > clat) && clon < (xj - xi) * (clat - yi) / (yj - yi) + xi) c = !c; }
                    if (c) in = true;
                    // nur Grenzstücke im Gebiet (+ Rand) übernehmen
                    JSONArray ln = new JSONArray(); double pad = 0.05;
                    for (int i = 0; i < r.length / 2; i++) {
                        double x = r[2 * i], y = r[2 * i + 1];
                        boolean ins = y <= n + pad && y >= s - pad && x <= e + pad && x >= w - pad;
                        if (ins) ln.put(Math.round(x * 1e5) / 1e5).put(Math.round(y * 1e5) / 1e5);
                        else if (ln.length() >= 4) { lines.put(ln); ln = new JSONArray(); } else ln = new JSONArray();
                    }
                    if (ln.length() >= 4) lines.put(ln);
                }
                if (lines.length() == 0 && !in) continue;
                out.put(new JSONObject().put("lvl", lvl).put("code", code).put("name", name).put("src", "BKG")
                        .put("ref", new JSONArray().put(clat).put(clon)).put("refIn", in)
                        .put("bb", new JSONArray().put(w).put(s).put(e).put(n)).put("lines", lines));
            }
        }
        if (out.length() == 0) return false;
        writeFileAtomic(regionFile(), out.toString());
        prefs.edit().putLong("reg_fetched", System.currentTimeMillis())
                .putString("reg_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
        return true;
    }
    private List<Object[]> bkgFeatures(String layer) throws Exception {
        Exception last = null;
        for (String fmt : new String[]{"application/json", "application/geo%2Bjson", ""}) {
            try {
                String url = "https://sgx.geodatenzentrum.de/wfs_vg2500?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=" + layer
                        + "&SRSNAME=EPSG:4326" + (fmt.isEmpty() ? "" : "&OUTPUTFORMAT=" + fmt);
                String t = httpText(url, 60000);
                List<Object[]> res = new ArrayList<>();
                if (t.trim().startsWith("{")) {
                    JSONArray fs = new JSONObject(t).optJSONArray("features"); if (fs == null) continue;
                    for (int i = 0; i < fs.length(); i++) {
                        JSONObject f = fs.getJSONObject(i), p = f.optJSONObject("properties"), g = f.optJSONObject("geometry");
                        if (p == null || g == null) continue;
                        List<double[]> rings = new ArrayList<>();
                        JSONArray co = g.optJSONArray("coordinates");
                        if ("Polygon".equals(g.optString("type"))) addRings(co, rings); else if ("MultiPolygon".equals(g.optString("type"))) for (int k = 0; k < co.length(); k++) addRings(co.getJSONArray(k), rings);
                        res.add(new Object[]{p.optString("GEN", p.optString("gen", "")), p.optString("RS", p.optString("rs", p.optString("ARS", p.optString("ars", p.optString("AGS", p.optString("ags", "")))))), rings});
                    }
                } else if (t.contains("posList") || t.contains("coordinates")) {   // GML: Mitglieder einzeln lesen
                    Matcher mm = Pattern.compile("(?s)<(?:wfs:member|gml:featureMember)>(.*?)</(?:wfs:member|gml:featureMember)>").matcher(t);
                    while (mm.find()) {
                        String b = mm.group(1);
                        Matcher gn = Pattern.compile("(?i)<[\\w]+:gen>([^<]*)<").matcher(b), rs = Pattern.compile("(?i)<[\\w]+:(?:rs|ars|ags)>([^<]*)<").matcher(b);
                        List<double[]> rings = new ArrayList<>();
                        Matcher pl = Pattern.compile("<gml:posList[^>]*>([^<]+)<").matcher(b);
                        while (pl.find()) {
                            String[] v = pl.group(1).trim().split("\\s+"); if (v.length < 6) continue;
                            double[] r = new double[v.length - v.length % 2];
                            boolean latFirst = Double.parseDouble(v[0]) > 40;   // EPSG:4326 in WFS 2.0: Breite zuerst
                            for (int i = 0; i + 1 < v.length; i += 2) { double a = Double.parseDouble(v[i]), c = Double.parseDouble(v[i + 1]); r[i] = latFirst ? c : a; r[i + 1] = latFirst ? a : c; }
                            rings.add(r);
                        }
                        res.add(new Object[]{gn.find() ? gn.group(1) : "", rs.find() ? rs.group(1) : "", rings});
                    }
                }
                if (!res.isEmpty()) return res;
            } catch (Exception ex) { last = ex; }
        }
        if (last != null) throw last;
        return new ArrayList<>();
    }
    private static void addRings(JSONArray poly, List<double[]> rings) throws Exception {
        for (int i = 0; i < poly.length(); i++) {
            JSONArray r = poly.getJSONArray(i); double[] a = new double[r.length() * 2];
            for (int k = 0; k < r.length(); k++) { a[2 * k] = r.getJSONArray(k).getDouble(0); a[2 * k + 1] = r.getJSONArray(k).getDouble(1); }
            if (a.length > 1 && a[1] < 20 && a[0] > 40) for (int k = 0; k < a.length; k += 2) { double t2 = a[k]; a[k] = a[k + 1]; a[k + 1] = t2; }   // vertauscht?
            rings.add(a);
        }
    }

    /** "142 m" / "142,5" -> 142.5, sonst 0 */
    private static double num(String v) {
        try { Matcher m = Pattern.compile("([\\d]+(?:[.,]\\d+)?)").matcher(v == null ? "" : v); return m.find() ? Double.parseDouble(m.group(1).replace(',', '.')) : 0; } catch (Exception e) { return 0; }
    }

    /** Douglas-Peucker (iterativ genug für Grenzlinien) */
    private static void simplify(double[] la, double[] lo, int a, int b, double tol, boolean[] keep) {
        java.util.ArrayDeque<int[]> st = new java.util.ArrayDeque<>();
        st.push(new int[]{a, b});
        while (!st.isEmpty()) {
            int[] r = st.pop();
            int i0 = r[0], i1 = r[1];
            if (i1 <= i0 + 1) continue;
            double ax = lo[i0], ay = la[i0], bx = lo[i1], by = la[i1];
            double dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
            double best = -1; int bi = -1;
            for (int i = i0 + 1; i < i1; i++) {
                double d;
                if (l == 0) d = Math.hypot(lo[i] - ax, la[i] - ay);
                else {
                    double t = Math.max(0, Math.min(1, ((lo[i] - ax) * dx + (la[i] - ay) * dy) / l));
                    d = Math.hypot(lo[i] - ax - t * dx, la[i] - ay - t * dy);
                }
                if (d > best) { best = d; bi = i; }
            }
            if (best > tol) { keep[bi] = true; st.push(new int[]{i0, bi}); st.push(new int[]{bi, i1}); }
        }
    }

    // ---------- Flugplätze (OSM) + Frequenzen (OurAirports, CC-BY) ----------
    private File afFile() { return dataFile("airfields.json"); }
    private File afFreqFile() { return dataFile("airfield_freqs.json"); }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    /** Sicher schreiben: Ordner anlegen (falls Cache-Bereich gelöscht/verschoben wurde), eigene Temp-Datei je Thread, dann umbenennen */
    private static void writeFileAtomic(File f, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) { last = new java.io.IOException("Ordner nicht anlegbar: " + dir); Thread.sleep(150); continue; }
            File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + ".tmp");
            try {
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(data); }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new java.io.IOException("Umbenennen fehlgeschlagen: " + f.getName()); }
                return;
            } catch (Exception e) { last = e; tmp.delete(); Thread.sleep(150); }
        }
        throw last;
    }

    // Hauptserver zuerst (gleiches Zertifikat, drei Rechner), dann Ausweichserver
    private static final String[] OVERPASS = {
            "https://overpass-api.de/api/interpreter", "https://lz4.overpass-api.de/api/interpreter", "https://z.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.osm.jp/api/interpreter"};

    // ---------- DULV-Flugplatzdatenbank: einmal alle Plätze in Deutschland (ca. 700 Seiten) im Hintergrund, alle 90 Tage ----------
    // Der DULV ist mit der Nutzung für die ehrenamtliche Kitzrettung einverstanden. Angaben ohne Gewähr, nicht für die Navigation.
    private File dulvFile() { return dataFile("dulv_airfields.json"); }
    private volatile boolean dulvRunning = false;
    private static final String DULV = "https://dulv.de/fmwebphp/Flugplatz/";
    private static String httpText(String url, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, ehrenamtlich, nicht-kommerziell)");
            if (c.getResponseCode() != 200) throw new Exception("DULV antwortet mit HTTP " + c.getResponseCode());
            byte[] b; try (InputStream in = c.getInputStream()) { b = readBytes(in); }
            String ct = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            String head = new String(b, 0, Math.min(b.length, 2000), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            boolean latin = ct.contains("8859") || ct.contains("1252") || (!ct.contains("utf-8") && (head.contains("8859") || head.contains("1252")));
            String t = new String(b, latin ? java.nio.charset.Charset.forName("windows-1252") : StandardCharsets.UTF_8);
            if (!latin && t.indexOf('\uFFFD') >= 0) t = new String(b, java.nio.charset.Charset.forName("windows-1252"));
            return t;
        } finally { c.disconnect(); }
    }
    private static String htmlText(String h) {
        String t = h.replaceAll("(?is)<script.*?</script>", "").replaceAll("(?i)<br\\s*/?>", "; ").replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü").replace("&szlig;", "ß").replace("&deg;", "°");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(t); StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t\\u00a0]+", " ").replaceAll("(; )+", "; ").trim().replaceAll("^;\\s*|;\\s*$", "");
    }
    /** „50.39.39 N“ → 50.6608 */
    private static double dms(String v) {
        try {
            Matcher m = Pattern.compile("(\\d+)[.° ]+(\\d+)[.' ]+(\\d+(?:[.,]\\d+)?)\\D*([NSEOW])?").matcher(v.trim());
            if (!m.find()) return Double.NaN;
            double d = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0 + Double.parseDouble(m.group(3).replace(',', '.')) / 3600.0;
            String h = m.group(4); if ("S".equals(h) || "W".equals(h)) d = -d;
            return d;
        } catch (Exception e) { return Double.NaN; }
    }
    private JSONObject dulvDetail(int id) throws Exception {
        String h = httpText(DULV + "details.php?id=" + id, 30000);
        JSONObject o = new JSONObject().put("id", id);
        Matcher rm = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(h);
        while (rm.find()) {
            Matcher cm = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rm.group(1));
            List<String> cells = new ArrayList<>();
            while (cm.find()) cells.add(htmlText(cm.group(1)));
            if (cells.size() < 2) continue;
            String lab = cells.get(0).replace(":", "").trim(), val = String.join("; ", cells.subList(1, cells.size())).replaceAll("^(;\\s*)+|(;\\s*)+$", "").trim();
            if (val.isEmpty()) continue;
            String k = null;
            switch (lab) {
                case "Ortskennung": k = "name"; break; case "ICAO": k = "icao"; break; case "Kurzname": k = "short"; break;
                case "Nord": o.put("lat", dms(val)); break; case "Ost": o.put("lon", dms(val)); break;
                case "Lage": k = "lage"; break; case "Höhe": k = "ele"; break; case "Funk": k = "funk"; break;
                case "Platzrunde": k = "runde"; break; case "Besonderheiten Anflug": k = "anflug"; break; case "Danger": k = "danger"; break;
                case "Betriebszeiten": k = "zeiten"; break; case "Betriebsarten": k = "arten"; break; case "Telefon": k = "tel"; break;
                case "Webseite": k = "web"; break; case "Bemerkung": k = "bem"; break;
            }
            if (k != null && !o.has(k)) o.put(k, val.length() > 400 ? val.substring(0, 400) : val);
        }
        return o;
    }
    private void dulvSyncImpl(boolean force) {
        if (dulvRunning || !online()) return;
        File f = dulvFile();
        JSONObject old = null;
        try { if (f.isFile()) old = new JSONObject(readFile(f)); } catch (Exception ignored) { }
        boolean fresh = old != null && old.optBoolean("complete") && System.currentTimeMillis() - old.optLong("t", 0) < 90L * 24 * 3600 * 1000;
        if (!force && fresh) return;
        dulvRunning = true;
        final JSONObject prev = old;
        new Thread(() -> {
            try {
                catSt("flugplaetze", "load");
                String list = httpText(DULV + "show_all.php", 60000);
                // Zeilen: Kennung | Ortskennung (Link details.php?id=) | Zulassung | PLZ – nur deutsche Plätze (PLZ fünfstellig)
                List<int[]> ids = new ArrayList<>(); Map<Integer, String> kenn = new HashMap<>();
                Matcher rm = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(list);
                while (rm.find()) {
                    String row = rm.group(1);
                    Matcher im = Pattern.compile("details\\.php\\?id=(\\d+)").matcher(row); if (!im.find()) continue;
                    int id = Integer.parseInt(im.group(1));
                    Matcher cm = Pattern.compile("(?is)<td[^>]*>(.*?)</td>").matcher(row); List<String> c = new ArrayList<>();
                    while (cm.find()) c.add(htmlText(cm.group(1)));
                    String plz = c.size() > 3 ? c.get(3).replaceAll("\\s", "") : "";
                    if (!plz.matches("\\d{5}")) continue;
                    ids.add(new int[]{id}); kenn.put(id, c.isEmpty() ? "" : c.get(0));
                }
                if (ids.isEmpty()) throw new Exception("DULV-Liste leer oder Format geändert");
                // schon geladene (unter 90 Tage) behalten – Abbruch/Neustart setzt fort
                Map<Integer, JSONObject> have = new java.util.concurrent.ConcurrentHashMap<>();
                if (prev != null && System.currentTimeMillis() - prev.optLong("t0", prev.optLong("t", 0)) < 90L * 24 * 3600 * 1000) {
                    JSONArray pl = prev.optJSONArray("list"); if (pl != null) for (int i = 0; i < pl.length(); i++) { JSONObject o = pl.getJSONObject(i); have.put(o.getInt("id"), o); }
                }
                final long t0 = prev != null && prev.has("t0") && !have.isEmpty() ? prev.optLong("t0") : System.currentTimeMillis();
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(3);
                final int[] done = {have.size()}, fail = {0};
                for (int[] w : ids) {
                    final int id = w[0];
                    if (have.containsKey(id)) continue;
                    ex.submit(() -> {
                        for (int tr = 0; tr < 2; tr++) {
                            try { JSONObject o = dulvDetail(id); if (!o.has("icao") && kenn.get(id) != null && kenn.get(id).matches("[A-Z]{4}")) o.put("icao", kenn.get(id)); have.put(id, o); break; }
                            catch (Exception e) { if (tr == 1) synchronized (fail) { fail[0]++; } else try { Thread.sleep(2000); } catch (InterruptedException ie) { return; } }
                        }
                        synchronized (done) { done[0]++; if (done[0] % 60 == 0) { dulvSave(have, t0, false); catSt("flugplaetze", "load"); } }
                        try { Thread.sleep(150); } catch (InterruptedException ie) { }   // schonend für den DULV-Server
                    });
                }
                ex.shutdown(); ex.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
                boolean complete = fail[0] <= ids.size() / 50;
                dulvSave(have, t0, complete);
                catSt("flugplaetze", complete ? "ok" : (f.isFile() ? "alt:" : "") + "DULV: " + fail[0] + " von " + ids.size() + " Plätzen nicht geladen – wird beim nächsten Mal ergänzt");
                js("window.onDulv && window.onDulv()");
            } catch (Exception e) {
                catSt("flugplaetze", (f.isFile() ? "alt:" : "") + "DULV: " + errTxt(e));
            } finally { dulvRunning = false; }
        }).start();
    }
    private synchronized void dulvSave(Map<Integer, JSONObject> have, long t0, boolean complete) {
        try {
            JSONArray out = new JSONArray(); for (JSONObject o : have.values()) out.put(o);
            writeFileAtomic(dulvFile(), new JSONObject().put("t", System.currentTimeMillis()).put("t0", t0).put("complete", complete).put("list", out).toString());
        } catch (Exception ignored) { }
    }

    /** Overpass-Abfrage; ist ein Server überlastet, wird der nächste versucht */
    private static volatile String overpassGood = null;
    private static final Map<String, Long> overpassDead = new java.util.concurrent.ConcurrentHashMap<>();
    private String overpass(String q, int readTimeoutMs) throws Exception {
        Exception first = null;
        List<String> eps = new ArrayList<>(Arrays.asList(OVERPASS));
        if (overpassGood != null) { eps.remove(overpassGood); eps.add(0, overpassGood); }   // zuletzt funktionierender Server zuerst
        long now = System.currentTimeMillis();
        List<String> alive = new ArrayList<>(); for (String ep : eps) { Long d = overpassDead.get(ep); if (d == null || now - d > 10 * 60000L) alive.add(ep); }
        if (alive.isEmpty()) alive = eps;   // alle gesperrt: trotzdem nochmal alle versuchen
        for (String ep : alive) {
          for (int tr = 0; tr < 2; tr++) {   // überlastet (429/504): kurz warten, nochmal
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000); c.setReadTimeout(readTimeoutMs);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("User-Agent", "MissionKitzrettung/2.0 (Kitzrettung, nicht-kommerziell)");
                try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code != 200) {
                    c.disconnect();
                    Exception e = new Exception(code == 429 ? "OpenStreetMap-Server überlastet (zu viele Anfragen)" : code == 504 || code == 503 ? "OpenStreetMap-Server ausgelastet (Zeitüberschreitung)" : "OpenStreetMap-Server antwortet mit Fehler " + code);
                    if (first == null) first = e;
                    if ((code == 429 || code == 504) && tr == 0) { Thread.sleep(6000); continue; }
                    break;
                }
                try (InputStream in = c.getInputStream()) { String res = readAll(in); overpassGood = ep; overpassDead.remove(ep); return res; } finally { c.disconnect(); }
            } catch (Exception e) {
                // keine Verbindung / Zertifikat: diesen Server 10 min auslassen (statt bei jeder Kachel wieder 8–15 s zu warten)
                if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException && String.valueOf(e.getMessage()).contains("connect")
                        || e instanceof java.net.UnknownHostException || e instanceof javax.net.ssl.SSLException || String.valueOf(e.getMessage()).contains("Failed to connect"))
                    overpassDead.put(ep, System.currentTimeMillis());
                // Zertifikatsfehler eines Ausweichservers (alte Android-Version kennt dessen Zertifikat nicht) nicht als Hauptgrund melden
                if (first == null && !(e instanceof javax.net.ssl.SSLException) && !String.valueOf(e.getMessage()).contains("CertPath")) first = e;
                break;
            }
          }
        }
        throw first != null ? first : new Exception("Kein OpenStreetMap-Server erreichbar");
    }


    /** Manche Server (z. B. dulv.de, einzelne OpenStreetMap-Spiegel) schicken ihr Zwischenzertifikat nicht mit; Android baut die Kette dann nicht.
     *  Hier wird das fehlende Zwischenzertifikat über die im Zertifikat genannte Adresse (AIA) nachgeladen und die Kette danach
     *  ganz normal gegen die Android-Stammzertifikate geprüft – es wird also nichts ungeprüft akzeptiert. */
    private static void installAiaTrust() {
        try {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            javax.net.ssl.X509TrustManager def = null;
            for (javax.net.ssl.TrustManager t : tmf.getTrustManagers()) if (t instanceof javax.net.ssl.X509TrustManager) def = (javax.net.ssl.X509TrustManager) t;
            if (def == null) return;
            final javax.net.ssl.X509TrustManager d = def;
            javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException { d.checkClientTrusted(c, a); }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return d.getAcceptedIssuers(); }
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) throws java.security.cert.CertificateException {
                    try { d.checkServerTrusted(c, a); }
                    catch (java.security.cert.CertificateException ce) {
                        java.security.cert.X509Certificate[] ext = aiaComplete(c);
                        try { if (ext == null) throw ce; d.checkServerTrusted(ext, a); }
                        catch (java.security.cert.CertificateException ce2) {
                            // Stammzertifikat fehlt in älteren Android-Versionen (z. B. Certum bei dulv.de auf Android 11): gegen mitgelieferte Wurzel prüfen
                            javax.net.ssl.X509TrustManager x = extraRoots();
                            if (x == null) throw ce;
                            x.checkServerTrusted(ext != null ? ext : c, a);
                        }
                    }
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        } catch (Exception ignored) { }
    }
    /** Mitgelieferte Stammzertifikate, die auf Android 11 fehlen können. Certum Trusted Root CA (gültig bis 2043),
     *  SHA-256 FE:76:96:57:38:55:77:3E:37:A9:5E:7A:D4:D9:CC:96:C3:01:57:C1:5D:31:76:5B:A9:B1:57:04:E1:AE:78:FD */
    private static final String[] EXTRA_ROOTS = {"MIIFwDCCA6igAwIBAgIQHr9ZULjJgDdMBvfrVU+17TANBgkqhkiG9w0BAQ0FADB6MQswCQYDVQQGEwJQTDEhMB8GA1UEChMYQXNzZWNvIERhdGEgU3lzdGVtcyBTLkEuMScwJQYDVQQLEx5DZXJ0dW0gQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxHzAdBgNVBAMTFkNlcnR1bSBUcnVzdGVkIFJvb3QgQ0EwHhcNMTgwMzE2MTIxMDEzWhcNNDMwMzE2MTIxMDEzWjB6MQswCQYDVQQGEwJQTDEhMB8GA1UEChMYQXNzZWNvIERhdGEgU3lzdGVtcyBTLkEuMScwJQYDVQQLEx5DZXJ0dW0gQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxHzAdBgNVBAMTFkNlcnR1bSBUcnVzdGVkIFJvb3QgQ0EwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDRLY67tzbqbTeRn06TpwXkKQMlzhyC93yZn0EGze2jusDbCSzBfN8pfktlL5On1AFrAygYo9idBcEq2EXxkd7fO9CAAozPOA/qp1x4EaTByIVcJdPTsuclzxFUl6s1wB52HO8AU5853BSlLCIls3Jy/I2z5T4IHhQqNwuIPMqw9MjCoa68wb4pZ1Xi/K1ZXP69VyywkI3C7Te2fJmItdUDmj0VDT06qKhF8JVOJVkdzZhpu9PMMsmN74H+rX2Ju7pgE8pllWeg8xn2A1bUatMn4qGtg/BKEiJ3HAVz4hlxQsDsdUaakFjgao4rpUYwBI4Zshfjvqm6f1bxJAPXsiEodg42MEx51UGamqi4NboMOvJEGyCI98Ul1z3G4z5D3Yf+xOr1Uz5MZf87Sst4WmsXXw3Hw09Omiqi7VdNIuJGmj8PkTQkfVXjjJU30xrwCSss0smNtA0Aq2cpKNgB9RkEth2+dv5yXMSFytKAQd8FqKPVhJBPC/PgP5sZ0jeJP/J7UhyM9uH3PAeXjA6iWYEMspA90+NZRu0PqafegGtaqge2Gcu8V/OXIXoMsSt0Puvap2ctTMSYnjYJdmZm/Bo/6khUHL4wvYBQv3y1zgD2DGHZ5yQD4OMBgQ692IU0iL2yNqh7XAjlRICMb/gv1SHKHRzQ+8S1h9E6Tsd2tTVItQIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBSM+xx1vALTn04uSNn5YFSqxLNP+jAOBgNVHQ8BAf8EBAMCAQYwDQYJKoZIhvcNAQENBQADggIBAEii1QALLtA/vBzVtVRJHlpr9OTy4EA34MwUe7nJ+jW1dReTagVphZzNTxl4WxmB82M+w85bj/UvXgF2Ez8sALnNllI5SW0ETsXpD4YN4fqzX4IS8TrOZgYkNCvozMrnadyHncI013nR03e4qllY/p0m+jiGPp2Kh2RX5Rc64vmNueMzeMGQ2Ljdt4NR5MTMI9UGfOZR0800McD2RrsLrfw9EAUqO0qRJe6M1ISHgCq8CYyqOhNf6DR5UMEQGfnTKB7U0VEwKbOukGfWHwpjscWpxkIxYxeU72nLL/qMFH3EQxiJ2fAyQOaA4kZf5ePBAFmo+eggvIksDkc0C+pXwlM2/KfUrzHN/gLldfq5Jwn58/U7yn2fqSLLiMmq0Uc9NneoWWRrJ8/vJ8HjJLWG965+Mk2weWjROeiQWMODvA8s1pfrzgzhIMfatz7DP78v3DSk+yshzWePS/Tj6tQ/50+6uaWTRRxmHyH6ZF5v4HaUMst19W7l9o/HuKTMqJZ9ZPskWkoDbGs4xugDQ5r3V7mzKWmTOPQD8rv7gmsHINFSH5pkAnuYZttcTVoP0ISVoDwUQwbKytu4QTbaakRnh6+v40URFWkIsr4WOZckbxJF0WddCajJFdr60qZfE2Efv4WstK2tBZQIgx51F9NxO5NQI1mg7TyRVJ12AMXDuDjb"};
    private static volatile javax.net.ssl.X509TrustManager extraTm = null;
    private static javax.net.ssl.X509TrustManager extraRoots() {
        if (extraTm != null) return extraTm;
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); ks.load(null, null);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            for (int i = 0; i < EXTRA_ROOTS.length; i++)
                ks.setCertificateEntry("extra" + i, cf.generateCertificate(new java.io.ByteArrayInputStream(android.util.Base64.decode(EXTRA_ROOTS[i], android.util.Base64.DEFAULT))));
            javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            f.init(ks);
            for (javax.net.ssl.TrustManager t : f.getTrustManagers()) if (t instanceof javax.net.ssl.X509TrustManager) { extraTm = (javax.net.ssl.X509TrustManager) t; break; }
        } catch (Exception ignored) { }
        return extraTm;
    }
    private static final Map<String, java.security.cert.X509Certificate> AIA_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.security.cert.X509Certificate[] aiaComplete(java.security.cert.X509Certificate[] chain) {
        try {
            List<java.security.cert.X509Certificate> l = new ArrayList<>(Arrays.asList(chain));
            for (int i = 0; i < 3; i++) {
                java.security.cert.X509Certificate last = l.get(l.size() - 1);
                if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())) break;   // schon bei der Wurzel
                byte[] ext = last.getExtensionValue("1.3.6.1.5.5.7.1.1"); if (ext == null) break;
                String u = aiaIssuerUrl(ext); if (u == null) break;
                java.security.cert.X509Certificate ic = AIA_CACHE.get(u);
                if (ic == null) {
                    // einfache HTTP-Abfrage über einen Socket (Android sperrt http für HttpURLConnection); Inhalt wird unten kryptografisch geprüft
                    byte[] body = rawHttpGet(u);
                    if (body == null) break;
                    java.util.Collection<? extends java.security.cert.Certificate> cs = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(body));
                    for (java.security.cert.Certificate x : cs) { ic = (java.security.cert.X509Certificate) x; break; }
                    if (ic == null) break;
                    AIA_CACHE.put(u, ic);
                }
                last.verify(ic.getPublicKey());   // passt das nachgeladene Zertifikat wirklich?
                l.add(ic);
            }
            return l.size() > chain.length ? l.toArray(new java.security.cert.X509Certificate[0]) : null;
        } catch (Exception e) { return null; }
    }

    /** Adresse des ausstellenden Zertifikats (caIssuers, OID 1.3.6.1.5.5.7.48.2) aus der AIA-Erweiterung */
    static String aiaIssuerUrl(byte[] ext) {
        byte[] oid = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};
        for (int i = 0; i + oid.length + 2 < ext.length; i++) {
            boolean ok = true; for (int j = 0; j < oid.length; j++) if (ext[i + j] != oid[j]) { ok = false; break; }
            if (!ok) continue;
            int p = i + oid.length; if ((ext[p] & 0xff) != 0x86) continue;
            int len = ext[p + 1] & 0xff, off = p + 2;
            if (len == 0x81) { len = ext[p + 2] & 0xff; off = p + 3; }
            if (off + len > ext.length) return null;
            String u = new String(ext, off, len, StandardCharsets.ISO_8859_1);
            return u.startsWith("http://") ? u : null;
        }
        return null;
    }
    private static byte[] rawHttpGet(String url) {
        try {
            URL u = new URL(url);
            try (java.net.Socket so = new java.net.Socket()) {
                so.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 10000); so.setSoTimeout(15000);
                String path = u.getFile().isEmpty() ? "/" : u.getFile();
                so.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\nUser-Agent: MissionKitzrettung\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                byte[] all = readBytes(so.getInputStream());
                for (int i = 0; i + 3 < all.length; i++)
                    if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                        String head = new String(all, 0, i, StandardCharsets.ISO_8859_1);
                        if (!head.startsWith("HTTP/1.") || !head.substring(9, 12).equals("200")) return null;
                        return Arrays.copyOfRange(all, i + 4, all.length);
                    }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Overpass-Abfrage in Kacheln (Kantenlänge ca. tileKm, höchstens maxK×maxK), bis zu 3 Durchgänge mit Pause.
     *  Ergebnis: {JSONArray elemente (ohne Doppelte), Integer fehlende Teile, Integer Teile gesamt, Exception letzter Fehler} */
    private Object[] overpassTiles(String[] filters, double n, double s, double e, double w, double tileKm, int maxK, String cat) throws Exception {
        double km = Math.max((n - s) * 111.2, (e - w) * 111.2 * Math.cos(Math.toRadians((n + s) / 2)));
        int k = Math.max(1, Math.min(maxK, (int) Math.ceil(km / tileKm)));
        JSONArray out = new JSONArray(); java.util.Set<String> seen = new java.util.HashSet<>(); Exception err = null;
        List<int[]> todo = new ArrayList<>();
        for (int iy = 0; iy < k; iy++) for (int ix = 0; ix < k; ix++) todo.add(new int[]{iy, ix});
        for (int pass = 0; pass < 3 && !todo.isEmpty(); pass++) {
            if (pass > 0) Thread.sleep(pass * 20000L);
            List<int[]> failed = new ArrayList<>();
            for (int[] t : todo) {
                double ts = s + (n - s) * t[0] / k, tn = s + (n - s) * (t[0] + 1) / k, tw = w + (e - w) * t[1] / k, te = w + (e - w) * (t[1] + 1) / k;
                String tb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", ts, tw, tn, te);
                StringBuilder q = new StringBuilder("[out:json][timeout:90];(");
                for (String f : filters) q.append(f).append("(").append(tb).append(");");
                q.append(");out tags geom;");
                if (cat != null) catSt(cat, ovLoad);
                try {
                    JSONArray part = new JSONObject(overpass(q.toString(), 120000)).getJSONArray("elements");
                    for (int i = 0; i < part.length(); i++) { JSONObject el = part.getJSONObject(i); if (seen.add(el.optString("type") + el.optLong("id"))) out.put(el); }
                } catch (Exception ex) { failed.add(t); err = ex; }
                Thread.sleep(1500);
            }
            todo = failed;
        }
        return new Object[]{out, todo.size(), k * k, err};
    }

    private static List<String> csvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else if (ch == '"') q = false;
                else sb.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        out.add(sb.toString());
        return out;
    }

    /** Hubschrauberlandeplätze in Deutschland aus OurAirports (gemeinfrei) – ergänzt OpenStreetMap: Kennung|Name|Breite|Länge */
    static final String OA_HELI = "D030|Balingen Healthcare Hubschrauberlandeplatz|48.28705|8.86341\nD072|Burladingen Hubschrauberlandeplatz (Fa. Trigema)|48.28802|9.10657\nD125|Esslingen Klinikum Rooftop Hubschrauberlandeplatz|48.73750|9.32694\nD154|Gerlingen Fa. Bosch Hubschrauberlandeplatz|48.78552|9.06290\nD304|Nagold Krankenhaus Helitop|48.54164|8.72279\nD371|Reutlingen Klinikum Rooftop Hubschrauberlandeplatz|48.48398|9.21847\nD372|Reutlingen Betzingen Hubschrauberlandeplatz|48.49153|9.15509\nD415|Sindelfingen Fa. Daimler Hubschrauberlandeplatz|48.69286|8.98576\nD429|Robert Bosch Krankenhaus Helistop|48.81388|9.18547\nD430|Stuttgart Katharinen Krankenhaus Helistop|48.78383|9.17317\nD431|Stuttgart Marien Krankenhaus Rooftop Hubschrauberlandeplatz|48.76108|9.16386\nD447|Untertürkheim Fa. Daimler Rooftop Hubschrauberlandeplatz|48.78216|9.23966\nDE-0001|Klinikum am Plattenwald Hubschrauberlandeplatz|49.21286|9.23673\nDE-0014|Rostock University Hubschrauberlandeplatz|54.08330|12.10050\nDE-0015|Dresden Friedrichstadt Krankenhaus Hubschrauberlandeplatz|51.05840|13.71606\nDE-0016|Erlangen Uniklinik Ebrardstraße Hubschrauberlandeplatz|49.60202|11.02244\nDE-0018|Frankfurt am Main Accident Krankenhaus Roof Hubschrauberlandeplatz (CHX2)|50.14542|8.71011\nDE-0020|Höchst Industrial Park Hubschrauberlandeplatz|50.08939|8.53805\nDE-0021|Biblis Nuclear Power Plant Hubschrauberlandeplatz|49.70750|8.41750\nDE-0022|Mainz ZDF-Complex Hubschrauberlandeplatz|49.96620|8.21270\nDE-0023|Bernkastel-Kues Cusanus Krankenhaus Hubschrauberlandeplatz|49.91570|7.06230\nDE-0024|Remagen Rolandseck Hubschrauberlandeplatz|50.63208|7.20765\nDE-0025|Osnabrück Krankenhaus Hubschrauberlandeplatz|52.27790|8.00480\nDE-0026|Meppen Ludmillenstift Krankenhaus Hubschrauberlandeplatz|52.68950|7.29440\nDE-0027|Grafenrheinfeld Nuclear Power Plant Hubschrauberlandeplatz|49.98224|10.19140\nDE-0030|Traunstein Krankenhaus Hubschrauberlandeplatz|47.87414|12.63141\nDE-0031|Bad Reichenhall Krankenhaus Hubschrauberlandeplatz|47.72849|12.87248\nDE-0032|Berchtesgaden Krankenhaus Hubschrauberlandeplatz|47.64035|13.00605\nDE-0033|Freilassing Krankenhaus Hubschrauberlandeplatz|47.84850|12.97974\nDE-0034|Trostberg Krankenhaus Hubschrauberlandeplatz|48.02913|12.54319\nDE-0035|Helgoland Navy Helicopter Base|54.17511|7.89002\nDE-0036|Grafenwöhr Medevac Hubschrauberlandeplatz|49.70806|11.91611\nDE-0037|Vilseck Main Army Hubschrauberlandeplatz|49.64944|11.80583\nDE-0070|Frankfurt am Main Polizei Headquarters Hubschrauberlandeplatz|50.13276|8.67318\nDE-0212|Evangelisches Jung-Stilling Krankenhaus Hubschrauberlandeplatz|50.85180|8.01840\nDE-0214|Gießen Uniklinik Hubschrauberlandeplatz|50.57590|8.66710\nDE-0216|Heilbronn Krankenhaus Hubschrauberlandeplatz|49.15320|9.19180\nDE-0243|Lobeda Hubschrauberlandeplatz|50.89538|11.60270\nDE-0245|SRH Krankenhaus Hubschrauberlandeplatz|50.60300|10.71040\nDE-0264|Helios Sankt Elisabeth Krankenhaus Bad Kissingen Hubschrauberlandeplatz|50.19024|10.08264\nDE-0265|Kißlegg Autobahnpolizei Hubschrauberlandeplatz|47.76890|9.89210\nDE-0267|Tübingen Trauma Krankenhaus Helistop|48.53298|9.03551\nDE-0268|Tübingen Uniklinik Rooftop Hubschrauberlandeplatz (Crona Kliniken)|48.52973|9.03809\nDE-0373|Günzburg Krankenhaus Hubschrauberlandeplatz|48.45810|10.29160\nDE-0374|Legoland Hubschrauberlandeplatz|48.42590|10.29850\nDE-0384|Kemnitz Hubschrauberlandeplatz|54.08242|13.53615\nDE-0410|Rupin Krankenhaus Hubschrauberlandeplatz|52.90397|12.79634\nDE-0418|DRF Luftrettung München Hubschrauberlandeplatz|48.11056|11.46524\nDE-0420|Greifswald Krankenhaus Hubschrauberlandeplatz|54.08908|13.40818\nDE-0421|Neustrelitz Hubschrauberlandeplatz|53.38212|13.05212\nDE-0422|Aachen Krankenhaus Hubschrauberlandeplatz|50.77567|6.04437\nDE-0423|Bad Saarow Krankenhaus Hubschrauberlandeplatz|52.28442|14.06036\nDE-0429|Böblingen Krankenhaus Hubschrauberlandeplatz|48.69055|9.03221\nDE-0430|Sindelfingen City Krankenhaus Helistop|48.71495|9.02438\nDE-0431|Oldenburg Polizei Hubschrauberlandeplatz|53.21621|8.21777\nDE-0434|Christoph 12 Luftrettungszentrum|54.03494|10.56252\nDE-0437|Luftrettungszentrum Bielefeld|51.98269|8.52871\nDE-0442|Sankt Elisabeth Krankenhaus Iserlohn Hubschrauberlandeplatz|51.38035|7.69069\nDE-0444|Borkum Hubschrauberlandeplatz|53.56665|6.75576\nDE-0446|Rhein-Maas Krankenhaus Hubschrauberlandeplatz|50.81488|6.14321\nDE-0450|Duisburg Luftrettung Hubschrauberlandeplatz (Christoph 9)|51.38021|6.78730\nDE-0451|Düdinghausen Auxiliary Helicopter Base|52.58029|8.93266\nDE-0457|Bonn Uniklinik Hubschrauberlandeplatz|50.70175|7.10241\nDE-0458|Berlin Emergency Krankenhaus Hubschrauberlandeplatz|52.51926|13.56769\nDE-0459|Helios Klinik Berlin-Buch Hubschrauberlandeplatz|52.63000|13.51039\nDE-0460|Vivantes Humboldt Klinik Hubschrauberlandeplatz|52.59042|13.30846\nDE-0461|Charité Klinik Virchow Campus Hubschrauberlandeplatz|52.54049|13.34266\nDE-0462|Vivantes Freidrichshain Klinik Hubschrauberlandeplatz|52.52701|13.43915\nDE-0463|Berlin Tempelhof Hubschrauberlandeplatz|52.48243|13.39561\nDE-0464|Charité Klinik Benjamin Franklin Campus Hubschrauberlandeplatz|52.44135|13.32286\nDE-0465|Havelhöhe Medical Hubschrauberlandeplatz|52.46104|13.16276\nDE-0467|Ernst von Bergmann Klinik Hubschrauberlandeplatz|52.40015|13.06554\nDE-0468|ADAC Luftrettung Center Hubschrauberlandeplatz (Christoph 1)|48.08366|11.55941\nDE-0469|Murnau Accident Krankenhaus Hubschrauberlandeplatz|47.67214|11.21545\nDE-0470|DRF Air Rettungswache Regensburg / Regensburg Uniklinik Hubschrauberlandeplatz|48.98706|12.09297\nDE-0471|Rechts der Isar Uniklinik Hubschrauberlandeplatz|48.13601|11.60068\nDE-0472|München Bogenhausen Krankenhaus Hubschrauberlandeplatz|48.15431|11.62618\nDE-0473|Deutsches Herzzentrum München Hubschrauberlandeplatz|48.15279|11.54855\nDE-0474|München South Surgical Krankenhaus Hubschrauberlandeplatz|48.10304|11.54825\nDE-0475|Aussenlandeplatz Innenstadtkliniken Goetheplatz|48.13321|11.56226\nDE-0476|Garmisch-Partenkirchen Klinik Hubschrauberlandeplatz|47.48440|11.12906\nDE-0482|Lehnin Military Training Area Hubschrauberlandeplatz|52.22645|12.80727\nDE-0483|Lehnin Military Training Area Fläming Barracks Hubschrauberlandeplatz|52.21366|12.79869\nDE-0484|IABG Hubschrauberlandeplatz|51.60917|8.93517\nDE-0485|Dresden Federal Polizei Hubschrauberlandeplatz|51.13370|13.77355\nDE-0486|Christoph  Westfalen-ADAC|52.12338|7.69031\nDE-0487|ADAC Air Rettungswache Rheine Hubschrauberlandeplatz (Christoph Europa 2)|52.27728|7.43142\nDE-0488|Luftstützpunkt Meppen Hubschrauberlandeplatz|52.72632|7.32988\nDE-0489|Reinbek St. Adolf-Stift Krankenhaus Hubschrauberlandeplatz|53.50894|10.24186\nDE-0490|Bethesda Krankenhaus Hubschrauberlandeplatz|53.48615|10.23468\nDE-0491|Wilhelmstift Kath. Children's Krankenhaus Hubschrauberlandeplatz|53.58744|10.16424\nDE-0492|Hamburg Bundeswehrkrankenhaus Hubschrauberlandeplatz|53.59687|10.07786\nDE-0493|Johanniter Krankenhaus Hubschrauberlandeplatz|53.43184|10.38549\nDE-0494|Asklepios Krankenhaus Harburg Hubschrauberlandeplatz|53.45979|9.95083\nDE-0495|Asklepios Krankenhaus Altona Hubschrauberlandeplatz|53.55543|9.90235\nDE-0496|University of Hamburg Klinikum Eppendorf Hubschrauberlandeplatz|53.59152|9.97396\nDE-0497|Frankfurt Westside Hubschrauberlandeplatz|50.08962|8.58557\nDE-0500|Helios Klinik Wiesbaden Hubschrauberlandeplatz|50.08687|8.27111\nDE-0501|Helios Horst Schmidt Klinik (HSK) Hubschrauberlandeplatz|50.06360|8.18951\nDE-0502|Benedictus Krankenhaus Hubschrauberlandeplatz|47.90597|11.27647\nDE-0503|Wolfratshausen Kreis-Klinik Hubschrauberlandeplatz|47.91171|11.42951\nDE-0504|Asklepios Klinik Bad Tölz Hubschrauberlandeplatz|47.75594|11.55160\nDE-0505|Agatharied Krankenhaus Hubschrauberlandeplatz|47.76841|11.82824\nDE-0506|RoMed Krankenhaus Rosenheim Hubschrauberlandeplatz|47.86040|12.13221\nDE-0507|Wasserburg Kreis-Krankenhaus Hubschrauberlandeplatz|48.06337|12.21310\nDE-0508|Lübbecke Krankenhaus Hubschrauberlandeplatz|52.29628|8.60262\nDE-0509|Agaplesion Markus Krankenhaus Hubschrauberlandeplatz|50.13542|8.64781\nDE-0510|Frankfurt Uniklinik Hubschrauberlandeplatz|50.09565|8.66248\nDE-0511|Frankfurt Uniklinik Neurology Klinik Hubschrauberlandeplatz|50.09148|8.64743\nDE-0512|Northwest Krankenhaus Hubschrauberlandeplatz|50.15007|8.61875\nDE-0513|Hochtaunus Krankenhaus Hubschrauberlandeplatz|50.21240|8.61915\nDE-0516|Dresden Neustadt Krankenhaus Hubschrauberlandeplatz|51.09002|13.70950\nDE-0517|Würzburg Uniklinik Straubmühlweg Hubschrauberlandeplatz|49.80971|9.95594\nDE-0523|Passau Klinik Hubschrauberlandeplatz|48.56352|13.44477\nDE-0524|ADAC Air Rettungswache Senftenberg Hubschrauberlandeplatz|51.53067|13.98819\nDE-0525|Nennig Hubschrauberlandeplatz|49.53668|6.38792\nDE-0526|Nürburgring Hubschrauberlandeplatz|50.33124|6.94311\nDE-0527|Spiekeroog Hubschrauber-Notlandeplatz|53.76664|7.69820\nDE-0528|Dresden Uniklinik Hubschrauberlandeplatz|51.05757|13.78001\nDE-0529|Dresden Elbwiesen Hubschrauberlandeplatz|51.06144|13.78079\nDE-0530|Coswig Specialty Krankenhaus Hubschrauberlandeplatz|51.13189|13.59310\nDE-0531|Dernbach Sacred Heart Krankenhaus Hubschrauberlandeplatz|50.45302|7.78650\nDE-0532|St. Vincent Krankenhaus Limburg Hubschrauberlandeplatz|50.38764|8.05149\nDE-0534|Eduardus Krankenhaus Hubschrauberlandeplatz|50.93188|6.98074\nDE-0535|Köln Uniklinik Hubschrauberlandeplatz|50.92398|6.91981\nDE-0536|Elisabeth Krankenhaus Rheydt Hubschrauberlandeplatz|51.15698|6.41791\nDE-0537|Maria Hilf Klinik Hubschrauberlandeplatz|51.21732|6.42161\nDE-0538|Hermann-Josef Krankenhaus Erkelenz Hubschrauberlandeplatz|51.07460|6.31341\nDE-0539|Rheinaue Hubschrauberlandeplatz|50.70948|7.13890\nDE-0540|Porz am Rhein Krankenhaus Hubschrauberlandeplatz|50.89163|7.05102\nDE-0541|Merheim Krankenhaus Hubschrauberlandeplatz|50.93864|7.05412\nDE-0542|Sankt Vinzenz Krankenhaus Hubschrauberlandeplatz|50.96678|6.94577\nDE-0543|Amsterdam Street Children's Krankenhaus Hubschrauberlandeplatz|50.96237|6.96690\nDE-0544|Sankt Johannes Krankenhaus Hubschrauberlandeplatz|50.79674|7.11828\nDE-0545|Helios Klinik Wipperfürth Hubschrauberlandeplatz|51.11220|7.38447\nDE-0546|Vinzenz-Pallotti Krankenhaus Hubschrauberlandeplatz|50.96117|7.17554\nDE-0547|Riesa Krankenhaus Hubschrauberlandeplatz|51.29502|13.31389\nDE-0548|Collm Klinik Oschatz Hubschrauberlandeplatz|51.29187|13.11432\nDE-0549|RoMed Krankenhaus Bad Aibling Hubschrauberlandeplatz|47.86548|12.01773\nDE-0550|Pirna Krankenhaus Hubschrauberlandeplatz|50.95862|13.95239\nDE-0551|Kath. Klinik Marienhof Koblenz Hubschrauberlandeplatz|50.35987|7.57101\nDE-0552|Helios Klinik Bonn / Rhein-Seig Hubschrauberlandeplatz|50.70756|7.03309\nDE-0553|Nettetal City Krankenhaus Hubschrauberlandeplatz|51.30855|6.27125\nDE-0554|Buttstädt Hubschrauberlandeplatz|51.11052|11.32703\nDE-0555|Freising Klinik Hubschrauberlandeplatz|48.40440|11.74402\nDE-0556|Landshut Krankenhaus Hubschrauberlandeplatz|48.53758|12.13869\nDE-0557|Enghausen Hubschrauberlandeplatz|48.53653|11.90451\nDE-0558|Ilmtal Krankenhaus Pfaffenhofen Hubschrauberlandeplatz|48.51860|11.50034\nDE-0559|Kösching Krankenhaus Hubschrauberlandeplatz|48.81558|11.50780\nDE-0560|Viersen General Krankenhaus Hubschrauberlandeplatz|51.25142|6.38142\nDE-0561|Asklepios Children's Klinik Sankt Augustin Hubschrauberlandeplatz|50.77646|7.18020\nDE-0562|Saxon Switzerland Klinik Sebnitz Hubschrauberlandeplatz|50.98280|14.26838\nDE-0563|Asklepios Klinik Langen Hubschrauberlandeplatz|49.99727|8.68459\nDE-0564|Wurzen Krankenhaus Hubschrauberlandeplatz|51.37923|12.73214\nDE-0565|Ingolstadt Krankenhaus Hubschrauberlandeplatz|48.77048|11.37254\nDE-0566|Regiomed Krankenhaus Coburg Hubschrauberlandeplatz|50.24666|10.97510\nDE-0567|Medinos Kliniks Sonneberg Kreis-Hubschrauberlandeplatz|50.35401|11.16230\nDE-0568|Memmingen Krankenhaus Hubschrauberlandeplatz|47.98258|10.16873\nDE-0569|Sana Klinik Biberach Kreis-Krankenhaus Hubschrauberlandeplatz|48.11311|9.77936\nDE-0570|Ulm Bundeswehrkrankenhaus Hubschrauber-Notlandeplatz|48.42492|9.94762\nDE-0571|Westallgäu Klinik Wangen Hubschrauberlandeplatz|47.69569|9.84637\nDE-0572|Sankt Elisabeth Krankenhaus Hubschrauberlandeplatz|47.78801|9.62105\nDE-0573|Helios Klinik Dachau Hubschrauberlandeplatz|48.26608|11.42828\nDE-0574|Konstanz Krankenhaus Hubschrauberlandeplatz|47.67203|9.18869\nDE-0575|Weißer Hirsch Krankenhaus Hubschrauberlandeplatz|51.06518|13.83706\nDE-0576|Düsseldorf Uniklinik Hubschrauberlandeplatz|51.19524|6.78924\nDE-0577|Johanna-Etienne Krankenhaus Hubschrauberlandeplatz|51.21284|6.65982\nDE-0578|Helios Klinik Krefeld Hubschrauberlandeplatz|51.31996|6.56446\nDE-0579|Kemperhof Koblenz Klinik Hubschrauberlandeplatz|50.35648|7.56574\nDE-0580|Sankt-Nikolaus Diocesan Krankenhaus Hubschrauberlandeplatz|50.44161|7.40338\nDE-0581|Ochtendung Hubschrauberlandeplatz|50.35352|7.42395\nDE-0582|Ebersberg Hubschrauberlandeplatz|48.08125|11.95861\nDE-0583|Mainz Uniklinik Hubschrauberlandeplatz|49.99405|8.25470\nDE-0584|Weinheim Krankenhaus Hubschrauberlandeplatz|49.55042|8.64409\nDE-0585|Helios Klinik Leisnig Hubschrauberlandeplatz|51.15590|12.91885\nDE-0586|Juraklinik Scheßlitz Hubschrauberlandeplatz|49.98010|11.03935\nDE-0587|Ilm Kreis-Klinik Arnstadt-Ilmenau Hubschrauberlandeplatz|50.67973|10.91826\nDE-0588|Sankt Josef Krankenhaus Hubschrauberlandeplatz|48.03333|10.72428\nDE-0589|Landsberg am Lech Krankenhaus Hubschrauberlandeplatz|48.04754|10.85646\nDE-0591|Thuringia Krankenhaus Saalfeld Hubschrauberlandeplatz|50.64393|11.34214\nDE-0592|Asklepios ASB Krankenhaus Radeberg Hubschrauberlandeplatz|51.12457|13.93073\nDE-0593|Asklepios Krankenhaus München-Gauting Hubschrauberlandeplatz|48.06684|11.35257\nDE-0594|Mittlerhein Community Krankenhaus Sankt Elisabeth Mayen Hubschrauberlandeplatz|50.32331|7.21985\nDE-0595|Jakobsberg Hubschrauberlandeplatz|50.25589|7.59564\nDE-0596|Osterspai Freiwillige Feuerwehr Hubschrauberlandeplatz|50.24557|7.59856\nDE-0597|RoMed Krankenhaus Prien Hubschrauberlandeplatz|47.85012|12.36949\nDE-0598|RoMed Krankenhaus Wasserburg Hubschrauberlandeplatz|48.05886|12.19701\nDE-0599|Bergstraße Kreis-Krankenhaus Hubschrauberlandeplatz|49.62937|8.62350\nDE-0600|Gensingen Freiwillige Feuerwehr Hubschrauberlandeplatz|49.89160|7.92980\nDE-0601|Hannover Luftrettung Hubschrauberlandeplatz (Christoph 4)|52.38496|9.80657\nDE-0602|Wolfenbüttel Municipal Krankenhaus Hubschrauberlandeplatz (Christoph 30)|52.17923|10.53453\nDE-0603|ADAC Air Rettungswache Bremen Hubschrauberlandeplatz (Christoph 6)|53.03528|8.81700\nDE-0604|ADAC Air Rettungswache Uelzen Hubschrauberlandeplatz (Christoph 19)|52.97323|10.53266\nDE-0605|Federal Office of Administration Hubschrauberlandeplatz|50.97039|6.97086\nDE-0606|ADAC Air Rettungswache Leipzig/Schkeuditz-Dölzig Hubschrauberlandeplatz (Christoph 61)|51.35124|12.19252\nDE-0607|Darmstadt Krankenhaus Hubschrauberlandeplatz|49.87451|8.64535\nDE-0608|Sana Klinik Pegnitz Hubschrauberlandeplatz|49.75779|11.52650\nDE-0609|Sana Krankenhaus Offenbach Hubschrauberlandeplatz|50.09449|8.75702\nDE-0610|Grafenwöhr South Hubschrauberlandeplatz|49.62768|11.67311\nDE-0611|Grafenwöhr West Hubschrauberlandeplatz|49.66886|11.65067\nDE-0612|Sankt Johannes Krankenhaus Auerbach Hubschrauberlandeplatz|49.69805|11.62001\nDE-0613|Jena Uniklinik Hubschrauberlandeplatz|50.88672|11.61895\nDE-0614|Barmherzige Brüder (Brothers of Mercy) Krankenhaus Regensburg Hubschrauberlandeplatz|49.01728|12.06322\nDE-0615|Lakumed Krankenhaus Landshut-Achdorf Hubschrauberlandeplatz|48.52405|12.15101\nDE-0616|Bernkastel/Wittlich Compound Krankenhaus Hubschrauberlandeplatz (Christoph 10)|49.99985|6.89311\nDE-0617|ADAC Air Rettungswache Ulm (Christoph 22)|48.42654|9.94550\nDE-0618|Friedrichshafen Krankenhaus Hubschrauberlandeplatz|47.67284|9.42856\nDE-0619|Helios Klinik Erlenbach Hubschrauberlandeplatz|49.81028|9.16613\nDE-0620|Kulmbach Krankenhaus Hubschrauberlandeplatz|50.11744|11.46655\nDE-0621|Helios Klinik Meiningen Hubschrauberlandeplatz|50.55709|10.39894\nDE-0622|Sana Krankenhaus Radevormwald Hubschrauberlandeplatz|51.19901|7.35192\nDE-0623|Sana Klinik Duisburg Hubschrauberlandeplatz|51.39605|6.78257\nDE-0624|Sana Klinik Hof Hubschrauberlandeplatz|50.30143|11.91530\nDE-0625|Marien Krankenhaus Siegen Hubschrauberlandeplatz|50.87863|8.02942\nDE-0627|Cham Krankenhaus Hubschrauberlandeplatz|49.22680|12.65910\nDE-0628|Heinrich Braun Krankenhaus Zwickau Hubschrauberlandeplatz|50.72310|12.47106\nDE-0629|Helios Klinik Plauen Hubschrauberlandeplatz|50.48964|12.15362\nDE-0630|Rudolf Virchow Krankenhaus Glauchau Hubschrauberlandeplatz|50.80806|12.55431\nDE-0631|DRK Krankenhaus Lichtenstein Hubschrauberlandeplatz|50.74793|12.63540\nDE-0632|Sachsenring Hubschrauberlandeplatz|50.79212|12.68528\nDE-0633|Erzgebirge Krankenhaus Hubschrauberlandeplatz|50.59373|13.00303\nDE-0634|Kamenz Hubschrauberlandeplatz|51.26410|14.12522\nDE-0635|Chemnitz-Glösa Industrial Park Hubschrauberlandeplatz|50.87181|12.90440\nDE-0636|Helios Klinik Schwelm Hubschrauberlandeplatz|51.29488|7.31265\nDE-0637|Alfried Krupp Krankenhaus Hubschrauberlandeplatz|51.42772|7.00968\nDE-0639|DEKRA Lausitzring Hubschrauberlandeplatz|51.53192|13.92451\nDE-0640|Bad Neustadt an der Saale Krankenhaus Hubschrauberlandeplatz|50.32265|10.23508\nDE-0641|Siloah Sankt Trudpert Krankenhaus Hubschrauberlandeplatz|48.89790|8.67334\nDE-0642|Groß-Gerau Kreis-Krankenhaus Hubschrauberlandeplatz|49.91367|8.47589\nDE-0643|Frankfurt Höchst Krankenhaus Hubschrauberlandeplatz|50.10987|8.54714\nDE-0644|Dritter Orden Uniklinik Hubschrauberlandeplatz|48.16789|11.50389\nDE-0645|SRH Wald-Klinikum Gera Krankenhaus Hubschrauberlandeplatz|50.86702|12.05101\nDE-0646|Pleißental Krankenhaus Hubschrauberlandeplatz|50.74247|12.36517\nDE-0647|Tirschenreuth Krankenhaus Hubschrauberlandeplatz|49.88563|12.33422\nDE-0648|Weiden Krankenhaus Hubschrauberlandeplatz|49.68186|12.16637\nDE-0649|DRF Air Rettungswache Villingen-Schwenningen Hubschrauberlandeplatz (Christoph 11)|48.06631|8.50103\nDE-0650|Rottweil-Rottenmünster Krankenhaus Hubschrauberlandeplatz|48.14948|8.63563\nDE-0651|Görlitz Krankenhaus Hubschrauberlandeplatz|51.16119|14.96984\nDE-0652|Bautzen-Bischofswerda Krankenhaus Hubschrauberlandeplatz|51.13689|14.17750\nDE-0653|Sankt Josefs Krankenhaus Hubschrauberlandeplatz|48.46829|7.96692\nDE-0654|Ortenau Krankenhaus Lahr-Ettenheim Hubschrauberlandeplatz|48.34407|7.88709\nDE-0655|Ortenau Krankenhaus Achern Hubschrauberlandeplatz|48.62530|8.06402\nDE-0656|Oberlausitzer Bergland Krankenhaus Hubschrauberlandeplatz|51.00531|14.56841\nDE-0657|Freiburg Uniklinik Hubschrauberlandeplatz 1|48.00746|7.83927\nDE-0658|Freiburg Uniklinik Hubschrauberlandeplatz 2|48.00677|7.84024\nDE-0659|Sankt Marien Krankenhaus Ahaus|52.06904|6.99206\nDE-0660|Sankt Antonius Krankenhaus Hubschrauberlandeplatz|51.77196|6.13523\nDE-0661|Marienhaus Krankenhaus Sankt Wendel-Ottweiler Hubschrauberlandeplatz|49.45445|7.17959\nDE-0662|Westpfalz Krankenhaus Kaiserslautern Hubschrauberlandeplatz|49.44169|7.75684\nDE-0663|Altötting-Burghausen Kreis-Krankenhaus Burghausen Hubschrauberlandeplatz|48.16720|12.84225\nDE-0664|DonauIsar Krankenhaus Dingolfing Hubschrauberlandeplatz|48.62124|12.48935\nDE-0665|Eggenfelden Kreis-Krankenhaus Hubschrauberlandeplatz|48.41225|12.76021\nDE-0666|Pfarrkirchen Kreis-Krankenhaus Hubschrauberlandeplatz|48.43755|12.92685\nDE-0667|Altötting-Burghausen Kreis-Krankenhaus Altötting Hubschrauberlandeplatz|48.23503|12.67676\nDE-0668|Mühldorf am Inn Krankenhaus Hubschrauberlandeplatz|48.24375|12.53212\nDE-0669|Evangelisches Diakonie Krankenhaus Schwäbisch Hall Hubschrauberlandeplatz|49.12535|9.74176\nDE-0670|Schrobenhausen Kreis-Krankenhaus Hubschrauberlandeplatz|48.57220|11.26313\nDE-0671|Wertach Krankenhaus Bobingen Hubschrauberlandeplatz|48.28158|10.82354\nDE-0672|Augsburg Uniklinik Hubschrauberlandeplatz|48.38452|10.83784\nDE-0673|Sankt Elisabeth Krankenhaus Straubing Hubschrauberlandeplatz|48.88594|12.59089\nDE-0674|Leonberg Krankenhaus Hubschrauberlandeplatz|48.80325|8.99910\nDE-0675|Sankt Vincentius Krankenhaus Hubschrauberlandeplatz|49.00043|8.37832\nDE-0676|Südharz Krankenhaus Nordhausen Hubschrauberlandeplatz|51.51658|10.79483\nDE-0678|Harz Krankenhaus Quedlinburg Hubschrauberlandeplatz|51.79320|11.16262\nDE-0679|Martha-Maria Nürnberg Krankenhaus Hubschrauberlandeplatz|49.48284|11.13680\nDE-0680|Nürnberg South Krankenhaus Hubschrauberlandeplatz|49.41419|11.14770\nDE-0681|Nürnberg North Krankenhaus Hubschrauberlandeplatz|49.46318|11.06251\nDE-0682|Fürth Krankenhaus Hubschrauberlandeplatz|49.48428|10.97486\nDE-0683|Burgholzhausen Rotorflug Hubschrauberlandeplatz|50.26433|8.66629\nDE-0684|Rummelsberg Krankenhaus Hubschrauberlandeplatz|49.36154|11.26490\nDE-0685|Bietigheim Krankenhaus Hubschrauberlandeplatz|48.95662|9.13568\nDE-0687|Bamberg Krankenhaus Hubschrauberlandeplatz|49.86760|10.88804\nDE-0688|Erlangen Uniklinik Rooftop Hubschrauberlandeplatz|49.60007|11.01116\nDE-0689|Forchheim Franconian Switzerland Krankenhaus Hubschrauberlandeplatz|49.71353|11.07780\nDE-0690|Würzburg Uniklinik Rooftop Hubschrauberlandeplatz|49.80693|9.95787\nDE-0691|Kitzingen Krankenhaus Hubschrauberlandeplatz|49.74428|10.13679\nDE-0692|Ochsenfurt Krankenhaus Hubschrauberlandeplatz|49.65971|10.08672\nDE-0693|ADAC Air Rettungswache Dinkelsbühl Hubschrauberlandeplatz|49.06596|10.40159\nDE-0694|Leopoldina Schweinfurt City Krankenhaus Hubschrauberlandeplatz|50.05120|10.24420\nDE-0695|Europakanal Krankenhaus Hubschrauberlandeplatz|49.60593|10.96851\nDE-0696|Main-Kinzig Krankenhaus Schlüchtern Hubschrauberlandeplatz|50.35470|9.52871\nDE-0697|Helios Klinik Mansfeld-Südharz Hubschrauberlandeplatz|51.47178|11.31013\nDE-0698|Westphalia Miners' Krankenhaus Hubschrauberlandeplatz|51.51896|7.53337\nDE-0699|Christian Krankenhaus Unna West Hubschrauberlandeplatz|51.53263|7.67634\nDE-0700|Christian Krankenhaus Unna Central Hubschrauberlandeplatz|51.53704|7.68307\nDE-0701|Kamen Krankenhaus Hubschrauberlandeplatz|51.59550|7.66145\nDE-0702|Dortmund Krankenhaus North Hubschrauberlandeplatz|51.53326|7.45482\nDE-0703|Sankt Christophorus Krankenhaus Hubschrauberlandeplatz|51.67545|7.61918\nDE-0704|Sankt Marien Krankenhaus Hubschrauberlandeplatz|51.61878|7.51548\nDE-0705|Brambauer Krankenhaus Hubschrauberlandeplatz|51.59027|7.44300\nDE-0706|Bochum University Miners' Krankenhaus Hubschrauberlandeplatz|51.46541|7.32754\nDE-0707|Fichtelgebirge Krankenhaus Marktredwitz Hubschrauberlandeplatz|50.00113|12.06510\nDE-0709|Magdeburg Krankenhaus Hubschrauberlandeplatz|52.15862|11.58415\nDE-0710|Magdeburg Uniklinik Hubschrauberlandeplatz|52.10217|11.61981\nDE-0711|Herz-Jesu Krankenhaus Fulda Hubschrauberlandeplatz|50.56407|9.67235\nDE-0712|Fulda Krankenhaus Hubschrauberlandeplatz|50.54544|9.70510\nDE-0713|Helios Sankt Elisabeth Krankenhaus Hünfeld Hubschrauberlandeplatz|50.66238|9.76774\nDE-0714|Helios Klinik Erfurt Hubschrauberlandeplatz|50.99436|11.01206\nDE-0715|Kath. Krankenhaus Sankt Johann Nepomuk Erfurt Hubschrauberlandeplatz|50.94620|11.08564\nDE-0716|Weilburg Kreis-Krankenhaus Hubschrauberlandeplatz|50.48627|8.25322\nDE-0717|Helios Klinik Gotha Hubschrauberlandeplatz|50.92894|10.67889\nDE-0718|Sophien and Hufeland Krankenhaus Weimar Hubschrauberlandeplatz|50.96078|11.32206\nDE-0719|Lahn-Dill Klinik Wetzlar Hubschrauberlandeplatz|50.53711|8.52886\nDE-0720|Elisabeth Krankenhaus Schmalkalden Hubschrauberlandeplatz|50.72182|10.44020\nDE-0721|Hufeland Krankenhaus Hubschrauberlandeplatz|51.20038|10.46874\nDE-0722|KMG Krankenhaus Sondershausen Hubschrauberlandeplatz|51.37388|10.87975\nDE-0723|Schwandorf Kreis-Krankenhaus Oberviechtach Hubschrauberlandeplatz|49.46085|12.41117\nDE-0725|Sankt Marien Krankenhaus Amberg Hubschrauberlandeplatz|49.44836|11.86676\nDE-0726|Herdecke Community Krankenhaus Hubschrauberlandeplatz|51.41030|7.39948\nDE-0727|Sankt-Johannes Kath. Krankenhaus Hagen Hubschrauberlandeplatz|51.39629|7.47345\nDE-0728|Sankt Marien Krankenhaus Vreden Hubschrauberlandeplatz|52.04663|6.81387\nDE-0729|DonauIsar Krankenhaus Landau Hubschrauberlandeplatz|48.66640|12.70314\nDE-0730|Marburg Uniklinik Ground Hubschrauberlandeplatz|50.81390|8.80440\nDE-0731|Marburg Uniklinik Rooftop Hubschrauberlandeplatz|50.81474|8.80778\nDE-0732|Dillingen Kreis-Krankenhaus Hubschrauberlandeplatz|48.57447|10.48094\nDE-0733|Sankt Joseph Krankenhaus Prüm Hubschrauberlandeplatz|50.21311|6.41131\nDE-0734|AMEOS Krankenhaus Sankt Salvator Halberstadt Hubschrauberlandeplatz|51.90157|11.05214\nDE-0735|AMEOS Krankenhaus Bernburg Hubschrauberlandeplatz|51.78164|11.73507\nDE-0736|Harz Krankenhaus Wernigerode Hubschrauberlandeplatz|51.83587|10.77361\nDE-0737|Schotten Hubschrauberlandeplatz|50.50725|9.10638\nDE-0738|Carl Thien Krankenhaus Cottbus Hubschrauberlandeplatz|51.74339|14.32204\nDE-0739|Regiomed Krankenhaus Neuhaus am Rennweg Hubschrauberlandeplatz|50.51063|11.12983\nDE-0740|Untererthal Hubschrauberlandeplatz|50.15736|9.87626\nDE-0741|Crailsheim Krankenhaus Hubschrauberlandeplatz|49.13545|10.07877\nDE-0742|Red Cross Krankenhaus Wertheim Hubschrauberlandeplatz|49.76132|9.49866\nDE-0743|Vogelsberg Kreis-Krankenhaus Alsfeld Hubschrauberlandeplatz|50.76362|9.26963\nDE-0744|Bad Hersfeld Krankenhaus Hubschrauberlandeplatz|50.87705|9.71497\nDE-0745|Werra-Meißner Eschwege Krankenhaus Hubschrauberlandeplatz|51.18225|10.04925\nDE-0746|Jerichower Land Kreis-Krankenhaus Hubschrauberlandeplatz|52.26335|11.83110\nDE-0747|Elbe-Elster Krankenhaus Finsterwalde Hubschrauberlandeplatz|51.63642|13.69832\nDE-0748|Rotenburg an der Fulda Cardiovascular Center Hubschrauberlandeplatz|51.00444|9.73847\nDE-0749|Bad Salzungen Krankenhaus Hubschrauberlandeplatz|50.80420|10.21672\nDE-0750|Sankt Georg Krankenhaus Eisenach Hubschrauberlandeplatz|50.98755|10.31667\nDE-0751|Sankt Georg Krankenhaus Leipzig Reserve Hubschrauberlandeplatz|51.38289|12.37341\nDE-0752|Sankt George Krankenhaus Leipzig Hubschrauberlandeplatz|51.38325|12.37594\nDE-0753|Leipzig Uniklinik Hubschrauberlandeplatz 1|51.33016|12.38692\nDE-0754|Evangelical Deaconess Krankenhaus Leipzig Hubschrauberlandeplatz|51.34239|12.31806\nDE-0755|Leipzig Heart Center Ground Hubschrauberlandeplatz|51.30605|12.43675\nDE-0756|Leipzig Heart Center Rooftop Hubschrauberlandeplatz|51.30757|12.43564\nDE-0757|Sankt Elisabeth Krankenhaus Leipzig Hubschrauberlandeplatz|51.30341|12.37863\nDE-0758|Helios Klinik Schkeuditz Hubschrauberlandeplatz|51.39533|12.22929\nDE-0759|Regiomed Krankenhaus Lichtenfels Hubschrauberlandeplatz|50.13885|11.08157\nDE-0760|Leipzig Uniklinik Hubschrauberlandeplatz 2|51.32916|12.38882\nDE-0761|Martha-Maria Halle-Dölau Krankenhaus Hubschrauberlandeplatz|51.50387|11.88010\nDE-0762|BG Krankenhaus Bergmannstrost Halle Hubschrauberlandeplatz|51.46054|11.98771\nDE-0763|Sankt Elisabeth and Sankt Barbara Krankenhaus Halle (Saale) Hubschrauberlandeplatz|51.47723|11.96897\nDE-0764|Halle (Saale) Uniklinik Hubschrauberlandeplatz|51.50082|11.93521\nDE-0765|Asklepios Klinik Weißenfels Hubschrauberlandeplatz|51.19365|11.95542\nDE-0766|AMEOS Krankenhaus Aschersleben Hubschrauberlandeplatz|51.74479|11.45676\nDE-0768|KMG Krankenhaus Luckenwalde Hubschrauberlandeplatz|52.09918|13.18359\nDE-0769|Bitterfield-Wolfen Health Center Hubschrauberlandeplatz|51.63308|12.33311\nDE-0770|Hohe Warte Krankenhaus Hubschrauberlandeplatz|49.96745|11.58573\nDE-0771|Bayreuth Krankenhaus Hubschrauberlandeplatz|49.94438|11.53550\nDE-0772|Nordoberpfalz Krankenhaus Kemnath Hubschrauberlandeplatz|49.86856|11.89058\nDE-0773|Dessau Municipal Krankenhaus Rooftop Hubschrauberlandeplatz|51.81586|12.19702\nDE-0774|Dessau Municipal Krankenhaus Ground Hubschrauberlandeplatz|51.81604|12.19411\nDE-0775|Helios Krankenhaus Hettstedt Hubschrauberlandeplatz|51.66112|11.49327\nDE-0776|Elbe-Elster Krankenhaus Elsterwerda Hubschrauberlandeplatz|51.45563|13.52217\nDE-0777|Torgau Kreis-Krankenhaus Hubschrauberlandeplatz|51.56142|12.98388\nDE-0778|Elbe-Elster Krankenhaus Herzberg Hubschrauberlandeplatz|51.68606|13.20694\nDE-0779|Johanniter Krankenhaus Treuenbrietzen Hubschrauberlandeplatz|52.08331|12.87572\nDE-0780|Hephata Krankenhaus Hubschrauberlandeplatz|50.90977|9.16633\nDE-0781|Sankt Franziskus Krankenhaus Winterberg Hubschrauberlandeplatz|51.19336|8.53671\nDE-0782|Grafschaft Abbey Specialty Krankenhaus Hubschrauberlandeplatz|51.14439|8.32323\nDE-0783|Bad Berleburg Luftrettung Hubschrauberlandeplatz|51.06198|8.40527\nDE-0784|Flemmingstraße Krankenhaus Ground Hubschrauberlandeplatz|50.84617|12.88346\nDE-0785|Flemmingstraße Krankenhaus Rooftop Hubschrauberlandeplatz|50.84572|12.88526\nDE-0786|Helios Klinik Aue Hubschrauberlandeplatz|50.59296|12.71597\nDE-0787|Bergstadt Schneeberg Polyclinic Hubschrauberlandeplatz|50.60464|12.64301\nDE-0788|Heinrich Braun Krankenhaus Kirchberg Hubschrauberlandeplatz|50.62645|12.56295\nDE-0789|ADAC Air Rettungswache Zwickau Hubschrauberlandeplatz|50.71988|12.43055\nDE-0790|Erlabrunn Krankenhaus Hubschrauberlandeplatz|50.47181|12.70706\nDE-0791|Paracelsus Krankenhaus Schöneck Hubschrauberlandeplatz|50.39778|12.33596\nDE-0792|Paracelsus Krankenhaus Adorf Hubschrauberlandeplatz|50.32971|12.29068\nDE-0793|Fichtelgebirge Krankenhaus Selb Hubschrauberlandeplatz|50.16957|12.11761\nDE-0794|Paul-Gerhardt-Stift Evangelical Krankenhaus Hubschrauberlandeplatz|51.87057|12.65704\nDE-0795|MediClin Heart Center Coswig Hubschrauberlandeplatz|51.87831|12.47623\nDE-0796|Ernst von Bergmann Krankenhaus Bad Belzig Hubschrauberlandeplatz|52.14079|12.59828\nDE-0797|AMEOS Krankenhaus Staßfurt Hubschrauberlandeplatz|51.85366|11.59181\nDE-0798|Lausitzer Seenland Krankenhaus Hubschrauberlandeplatz|51.44227|14.27334\nDE-0803|DRK Altenkirchen Krankenhaus Hubschrauberlandeplatz|50.68258|7.62942\nDE-0808|Göttingen University Klinik Hubschrauberlandeplatz|51.55170|9.94329\nDE-0811|Hegau-Jugendwerk Neurological Krankenhaus Hubschrauberlandeplatz|47.69144|8.76817\nDE-0812|Christophsbad Hubschrauberlandeplatz|48.70053|9.64216\nDE-0813|Sulingen Auxiliary Helicopter Base|52.65147|8.88642\nDE-0815|Autobahnpolizei Wiesbaden Hubschrauberlandeplatz|50.09831|8.34958\nDE-0816|Idstein Freiwillige Feuerwehr Hubschrauberlandeplatz|50.21697|8.25538\nDE-0817|Kath. Krankenhaus Koblenz-Montabaur Hubschrauberlandeplatz|50.43125|7.83303\nDE-0818|Autobahnpolizei Heiligenroth Hubschrauberlandeplatz|50.44692|7.84539\nDE-0819|Kalkberg Hubschrauberlandeplatz|50.94758|6.99728\nDE-0823|Ransbach-Baumbach Freiwillige Feuerwehr Hubschrauberlandeplatz|50.46293|7.73081\nDE-0824|DRK Kamillus Krankenhaus Asbach Hubschrauberlandeplatz|50.66839|7.42471\nDE-0825|Neurath Power Station Hubschrauberlandeplatz|51.03358|6.61428\nDE-0826|Garzweiler Opencast Mine Hubschrauberlandeplatz|51.06548|6.54156\nDE-0827|Ayrshire Barracks South Hubschrauberlandeplatz|51.16992|6.39323\nDE-0828|Grimma Krankenhaus Hubschrauberlandeplatz|51.23730|12.71939\nDE-0829|Autobahnpolizei South Hesse Hubschrauberlandeplatz|49.86663|8.62122\nDE-0830|Dobeln Krankenhaus Hubschrauberlandeplatz|51.11705|13.12922\nDE-0832|Carl von Basedow Saalekreis Krankenhaus Querfurt Hubschrauberlandeplatz|51.37063|11.60606\nDE-0833|Naumburg Krankenhaus Hubschrauberlandeplatz|51.16025|11.81553\nDE-0834|Leipzig Polizei Academy Hubschrauberlandeplatz|51.37804|12.38731\nDE-0835|Ingolstadt Municipal Hubschrauberlandeplatz|48.77016|11.41878\nDE-0836|Rheinhessen Specialty Krankenhaus Alzey Hubschrauberlandeplatz|49.74042|8.12800\nDE-0837|Helios Krankenhaus Lutherstadt Eisleben Hubschrauberlandeplatz|51.53362|11.54092\nDE-0838|Sankt Marienworth Krankenhaus Hubschrauberlandeplatz|49.84692|7.86182\nDE-0839|Robert Koch Krankenhaus Hubschrauberlandeplatz|51.01379|11.51299\nDE-0840|Fossiliengrube Hubschrauberlandeplatz|49.90611|11.46417\nDE-0841|Bayreuth Psychiatric Krankenhaus Hubschrauberlandeplatz|49.95663|11.56823\nDE-0842|Zeitz Krankenhaus Hubschrauberlandeplatz|51.03202|12.16044\nDE-0843|Sana Krankenhaus Borna Hubschrauberlandeplatz|51.13545|12.50650\nDE-0844|Autobahnpolizei Ruchheim Hubschrauberlandeplatz|49.47969|8.32379\nDE-0846|Helios Klinik Munich West Hubschrauberlandeplatz|48.14521|11.45391\nDE-0847|Deutschherrnwiese Hubschrauberlandeplatz|49.45495|11.05905\nDE-0848|München North Railyard Northeast Hubschrauberlandeplatz|48.19426|11.50915\nDE-0849|München North Railyard West Hubschrauberlandeplatz 2|48.20183|11.47368\nDE-0850|München North Railyard West Hubschrauberlandeplatz 1|48.19838|11.48255\nDE-0851|Ostallgäu-Kaufbeuren Krankenhaus Hubschrauberlandeplatz|47.88773|10.60982\nDE-0852|Starnberg Hubschrauber-Notlandeplatz|48.01887|11.32643\nDE-0853|Hanau Feuerwehr Hubschrauber-Notlandeplatz|50.14534|8.94291\nDE-0854|Dippenricht Hubschrauberlandeplatz|49.25398|11.36318\nDE-0856|Bavaria Krankenhaus Kreischa Hubschrauberlandeplatz|50.94682|13.76707\nDE-0857|Helios Klinik Freital Hubschrauberlandeplatz|50.99179|13.64548\nDE-0858|Ilm Kreis-Krankenhaus Arnstadt-Ilmenau Hubschrauberlandeplatz|50.83944|10.94069\nDE-0859|DRF Rettungswache Bad Berka Hubschrauberlandeplatz|50.88991|11.26387\nDE-0860|Bad Berka Central Krankenhaus Hubschrauberlandeplatz|50.88993|11.26623\nDE-0861|Weilheim Rescue Hubschrauberlandeplatz|47.82553|11.14297\nDE-0862|Lauterbacher Mühle am Ostersee Private Krankenhaus Hubschrauberlandeplatz|47.79255|11.29150\nDE-0863|Feldafing Hubschrauberlandeplatz|47.93597|11.29251\nDE-0864|Aschaffenburg Krankenhaus Hubschrauberlandeplatz|49.97670|9.18859\nDE-0865|Neustadt Krankenhaus Hubschrauberlandeplatz|49.58199|10.62407\nDE-0866|Schützenplatz Hubschrauber-Notlandeplatz|50.98716|12.98901\nDE-0867|Brandweg Hubschrauberlandeplatz|47.46448|11.02754\nDE-0868|Main-Spessart Krankenhaus Marktheidenfeld Campus Hubschrauberlandeplatz|49.84949|9.62573\nDE-0869|Camp Albertshof Army Hubschrauberlandeplatz|49.23691|11.82518\nDE-0870|Höllentalangerhütte Mountain Rescue Hubschrauberlandeplatz|47.43736|11.02358\nDE-0871|Erding Kreis-Krankenhaus Hubschrauberlandeplatz|48.29414|11.89560\nDE-0872|Haar Krankenhaus Hubschrauberlandeplatz|48.11777|11.74781\nDE-0873|Eisenberg Forest Rudolf Elle Krankenhaus Hubschrauberlandeplatz|50.95682|11.88139\nDE-0911|Christoph Hansa ADAC Hubschrauberlandeplatz|53.50797|10.17338\nDE-0912|Bocholt Hubschrauberlandeplatz|51.82111|6.61704\nDE-0913|Marien Krankenhaus Papenberg Aschendorf Hubschrauberlandeplatz|53.07414|7.39295\nDE-0914|Borkum Island Krankenhaus Hubschrauberlandeplatz|53.58492|6.67023\nDE-0915|Borkum Riffgrund II Helideck|53.95200|6.48800\nDE-0919|Kesselsdorf Hubschrauberlandeplatz|51.03302|13.58153\nDE-0922|Frankfurt am Main Accident Krankenhaus Roof Hubschrauberlandeplatz|50.14547|8.70898\nDE-0925|Schwedt Refinery Hubschrauberlandeplatz|53.09184|14.22522\nDE-0926|Helwin A|54.45190|7.74004\nDE-0927|Meerwind S O|54.39917|7.70005\nDE-0928|Amrumbank West|54.51981|7.73545\nDE-0929|Pfronstetten-Aichelau Hubschrauberlandeplatz|48.28844|9.39446\nDE-0930|Nürtingen Medius Klinik Hubschrauberlandeplatz|48.63036|9.35637\nDE-0932|Ludwigsburg Krankenhaus Helistop|48.89957|9.20408\nDE-0933|Ostfildern-Ruit Medius Krankenhaus Helistop|48.74179|9.25447\nDE-0934|Bavaria Zscheckwitz Rehabilitation Krankenhaus Hubschrauberlandeplatz|50.95325|13.75225\nEDGL|Ludwigshafen Accident Krankenhaus Hubschrauberlandeplatz|49.48651|8.38924\nEDGO|Oedheim Hubschrauberlandeplatz|49.24147|9.23436\nEDHX|Bad Bramstedt Hubschrauberlandeplatz|53.94280|9.90550\nEDMR|Ottobrunn Hubschrauberlandeplatz|48.04813|11.65367\nEDMX|Oberschleissheim Hubschrauberlandeplatz|48.23795|11.56542\nEDOY|Ahrensfelde Hubschrauberlandeplatz|52.60100|13.57600\nEDPR|Donauwörth Hubschrauberlandeplatz|48.70748|10.76929\nEDVX|Gifhorn Federal Polizei Hubschrauberlandeplatz|52.49518|10.51029\nEDVZ|Fuldatal Federal Polizei Hubschrauberlandeplatz|51.35958|9.50280\nET31|Sheridan Barracks Landing Area|47.48278|11.06694\nET39|Hohenfels Krankenhaus Army Hubschrauberlandeplatz|49.22408|11.83481\nETEB|Ansbach Katterbach Army Hubschrauberlandeplatz|49.30820|10.63870\nETED|Kaiserslautern Depot Army Hubschrauberlandeplatz|49.44787|7.83957\nETHH|Bonn-Hardthöhe Air Base|50.69650|7.04511\nETIK|Illesheim Army Hubschrauberlandeplatz|49.47390|10.38810\nETIP|Landstuhl Army Hubschrauberlandeplatz|49.40306|7.53611\nETIY|Landstuhl Regional Klinikum Hubschrauberlandeplatz|49.40248|7.55859\nETJB|Schonewald CRC Hubschrauberlandeplatz|53.50995|13.43567\nETOA|Schweinfurt Army Hubschrauberlandeplatz|50.04975|10.16790";
    private void airfieldSync(double n, double s, double e, double w, boolean force) {
        try {
            boolean fresh = System.currentTimeMillis() - prefs.getLong("af_fetched", 0) < DAY;
            boolean covers = false;
            try {
                JSONObject b = new JSONObject(prefs.getString("af_bbox", "{}"));
                covers = b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
            } catch (Exception ignored) { }
            JSONArray els;
            if (pbfCovers(n, s, e, w) && afFile().isFile()) {   // aus der Geofabrik-Datei – kein Overpass nötig
                els = new JSONArray(readFile(afFile()));
                if (!"load".equals(catStatus.get("flugplaetze"))) catSt("flugplaetze", "ok");
            } else {
                return;   // ohne Geofabrik-Datei: bisheriger Stand bleibt – keine Overpass-Abfrage mehr
            }
            if (els == null) {
                catSt("flugplaetze", "load");
                String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
                String txt = overpass("[out:json][timeout:60];(nwr[\"aeroway\"~\"^(aerodrome|heliport|airstrip|helipad)$\"](" + bb + "););out tags center;", 90000);
                els = new JSONObject(txt).getJSONArray("elements");
                writeFileAtomic(afFile(), els.toString());
                catSt("flugplaetze", "ok");
                prefs.edit().putLong("af_fetched", System.currentTimeMillis())
                        .putString("af_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            }

            // Frequenzen höchstens wöchentlich aus OurAirports holen, nur für die gefundenen ICAO-Codes
            Set<String> icaos = new java.util.HashSet<>();
            for (int i = 0; i < els.length(); i++) {
                JSONObject t = els.getJSONObject(i).optJSONObject("tags");
                if (t != null && !t.optString("icao").isEmpty()) icaos.add(t.optString("icao").toUpperCase(Locale.ROOT));
            }
            boolean freqFresh = System.currentTimeMillis() - prefs.getLong("af_freq_fetched", 0) < 7 * DAY;
            JSONObject have = afFreqFile().isFile() ? new JSONObject(readFile(afFreqFile())) : new JSONObject();
            boolean missing = false;
            for (String ic : icaos) if (!have.has(ic)) missing = true;
            if (icaos.isEmpty() || (freqFresh && !missing && !force)) return;
            HttpURLConnection c = (HttpURLConnection) new URL("https://davidmegginson.github.io/ourairports-data/airport-frequencies.csv").openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) return;
            JSONObject freqs = new JSONObject();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();   // Kopfzeile: id,airport_ref,airport_ident,type,description,frequency_mhz
                while ((line = br.readLine()) != null) {
                    if (!line.contains("\"ED") && !line.contains(",ED") && !line.contains("\"ET") && !line.contains(",ET")) continue;
                    List<String> col = csvLine(line);
                    if (col.size() < 6) continue;
                    String ident = col.get(2).toUpperCase(Locale.ROOT);
                    if (!icaos.contains(ident)) continue;
                    if (!freqs.has(ident)) freqs.put(ident, new JSONArray());
                    freqs.getJSONArray(ident).put(new JSONObject().put("type", col.get(3)).put("desc", col.get(4)).put("mhz", col.get(5)));
                }
            } finally { c.disconnect(); }
            Iterator<String> it = have.keys();
            while (it.hasNext()) { String k = it.next(); if (!freqs.has(k)) freqs.put(k, have.get(k)); }
            writeFileAtomic(afFreqFile(), freqs.toString());
            prefs.edit().putLong("af_freq_fetched", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    // ---------- dipul Wetter ----------
    private static final int[] WX_HEIGHTS = {10, 50, 100, 150, 200, 250};
    private static final int[] WX_THEIGHTS = {2, 50, 100, 150, 200, 250};

    private void dipulWeatherImpl(String reqId, double lat, double lon) {
        JSONObject out = new JSONObject();
        try {
            if (!online()) throw new Exception("Offline – keine Internetverbindung");
            JSONArray times = new JSONArray();
            java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 0; i < 24; i++) { times.put(iso.format(c.getTime())); c.add(java.util.Calendar.HOUR_OF_DAY, 1); }
            String txt = null;
            for (int attempt = 0; attempt < 2 && txt == null; attempt++) {
                JSONArray wh = new JSONArray();
                for (int h : (attempt == 0 ? WX_HEIGHTS : new int[]{10, 50, 100, 150})) wh.put(new JSONObject().put("reference", "AGL").put("unit", "m").put("value", h));
                JSONArray th = new JSONArray();
                for (int h : (attempt == 0 ? WX_THEIGHTS : new int[]{2})) th.put(new JSONObject().put("reference", "AGL").put("unit", "m").put("value", h));
                JSONObject pos = new JSONObject().put("latitude", lat).put("longitude", lon).put("forecasts", times)
                        .put("qnh", new JSONObject().put("unit", "Pa"))
                        .put("temperature", new JSONObject().put("unit", "C").put("heights", th))
                        .put("wind", new JSONObject().put("unit", "m/s").put("heights", wh))
                        .put("rainPrecipitation", new JSONObject().put("unit", "mm"))
                        .put("snowPrecipitation", new JSONObject().put("unit", "cm"))
                        .put("totalCloudCover", new JSONObject().put("unit", "%"))
                        .put("airHumidity", new JSONObject().put("unit", "%").put("heights",
                                new JSONArray().put(new JSONObject().put("reference", "AGL").put("unit", "m").put("value", 50))));
                byte[] body = new JSONObject().put("positions", new JSONArray().put(pos)).toString().getBytes(StandardCharsets.UTF_8);
                HttpURLConnection hc = (HttpURLConnection) new URL("https://utm-service.dfs.de/api/weather/v1/weather").openConnection();
                hc.setRequestMethod("POST");
                hc.setConnectTimeout(15000); hc.setReadTimeout(30000);
                hc.setDoOutput(true);
                hc.setRequestProperty("Content-Type", "application/json");
                hc.setRequestProperty("Accept", "application/json");
                hc.setRequestProperty("User-Agent", "KMZMissionen/1.7 (Kitzrettung, nicht-kommerziell)");
                try (OutputStream o = hc.getOutputStream()) { o.write(body); }
                int code = hc.getResponseCode();
                InputStream in = code >= 400 ? hc.getErrorStream() : hc.getInputStream();
                String body0 = in != null ? readAll(in) : "";
                hc.disconnect();
                if (code >= 400 && attempt == 0) continue;   // zusätzliche Höhen abgelehnt -> bewährte Minimalabfrage
                if (code >= 400) {
                    String msg = "dipul-Wetter antwortet mit " + code;
                    try { msg += ": " + new JSONObject(body0).optString("errorMessage"); } catch (Exception ignored) { }
                    throw new Exception(msg);
                }
                txt = body0;
            }
            out.put("ok", true).put("data", new JSONObject(txt));
        } catch (Exception e) {
            try { out.put("ok", false).put("error", e.getMessage() != null ? e.getMessage() : "keine Verbindung"); } catch (Exception ignored) { }
        }
        js("window.onDipulWx && window.onDipulWx(" + JSONObject.quote(reqId) + "," + JSONObject.quote(out.toString()) + ")");
    }

    // ---------- NOTAM / temporäre Betriebseinschränkungen (dipul WFS, CC BY-ND 4.0) ----------
    private File notamFile() { return dataFile("notams.json"); }

    /** Eine dipul-WFS-Ebene für ganz Deutschland (GeoJSON-Features) */
    private JSONArray notamWfs(String layer) throws Exception {
        String url = "https://uas-betrieb.de/geoservices/dipul/wfs?service=WFS&version=1.1.0&request=GetFeature"
                + "&typeName=dipul:" + layer + "&outputFormat=application/json&srsName=EPSG:4326"
                + "&bbox=5.8,47.2,15.1,55.1,EPSG:4326";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
            if (c.getResponseCode() != 200) return null;
            String txt; try (InputStream in = c.getInputStream()) { txt = readAll(in); }
            if (!txt.trim().startsWith("{")) return null;
            return new JSONObject(txt).optJSONArray("features");
        } finally { c.disconnect(); }
    }

    /** Tieffluggebiete 250 ft (LFA 1–8) der Bundeswehr als KML vom DHV – alle 30 Tage, sonst der gespeicherte Stand */
    private static final String LFA_PAGE = "https://www.dhv.de/flugbetrieb/luftraum-luftrecht/militaerischer-tiefflug/";
    private static final String LFA_KML = "https://www.dhv.de/media/jahre/2026/04_flugbetrieb/LuftraumLuftrecht/MilitaerischerTiefflug/LFAVereinfacht.kml";
    private void lfaSync(boolean force) {
        File f = dataFile("lfa.kml");
        if (!force && f.isFile() && f.length() > 500 && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) return;
        if (!online()) return;
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        try {   // aktuellen Link von der DHV-Seite holen (Pfad enthält das Jahr)
            Matcher m = Pattern.compile("(?i)href=\"([^\"]*LFA[^\"]*\\.kml)\"").matcher(httpText(LFA_PAGE, 30000));
            while (m.find()) { String u = m.group(1).replace("&amp;", "&"); urls.add(u.startsWith("http") ? u : "https://www.dhv.de" + (u.startsWith("/") ? "" : "/") + u); }
        } catch (Exception ignored) { }
        urls.add(LFA_KML);
        for (String u : urls) {
            try {
                String k = httpText(u, 60000);
                if (k.contains("<coordinates") && k.toLowerCase(Locale.ROOT).contains("<kml")) { writeFileAtomic(f, k); return; }
            } catch (Exception ignored) { }
        }
    }

    private void notamSyncImpl(double n, double s, double e, double w, boolean force) {
        try { lfaSync(false); } catch (Exception ignored) { }
        try {
            if (!force && System.currentTimeMillis() - prefs.getLong("notam_fetched", 0) < 3600000L && notamFile().isFile()) return;
            // 1. Wahl: dipul-Dienst „affectedAreas“ (mit Aktivierungszeiten, Höhen, Kreisen); bei Fehler: WFS-Ebenen wie bisher
            try {
                JSONArray areas = dipulAffectedAreas();
                if (areas != null) {
                    JSONObject data = new JSONObject().put("areas", areas).put("src", "api");
                    // dipul-Ebene „temporäre Betriebseinschränkungen“ (ganz Deutschland) dazu – die App ordnet die Flächen den Einträgen zu
                    JSONObject wfs = new JSONObject();
                    for (String layer : new String[]{"temporaere_betriebseinschraenkungen", "inaktive_temporaere_betriebseinschraenkungen"}) {
                        try { JSONArray fs = notamWfs(layer); if (fs != null) wfs.put(layer, fs); } catch (Exception ignored) { }
                    }
                    data.put("wfs", wfs);
                    writeFileAtomic(notamFile(), data.toString());
                    prefs.edit().putLong("notam_fetched", System.currentTimeMillis()).remove("notam_err").apply();
                    return;
                }
            } catch (Exception apiErr) { /* weiter mit WFS */ }
            // Ganz Deutschland – temporäre Einschränkungen sind wenige, die App sortiert nach Entfernung zu den Feldern
            n = 55.1; s = 47.2; e = 15.1; w = 5.8;
            JSONObject data = new JSONObject();
            for (String layer : new String[]{"temporaere_betriebseinschraenkungen", "inaktive_temporaere_betriebseinschraenkungen"}) {
                String url = "https://uas-betrieb.de/geoservices/dipul/wfs?service=WFS&version=1.1.0&request=GetFeature"
                        + "&typeName=dipul:" + layer + "&outputFormat=application/json&srsName=EPSG:4326"
                        + "&bbox=" + String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", w, s, e, n) + ",EPSG:4326";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "KMZMissionen/1.7 (Kitzrettung, nicht-kommerziell)");
                int code = c.getResponseCode();
                if (code != 200) { c.disconnect(); prefs.edit().putString("notam_err", "dipul antwortet mit HTTP " + code).apply(); return; }
                String txt;
                try (InputStream in = c.getInputStream()) { txt = readAll(in); }
                c.disconnect();
                if (!txt.trim().startsWith("{")) {
                    String m = txt.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    prefs.edit().putString("notam_err", "dipul: " + (m.length() > 160 ? m.substring(0, 160) : m)).apply();
                    return;
                }
                JSONObject fc = new JSONObject(txt);
                data.put(layer, fc.optJSONArray("features") != null ? fc.optJSONArray("features") : new JSONArray());
            }
            writeFileAtomic(notamFile(), data.toString());
            prefs.edit().putLong("notam_fetched", System.currentTimeMillis()).remove("notam_err").apply();
        } catch (Exception ex) {
            boolean noNet = ex instanceof java.net.UnknownHostException || ex instanceof java.net.ConnectException || ex instanceof java.net.SocketTimeoutException
                    || String.valueOf(ex.getMessage()).contains("resolve host");
            prefs.edit().putString("notam_err", noNet ? "Keine Internetverbindung beim letzten Versuch – neuer Versuch automatisch, sobald die RC online ist" : "Abruf fehlgeschlagen: " + ex.getMessage()).apply();
        }
    }


    // ---------- Marktstammdatenregister (Bundesnetzagentur): Windenergieanlagen je Bundesland, gefiltert auf das Gebiet ----------
    private void mastrSyncImpl(String json) {
        JSONObject ev = new JSONObject();
        catSt("windraeder", "load");
        try {
            JSONObject p = new JSONObject(json);
            JSONArray st = p.getJSONArray("states"), box = p.getJSONArray("box");
            double w = box.getDouble(0), s = box.getDouble(1), e = box.getDouble(2), n = box.getDouble(3);
            JSONArray out = new JSONArray();
            for (int i = 0; i < st.length(); i++) {
                int page = 1, total = Integer.MAX_VALUE, got = 0;
                while (got < total && page < 30) {
                    String filter = "Energieträger~eq~'2497'~and~Bundesland~eq~'" + st.getInt(i) + "'";
                    String url = "https://www.marktstammdatenregister.de/MaStR/Einheit/EinheitJson/GetErweiterteOeffentlicheEinheitStromerzeugung?sort=&page=" + page
                            + "&pageSize=2000&group=&filter=" + URLEncoder.encode(filter, "UTF-8");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(90000);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
                    if (c.getResponseCode() != 200) { c.disconnect(); throw new Exception("Marktstammdatenregister antwortet mit HTTP " + c.getResponseCode()); }
                    String txt; try (InputStream in = c.getInputStream()) { txt = readAll(in); }
                    c.disconnect();
                    JSONObject j = new JSONObject(txt);
                    total = j.optInt("Total", 0);
                    JSONArray d = j.optJSONArray("Data");
                    if (d == null || d.length() == 0) break;
                    got += d.length(); page++;
                    for (int k = 0; k < d.length(); k++) {
                        JSONObject r = d.getJSONObject(k);
                        double la = r.optDouble("Breitengrad", Double.NaN), lo = r.optDouble("Laengengrad", Double.NaN);
                        if (Double.isNaN(la) || Double.isNaN(lo) || la < s || la > n || lo < w || lo > e) continue;
                        String status = r.optString("BetriebsStatusName", "");
                        boolean act = status.startsWith("In Betrieb");
                        if (!act && !status.toLowerCase(Locale.ROOT).contains("vorübergehend")) continue;   // geplante/abgebaute weglassen
                        double hub = r.optDouble("NabenhoeheWindenergieanlage", 0), rot = r.optDouble("RotordurchmesserWindenergieanlage", 0);
                        // natürliche Personen nicht namentlich speichern
                        String op = r.optInt("AnlagenbetreiberPersonenArt", 0) == 518 ? "Privatperson" : r.optString("AnlagenbetreiberName", r.optString("AnlagenbetreiberMaskedName", ""));
                        String name = (r.optString("WindparkName", "").isEmpty() || r.isNull("WindparkName") ? "" : r.optString("WindparkName") + " · ") + r.optString("EinheitName", "");
                        out.put(new JSONArray().put(la).put(lo).put(hub > 0 ? hub : JSONObject.NULL).put(rot > 0 ? rot : JSONObject.NULL)
                                .put(hub > 0 ? Math.round((hub + rot / 2) * 10) / 10.0 : JSONObject.NULL).put(name).put(op)
                                .put(r.isNull("Ort") ? "" : r.optString("Ort", "")).put(r.optString("MaStRNummer", "")).put(act ? 1 : 0)
                                .put(r.optLong("Id", 0)).put(r.optDouble("Nettonennleistung", 0)));
                    }
                }
            }
            writeFileAtomic(dataFile("mastr_wind.json"), new JSONObject().put("t", System.currentTimeMillis()).put("list", out).toString());
            ev.put("ok", true).put("n", out.length());
            catSt("windraeder", "ok");
        } catch (Exception ex) {
            try { ev.put("ok", false).put("error", ex.getMessage()); } catch (Exception ignored) { }
            catSt("windraeder", errTxt(ex));
        }
        js("window.onMastr && window.onMastr(" + JSONObject.quote(ev.toString()) + ")");
    }

    // ---------- dipul: temporäre Beschränkungsgebiete über die offizielle Schnittstelle ----------
    private String dipulToken = null; private long dipulTokenExp = 0;
    private String dipulTokenGet(boolean renew) throws Exception {
        if (!renew && dipulToken != null && System.currentTimeMillis() < dipulTokenExp - 60000) return dipulToken;
        Exception last = null;
        for (String realm : new String[]{"bmv", "bmdv"}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://uas-betrieb.dfs.de/api/token/v1/anonymous/" + realm + "/token").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                String body; try (InputStream in = c.getInputStream()) { body = readAll(in); }
                c.disconnect();
                JSONObject j = new JSONObject(body);
                String tok = j.optString("token", j.optString("access_token", ""));
                if (tok.isEmpty()) continue;
                long exp = System.currentTimeMillis() + 10 * 60000;
                Object ex = j.opt("expiration");
                if (ex instanceof Number) { long v = ((Number) ex).longValue(); exp = v > 1e12 ? v : v > 1e9 ? v * 1000 : System.currentTimeMillis() + v * 1000; }
                else if (ex instanceof String) try { java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); exp = f.parse(((String) ex).substring(0, 19)).getTime(); } catch (Exception ignored) { }
                dipulToken = tok; dipulTokenExp = exp;
                return tok;
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new Exception("kein dipul-Token");
    }

    /** Alle temporären UAS-Beschränkungsgebiete (U_NFZ) in Deutschland: Kreis um die Mitte Deutschlands, 120 m AGL */
    private JSONArray dipulAffectedAreas() throws Exception {
        JSONObject q = new JSONObject().put("type", "Feature")
                .put("properties", new JSONObject().put("subType", "Circle").put("radius", 560000)
                        .put("altitude", new JSONObject().put("value", 120).put("altitudeReference", "AGL").put("unit", "m")))
                .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(10.4515).put(51.1657)));
        byte[] body = q.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            String tok = dipulTokenGet(attempt > 0);
            HttpURLConnection c = (HttpURLConnection) new URL("https://dipul-service.dfs.de/api/geoapi/dipul/v4/affectedAreas?typeCode=U_NFZ&locale=de").openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + tok);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "MissionKitzrettung/2.1 (Android; Kitzrettung, nicht-kommerziell)");
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            if (code == 401 && attempt == 0) { c.disconnect(); continue; }   // Token abgelaufen: einmal erneuern
            if (code != 200) { c.disconnect(); throw new Exception("dipul-Dienst antwortet mit HTTP " + code); }
            String txt; try (InputStream in = c.getInputStream()) { txt = readAll(in); }
            c.disconnect();
            JSONObject j = new JSONObject(txt);
            JSONArray a = j.optJSONArray("affectedAreas");
            return a != null ? a : new JSONArray();
        }
        return null;
    }

    private static double distM(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6371000;
    }

    /** Abstand Punkt–Strecke in Metern (lokal eben gerechnet) */
    private static double segDistM(double lat, double lon, double aLat, double aLon, double bLat, double bLon) {
        double k = Math.cos(Math.toRadians(lat)) * 111320, m = 110540;
        double px = (lon - aLon) * k, py = (lat - aLat) * m, bx = (bLon - aLon) * k, by = (bLat - aLat) * m;
        double l = bx * bx + by * by;
        double t = l > 0 ? Math.max(0, Math.min(1, (px * bx + py * by) / l)) : 0;
        double dx = px - t * bx, dy = py - t * by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void powerInfoImpl(double lat, double lon) {
        try {
            File f = osmFile();
            boolean inArea = osmCovers(lat, lat, lon, lon);
            if (f.isFile() && inArea) {
                if (osmCache == null || f.lastModified() > osmCacheLoaded) {
                    try (InputStream in = new FileInputStream(f)) { osmCache = new JSONArray(readAll(in)); }
                    osmCacheLoaded = System.currentTimeMillis();
                }
                JSONArray near = new JSONArray();
                for (int i = 0; i < osmCache.length(); i++) {
                    JSONObject el = osmCache.getJSONObject(i);
                    JSONObject tg = el.optJSONObject("tags");
                    String pw = tg != null ? tg.optString("power") : "";
                    if (!pw.matches("line|minor_line|cable|tower|portal|pole")) continue;   // nur Stromnetz (keine Bahn/Windräder)
                    if ("node".equals(el.optString("type"))) {
                        if (distM(lat, lon, el.optDouble("lat"), el.optDouble("lon")) <= 350) near.put(el);
                    } else {
                        JSONArray g = el.optJSONArray("geometry");
                        if (g == null) continue;
                        for (int k = 0; k + 1 < g.length(); k++) {
                            JSONObject a = g.getJSONObject(k), b = g.getJSONObject(k + 1);
                            if (segDistM(lat, lon, a.optDouble("lat"), a.optDouble("lon"), b.optDouble("lat"), b.optDouble("lon")) <= 200) {
                                near.put(new JSONObject().put("type", "way").put("tags", el.optJSONObject("tags")));
                                break;
                            }
                        }
                    }
                }
                JSONObject res = new JSONObject().put("ok", true).put("elements", near)
                        .put("cached", true).put("fetched", prefs.getLong("osm_fetched", 0));
                js("window.onPowerInfo && window.onPowerInfo(" + JSONObject.quote(res.toString()) + ")");
                return;
            }
        } catch (Exception ignored) { }
        if (!online()) {
            try {
                js("window.onPowerInfo && window.onPowerInfo(" + JSONObject.quote(new JSONObject().put("ok", false)
                        .put("error", "offline – für diese Stelle noch keine Leitungsdaten gespeichert").toString()) + ")");
            } catch (Exception ignored) { }
            return;
        }
        powerInfoOnline(lat, lon);   // außerhalb des gespeicherten Gebiets: live abfragen
    }

    private void geoEvent(JSONObject ev) {
        js("window.onGeo && window.onGeo(" + JSONObject.quote(ev.toString()) + ")");
    }

    private static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }

    private long geoFetched() { return prefs.getLong("geo_vec_fetched", 0); }

    private int geoTileCount() { return prefs.getInt("geo_vec_count", 0); }

    /** areaJson: {n,s,e,w} – Gesamtgebiet aller Felder inkl. Rand. force = alles neu laden */
    private void geoSyncImpl(String areaJson, boolean force) {
        if (geoRunning) {   // läuft schon: Einsatzgebiet-Auftrag (größeres Gebiet) danach nachholen statt verwerfen
            if (areaJson.contains("\"vec\"")) { geoQueuedArea = areaJson; geoQueuedForce = force; }
            return;
        }
        if (!online()) {   // offline: nichts versuchen, gespeicherte Geozonen bleiben einfach aktiv
            try { geoEvent(new JSONObject().put("type", "offline").put("fetched", geoFetched())); } catch (Exception ignored) { }
            return;
        }
        geoRunning = true;
        geoLastError = null;
        try {
            JSONObject b = new JSONObject(areaJson);
            double n = b.getDouble("n"), s = b.getDouble("s"), e = b.getDouble("e"), w = b.getDouble("w");
            final double fn = n, fs = s, fe = e, fw = w;
            if (!b.optBoolean("onlyZones")) new Thread(() -> {   // „Geozonen neu holen“: nur die Zonendaten, nichts anderes
                regionSync(fn, fs, fe, fw, force);
                js("window.onRegions && window.onRegions()");
                osmSync(fn, fs, fe, fw, force);   // zuerst: liefert aus der Geofabrik-Datei auch Orte und Flugplätze
                js("window.onOsmLines && window.onOsmLines()");
                placeSync(fn, fs, fe, fw, force);
                js("window.onPlaces && window.onPlaces()");
                airfieldSync(fn, fs, fe, fw, force);
                dulvSyncImpl(false);   // läuft selbst im Hintergrund, nur alle 90 Tage
                notamSyncImpl(fn, fs, fe, fw, force);
                js("window.onNotams && window.onNotams()");
                js("window.onAirfields && window.onAirfields()");
            }).start();

            // „Einsatzgebiet speichern“: Zonendaten für das ganze Einsatzgebiet (wie das Kartenbild), nicht nur direkt um die Wiesen
            final double sn = n, ss = s, se = e, sw = w;   // engeres Gebiet: für Wohngrundstücke (sehr viele kleine Flächen)
            JSONObject vec = b.optJSONObject("vec");
            if (vec != null) { n = Math.max(n, vec.getDouble("n")); s = Math.min(s, vec.getDouble("s")); e = Math.max(e, vec.getDouble("e")); w = Math.min(w, vec.getDouble("w")); }
            boolean covers = false;
            try {
                JSONObject ob = new JSONObject(prefs.getString("geo_vec_bbox", "{}"));
                covers = ob.has("n") && ob.getDouble("n") >= n && ob.getDouble("s") <= s && ob.getDouble("e") >= e && ob.getDouble("w") <= w;
            } catch (Exception ignored) { }
            boolean fresh = System.currentTimeMillis() - geoFetched() < DAY;
            if (!force && covers) {   // nur auf Knopfdruck neu – oder wenn neue Felder außerhalb des gespeicherten Gebiets liegen
                geoEvent(new JSONObject().put("type", "done").put("ok", 0).put("fail", 0).put("fetched", geoFetched()).put("tiles", geoTileCount()));
                return;
            }
            // Bereits gespeichertes Gebiet nicht verlieren: zusammenfassen, solange das Gesamtgebiet klein bleibt (< ca. 80 km)
            try {
                JSONObject ob = new JSONObject(prefs.getString("geo_vec_bbox", "{}"));
                if (ob.has("n") && !force) {
                    double un = Math.max(n, ob.getDouble("n")), us = Math.min(s, ob.getDouble("s"));
                    double ue = Math.max(e, ob.getDouble("e")), uw = Math.min(w, ob.getDouble("w"));
                    double km = Math.hypot((un - us) * 111.2, (ue - uw) * 111.2 * Math.cos(Math.toRadians((un + us) / 2)));
                    if (km < 80) { n = un; s = us; e = ue; w = uw; }
                }
            } catch (Exception ignored) { }
            File dir = geoVecDir();
            if (!dir.isDirectory()) dir.mkdirs();
            catSt("zonendaten", "load");
            int ok = 0, fail = 0, count = 0, k = 0;
            boolean shrunk = false;
            for (String layer : GEO_LAYERS) {
                geoEvent(new JSONObject().put("type", "progress").put("done", k++).put("total", GEO_LAYERS.length));
                boolean small = vec != null && "wohngrundstuecke".equals(layer);
                JSONArray feats = null;
                for (int tr = 0; tr < 3 && feats == null; tr++) {   // dipul antwortet manchmal nicht sofort: bis zu 3 Versuche
                    if (tr > 0) try { Thread.sleep(tr * 3000L); } catch (InterruptedException ie) { break; }
                    feats = small ? wfsFetch(layer, sn, ss, se, sw) : wfsFetch(layer, n, s, e, w);
                }
                if (feats == null && vec != null && !small) {   // großes Gebiet klappt nicht: wenigstens das engere Wiesengebiet
                    feats = wfsFetch(layer, sn, ss, se, sw);
                    if (feats != null) shrunk = true;
                }
                if (feats == null) { fail++; continue; }
                writeFileAtomic(geoVecFile(layer), feats.toString().replaceAll("(\\.\\d{5})\\d+", "$1"));   // 5 Nachkommastellen (≈1 m) reichen – Datei viel kleiner
                ok++;
                count += feats.length();
            }
            if (ok > 0 && fail <= 2) {
                prefs.edit().putLong("geo_vec_fetched", System.currentTimeMillis()).putInt("geo_vec_count", count)
                        .putString("geo_vec_bbox", shrunk ? new JSONObject().put("n", sn).put("s", ss).put("e", se).put("w", sw).toString()
                                : new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            }
            catSt("zonendaten", ok == 0 ? "keine Antwort von dipul" : fail > 0 ? "ok, " + fail + " von " + GEO_LAYERS.length + " Zonenarten fehlen" : "ok");
            if (ok == 0) {
                String why = !online() ? "keine Internetverbindung"
                        : geoLastError != null && geoLastError.contains("resolve host") ? "dipul-Server nicht erreichbar"
                        : geoLastError != null && geoLastError.toLowerCase(Locale.ROOT).contains("timeout") ? "dipul antwortet nicht"
                        : geoLastError != null ? geoLastError : "keine Verbindung zu dipul";
                geoEvent(new JSONObject().put("type", "error").put("message", "Geozonen nicht aktualisiert (" + why + ")."
                        + (geoFetched() > 0 ? " Die gespeicherten Geozonen bleiben aktiv." : "")).put("fetched", geoFetched()).put("force", force));
            } else {
                geoEvent(new JSONObject().put("type", "done").put("ok", ok).put("fail", fail).put("fetched", geoFetched()).put("tiles", count));
            }
        } catch (Exception ex) {
            try { geoEvent(new JSONObject().put("type", "error").put("message", ex.getMessage())); } catch (Exception ignored) { }
        } finally {
            geoRunning = false;
            String q = geoQueuedArea; boolean qf = geoQueuedForce; geoQueuedArea = null;
            if (q != null) new Thread(() -> geoSyncImpl(q, qf)).start();
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
        // 0 = unzuverlässig, 1 = niedrig, 2 = mittel, 3 = hoch -> Kalibrierungshinweis in der Windrose
        if (web != null) runOnUiThread(() -> web.evaluateJavascript("window.onCompassAcc && window.onCompassAcc(" + accuracy + ")", null));
    }

    private double lastSentHeading = -999;

    @SuppressLint("MissingPermission")
    @Override
    public void onSensorChanged(android.hardware.SensorEvent ev) {
        try {
            android.hardware.SensorManager.getRotationMatrixFromVector(rotMat, ev.values);
            int rot = getWindowManager().getDefaultDisplay().getRotation();
            int ax = android.hardware.SensorManager.AXIS_X, ay = android.hardware.SensorManager.AXIS_Y;
            int mx = android.hardware.SensorManager.AXIS_MINUS_X, my = android.hardware.SensorManager.AXIS_MINUS_Y;
            switch (rot) {   // Bildschirmdrehung berücksichtigen (RC im Querformat)
                case android.view.Surface.ROTATION_90: android.hardware.SensorManager.remapCoordinateSystem(rotMat, ay, mx, remapMat); break;
                case android.view.Surface.ROTATION_180: android.hardware.SensorManager.remapCoordinateSystem(rotMat, mx, my, remapMat); break;
                case android.view.Surface.ROTATION_270: android.hardware.SensorManager.remapCoordinateSystem(rotMat, my, ax, remapMat); break;
                default: System.arraycopy(rotMat, 0, remapMat, 0, 9);
            }
            // Blickrichtung robust bei jeder Neigung: Oberkante (Y) und Rückseite (−Z) der RC auf die Waagerechte projiziert.
            // getOrientation allein wird instabil, wenn die RC wie beim Fliegen schräg/aufrecht gehalten wird.
            double e = remapMat[1] - remapMat[2], nn = remapMat[4] - remapMat[5];
            boolean flatOk = Math.hypot(e, nn) > 0.2;
            if (!flatOk) android.hardware.SensorManager.getOrientation(remapMat, orient);
            // Missweisung (magnetisch -> geografisch Nord), alle 10 min aus letzter Position
            long now = System.currentTimeMillis();
            if (now - declinationAt > 600000) {
                declinationAt = now;
                Location best = null;
                try {
                    for (String p : locationManager.getProviders(true)) {
                        Location l = locationManager.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    }
                } catch (Exception ignored) { }
                if (best != null) declination = new android.hardware.GeomagneticField((float) best.getLatitude(),
                        (float) best.getLongitude(), (float) best.getAltitude(), now).getDeclination();
                else declination = 2.5f;   // Mitteldeutschland ca. +2,5° (2026)
            }
            double az = flatOk ? Math.toDegrees(Math.atan2(e, nn)) : Math.toDegrees(orient[0]);
            double h = (az + declination + 720) % 360;
            if (Double.isNaN(headingSmooth)) headingSmooth = h;
            else {
                double diff = ((h - headingSmooth + 540) % 360) - 180;   // kürzester Weg über 0°/360°
                if (Math.abs(diff) < 0.8) diff = 0;   // Zittern unter 1° ignorieren
                headingSmooth = (headingSmooth + diff * (Math.abs(diff) > 30 ? 0.35 : 0.12) + 360) % 360;
            }
            if (now - headingSent > 150 && Math.abs(((headingSmooth - lastSentHeading + 540) % 360) - 180) >= 0.5) {
                lastSentHeading = headingSmooth;
                headingSent = now;
                final String v = String.format(Locale.ROOT, "%.1f", headingSmooth);
                web.evaluateJavascript("window.onHeading && window.onHeading(" + v + ")", null);
            }
        } catch (Exception ignored) { }
    }

    // ---------- Standortbestimmung ----------
    private final android.os.Handler locHandler = new android.os.Handler(Looper.getMainLooper());
    private Runnable locTimeout;

    @SuppressLint("MissingPermission")
    private void locateImpl() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
            js("window.onLocationError && window.onLocationError('Standort-Berechtigung fehlt – bitte im Dialog erlauben und dann erneut auf Standort tippen.')");
            return;
        }
        boolean gps = false, net = false;
        try { gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
        try { net = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) { }
        if (!gps && !net) {
            js("window.onLocationError && window.onLocationError('Standort ist in den Android-Einstellungen ausgeschaltet – bitte einschalten.')");
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { }
            return;
        }
        // Sofort letzte bekannte Position zeigen (falls nicht zu alt)
        Location best = null;
        for (String p : locationManager.getProviders(false)) {
            try {
                Location l = locationManager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) { }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60000) sendLocation(best, true);
        stopLocation();
        final long started = System.currentTimeMillis();
        final boolean[] gotFix = {false};
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                gotFix[0] = true;
                sendLocation(l, false);
                if (l.hasAccuracy() && l.getAccuracy() <= 15) stopLocation();   // genau genug
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try { if (gps) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper()); } catch (Exception ignored) { }
        try { if (net) locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, locationListener, Looper.getMainLooper()); } catch (Exception ignored) { }
        js("window.onLocationStatus && window.onLocationStatus('Suche GPS-Signal…" + (gps ? "" : " (GPS aus – nur Netzwerkortung)") + "')");
        if (locTimeout != null) locHandler.removeCallbacks(locTimeout);
        locTimeout = () -> {
            if (!gotFix[0]) js("window.onLocationError && window.onLocationError('Kein GPS-Empfang nach 90 s – unter freiem Himmel erneut versuchen. Die erste Ortung kann bis zu 1 Minute dauern.')");
            stopLocation();
        };
        locHandler.postDelayed(locTimeout, 90000);
    }

    private void sendLocation(Location l) { sendLocation(l, false); }

    private void sendLocation(Location l, boolean last) {
        js(String.format(Locale.ROOT, "window.onLocation && window.onLocation(%.7f,%.7f,%.1f,%s,%d)",
                l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : -1f, last ? "true" : "false",
                Math.max(0, (System.currentTimeMillis() - l.getTime()) / 60000)));
    }

}
