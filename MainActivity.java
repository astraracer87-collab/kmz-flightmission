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
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        dopCleanup();   // einmalig: Luftbilder mit grauem Fleck entfernen
        cacheVersionCheck();   // geänderte Datenbereiche aufräumen
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

    @Override
    protected void onResume() {
        super.onResume();
        try { if (hasFileAccess()) dataMigrate(); } catch (Exception ignored) { }   // Daten in „Kitzrettung Cache“ umziehen (einmalig)
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
        super.onPause();
        try { if (sensorManager != null) sensorManager.unregisterListener(this); } catch (Exception ignored) { }
        stopLocation();
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
        public void setPref(String key, String value) { prefs.edit().putString(key, value).apply(); }

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
                    out.put(new JSONObject()
                            .put("id", el.optString("type") + "/" + el.optLong("id"))
                            .put("name", t.optString("name", t.optString("short_name", "Flugplatz")))
                            .put("icao", icao)
                            .put("kind", t.optString("aeroway"))
                            .put("type", t.optString("aerodrome:type", t.optString("aerodrome", "")))
                            .put("ele", t.optString("ele", ""))
                            .put("phone", t.optString("phone", t.optString("contact:phone", "")))
                            .put("website", t.optString("website", t.optString("contact:website", "")))
                            .put("operator", t.optString("operator", ""))
                            .put("lat", lat).put("lon", lon)
                            .put("freqs", icao.isEmpty() ? new JSONArray() : freqs.optJSONArray(icao.toUpperCase(Locale.ROOT)) != null ? freqs.optJSONArray(icao.toUpperCase(Locale.ROOT)) : new JSONArray()));
                }
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
            new Thread(() -> {
                JSONArray out = new JSONArray(); long total = 0;
                try {
                    JSONObject marks = cacheMarks();
                    for (String[] c : CACHE_CATS) {
                        long[] r = {0, 0}; for (File f : catFiles(c[0])) sizeOf(f, r);
                        total += r[0];
                        JSONObject m = marks.optJSONObject(c[0]);
                        out.put(new JSONObject().put("id", c[0]).put("name", c[1]).put("bytes", r[0]).put("files", r[1]).put("ver", CACHE_VER.getOrDefault(c[0], 1))
                                .put("have", m != null ? m.optInt("ver", 0) : 0).put("t", m != null ? m.optLong("t", 0) : 0).put("st", catStatus.containsKey(c[0]) ? catStatus.get(c[0]) : ""));
                    }
                    lastTileBytes = 0; for (String id : new String[]{"karte", "luftbild", "geokacheln"}) { long[] r = {0, 0}; for (File f : catFiles(id)) sizeOf(f, r); lastTileBytes += r[0]; }
                    js("window.onCacheInfo && window.onCacheInfo(" + JSONObject.quote(new JSONObject().put("list", out).put("total", total).put("where", dataDir().getAbsolutePath()).toString()) + ")");
                } catch (Exception ignored) { }
            }).start();
        }

        /** Einen Datenbereich löschen (wird beim nächsten Speichern/Abgleich neu geholt). Ergebnis über window.onCacheCleared */
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
                            .put("o", t.optString("operator")).put("g", pts));
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
                    else text = body;
                }
                c.disconnect();
            } catch (Exception ignored) { }
            if (feats == null && text == null) {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "&info_format=text/plain").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("dipul antwortet mit " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { text = readAll(in); }
                c.disconnect();
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
        boolean oldFormat = prefs.getInt("osm_ver", 1) < 5;   // ältere Daten ohne Hindernisse/Ortsnamen: einmal neu laden
        if (!force && fresh && !oldFormat && osmCovers(n, s, e, w) && osmFile().isFile() && osmWaysFile().isFile()) return;
        catSt("hindernisse", "load"); catSt("feldwege", "load");
        try {
            String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
            String q = "[out:json][timeout:120];("
                    + "way[\"power\"~\"^(line|minor_line|cable)$\"](" + bb + ");"
                    + "node[\"power\"~\"^(tower|portal)$\"](" + bb + ");"
                    + "node[\"power\"=\"pole\"][\"height\"](" + bb + ");"
                    // Hindernisse: Windräder, hohe Bauwerke, Bahnstrecken
                    + "nwr[\"power\"=\"generator\"][\"generator:source\"=\"wind\"](" + bb + ");"
                    + "node[\"man_made\"~\"^(mast|tower|chimney|communications_tower)$\"](" + bb + ");"
                    + "way[\"railway\"~\"^(rail|light_rail)$\"](" + bb + ");"
                    // Orts- und Flurnamen für die Suche (offline)
                    + "node[\"place\"~\"^(city|town|village|hamlet|suburb|quarter|neighbourhood|isolated_dwelling|farm|locality)$\"][\"name\"](" + bb + ");"
                    + ");out tags geom;";
            JSONArray els = new JSONObject(overpass(q, 180000)).getJSONArray("elements");   // mit Ausweichservern
            writeFileAtomic(osmFile(), els.toString());
            catSt("hindernisse", "ok");
            // Feld- und Wirtschaftswege eigene Abfrage: sehr viele Daten – schlägt sie fehl, bleiben die Hindernisse trotzdem gespeichert
            // in Kacheln von ca. 10 km abfragen – kleine Anfragen kommen bei überlasteten Servern viel eher durch
            try {
                double km = Math.max((n - s) * 111.2, (e - w) * 111.2 * Math.cos(Math.toRadians((n + s) / 2)));
                int k = Math.max(1, Math.min(6, (int) Math.ceil(km / 10)));
                JSONArray wys = new JSONArray(); java.util.Set<Long> seenW = new java.util.HashSet<>(); int bad = 0; Exception wErr = null;
                for (int iy = 0; iy < k; iy++) for (int ix = 0; ix < k; ix++) {
                    double ts = s + (n - s) * iy / k, tn = s + (n - s) * (iy + 1) / k, tw = w + (e - w) * ix / k, te = w + (e - w) * (ix + 1) / k;
                    String tb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", ts, tw, tn, te);
                    catSt("feldwege", "load");
                    try {
                        JSONArray part = new JSONObject(overpass("[out:json][timeout:90];(way[\"highway\"~\"^(track|path|service|unclassified|tertiary|bridleway|cycleway|footway)$\"](" + tb + "););out tags geom;", 120000)).getJSONArray("elements");
                        for (int q2 = 0; q2 < part.length(); q2++) { JSONObject el = part.getJSONObject(q2); if (seenW.add(el.optLong("id"))) wys.put(el); }
                    } catch (Exception te2) { bad++; wErr = te2; }
                }
                if (bad == 0 || (wys.length() > 0 && !osmWaysFile().isFile())) writeFileAtomic(osmWaysFile(), wys.toString());
                catSt("feldwege", bad == 0 ? "ok" : (osmWaysFile().isFile() ? "alt:" : "") + bad + " von " + (k * k) + " Teilen fehlen – " + errTxt(wErr));
            } catch (Exception we) { catSt("feldwege", (osmWaysFile().isFile() ? "alt:" : "") + errTxt(we)); }
            prefs.edit().putLong("osm_fetched", System.currentTimeMillis()).putInt("osm_ver", 5)
                    .putString("osm_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            osmCache = els;
            osmCacheLoaded = System.currentTimeMillis();
        } catch (Exception ex) { catSt("hindernisse", (osmFile().isFile() ? "alt:" : "") + errTxt(ex)); if ("load".equals(catStatus.get("feldwege"))) catSt("feldwege", (osmWaysFile().isFile() ? "alt:" : "") + errTxt(ex)); }
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
    private void openInApp(String url) {
        if (pageBox == null) {
            float dp = getResources().getDisplayMetrics().density;
            pageBox = new android.widget.LinearLayout(this);
            pageBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            pageBox.setBackgroundColor(0xffffffff);
            android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xff15202b);
            bar.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
            android.widget.Button back = new android.widget.Button(this);
            back.setText("‹  Zurück zur App"); back.setAllCaps(false); back.setTextSize(17);
            back.setOnClickListener(v -> closeInApp());
            pageTitle = new android.widget.TextView(this);
            pageTitle.setTextColor(0xffe6edf3); pageTitle.setTextSize(15); pageTitle.setSingleLine(true);
            pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pageTitle.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
            android.widget.Button ext = new android.widget.Button(this);
            ext.setText("Im Browser"); ext.setAllCaps(false); ext.setTextSize(15);
            ext.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl != null ? pdfUrl : pageWeb.getUrl()))); } catch (Exception ignored) { } });
            bar.addView(back);
            bar.addView(pageTitle, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            bar.addView(ext);
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
                public void onPageFinished(WebView v, String u) { if (pageTitle != null) pageTitle.setText(v.getTitle() != null && !v.getTitle().isEmpty() ? v.getTitle() : u); }
            });
            pageBox.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
            pageBox.addView(pageWeb, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
            rootView.addView(pageBox, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        pageBox.setVisibility(android.view.View.VISIBLE);
        pageBox.bringToFront();
        if (isPdfUrl(url)) { showPdf(url); return; }
        pdfUrl = null;
        pageTitle.setText(url);
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
    // Sobald die Speicher-Freigabe da ist, liegen sie im Ordner „Kitzrettung Cache“ im internen Speicher (neben „KMZ Mission“) –
    // so bleiben sie auch bei Neuinstallation der App erhalten. Vorher (und bis zum Umzug) im App-eigenen Speicher.
    private volatile File dataDirCache = null;
    private File extDataDir() { return new File(Environment.getExternalStorageDirectory(), "Kitzrettung Cache"); }
    private File dataDir() {
        File c = dataDirCache; if (c != null) return c;
        if (prefs != null && prefs.getBoolean("data_ext_done", false) && hasFileAccess()) {
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
                            if (!(t.isFile() && t.length() == f.length())) {
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
            {"flugplaetze", "Flugplätze"}, {"wiesen", "Feldgrenzen der Wiesen"}};
    /** Version je Bereich: erhöhen, wenn sich das gespeicherte Format/der Umfang ändert – dann räumt die App diesen Bereich beim Start auf und holt ihn neu */
    private static final Map<String, Integer> CACHE_VER = new HashMap<>();
    static {
        CACHE_VER.put("karte", 1); CACHE_VER.put("luftbild", 1); CACHE_VER.put("geokacheln", 1); CACHE_VER.put("zonendaten", 2);
        CACHE_VER.put("feldwege", 1); CACHE_VER.put("hindernisse", 1); CACHE_VER.put("windraeder", 1); CACHE_VER.put("orte", 1);
        CACHE_VER.put("flugplaetze", 1); CACHE_VER.put("wiesen", 1);
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
            case "wiesen": l.add(outlineFile()); l.add(outlineFileByKmz()); break;
        }
        return l;
    }
    /** Merker zurücksetzen, damit der Bereich beim nächsten Speichern/Abgleich wirklich neu geholt wird */
    private void catReset(String id) {
        SharedPreferences.Editor e = prefs.edit();
        switch (id) {
            case "zonendaten": e.remove("geo_vec_bbox").remove("geo_vec_fetched"); break;
            case "feldwege": case "hindernisse": e.remove("osm_fetched").remove("osm_bbox"); break;
            case "orte": e.remove("place_fetched").remove("place_bbox").remove("reg_fetched"); break;
            case "flugplaetze": e.remove("af_fetched").remove("af_bbox").remove("dulv_t"); break;
            case "wiesen": e.remove("uave_outlines_fetched"); break;
        }
        e.remove("esBox5").putString("cacheRefetch", "1").apply();   // App holt beim nächsten Mal mit Internet automatisch neu
    }
    private static void sizeOf(File f, long[] r) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) { r[0] += f.length(); r[1]++; return; }
        java.util.ArrayDeque<File> st = new java.util.ArrayDeque<>(); st.push(f);
        while (!st.isEmpty()) { File[] fs = st.pop().listFiles(); if (fs == null) continue;
            for (File x : fs) { if (x.isDirectory()) st.push(x); else { r[0] += x.length(); r[1]++; } } }
    }
    // Versions-Merker liegt IM Cache-Ordner („Versionen.json“) – bleibt bei Neuinstallation erhalten und wird bei jedem Start schnell verglichen
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
                {"osm_places.json", "Orte"}, {"osm_ways.json", "Feldwege"}, {"regions.json", "Orte"}, {"airfields.json", "Flugplaetze"}, {"airfield_freqs.json", "Flugplaetze"}, {"dulv_airfields.json", "Flugplaetze"}, {"notams.json", "Geozonen"}};
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
            if (!force && fresh && covers && regionFile().isFile()) return;
            catSt("orte", "load");
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
        } catch (Exception ex) { catSt("orte", "Landesgrenzen: " + errTxt(ex)); }
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
            "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"};

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
    private String overpass(String q, int readTimeoutMs) throws Exception {
        Exception first = null;
        for (String ep : OVERPASS) {
          for (int tr = 0; tr < 2; tr++) {   // überlastet (429/504): kurz warten, nochmal
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000); c.setReadTimeout(readTimeoutMs);
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
                try (InputStream in = c.getInputStream()) { return readAll(in); } finally { c.disconnect(); }
            } catch (Exception e) {
                // Zertifikatsfehler eines Ausweichservers (alte Android-Version kennt dessen Zertifikat nicht) nicht als Hauptgrund melden
                if (first == null && !(e instanceof javax.net.ssl.SSLException) && !String.valueOf(e.getMessage()).contains("CertPath")) first = e;
                break;
            }
          }
        }
        throw first != null ? first : new Exception("Kein OpenStreetMap-Server erreichbar");
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

    private void airfieldSync(double n, double s, double e, double w, boolean force) {
        try {
            boolean fresh = System.currentTimeMillis() - prefs.getLong("af_fetched", 0) < DAY;
            boolean covers = false;
            try {
                JSONObject b = new JSONObject(prefs.getString("af_bbox", "{}"));
                covers = b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
            } catch (Exception ignored) { }
            if (!force && fresh && covers && afFile().isFile()) return;
            catSt("flugplaetze", "load");
            String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
            String txt = overpass("[out:json][timeout:60];(nwr[\"aeroway\"~\"^(aerodrome|heliport|airstrip)$\"](" + bb + "););out tags center;", 90000);
            JSONArray els = new JSONObject(txt).getJSONArray("elements");
            writeFileAtomic(afFile(), els.toString());
            catSt("flugplaetze", "ok");
            prefs.edit().putLong("af_fetched", System.currentTimeMillis())
                    .putString("af_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();

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

    private void notamSyncImpl(double n, double s, double e, double w, boolean force) {
        try {
            if (!force && System.currentTimeMillis() - prefs.getLong("notam_fetched", 0) < 3600000L && notamFile().isFile()) return;
            // 1. Wahl: dipul-Dienst „affectedAreas“ (mit Aktivierungszeiten, Höhen, Kreisen); bei Fehler: WFS-Ebenen wie bisher
            try {
                JSONArray areas = dipulAffectedAreas();
                if (areas != null) {
                    JSONObject data = new JSONObject().put("areas", areas).put("src", "api");
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
            prefs.edit().putString("notam_err", "Abruf fehlgeschlagen: " + ex.getMessage()).apply();
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
            new Thread(() -> {
                regionSync(fn, fs, fe, fw, force);
                js("window.onRegions && window.onRegions()");
                placeSync(fn, fs, fe, fw, force);
                js("window.onPlaces && window.onPlaces()");
                osmSync(fn, fs, fe, fw, force);
                js("window.onOsmLines && window.onOsmLines()");
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
