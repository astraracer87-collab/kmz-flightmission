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
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        web = new WebView(this);
        setContentView(web);
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
        web.setWebViewClient(new WebViewClient());
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
        if (!e.find()) return null;
        int en = Integer.parseInt(e.group(1)), sub = s.find() ? Integer.parseInt(s.group(1)) : -1;
        for (Map.Entry<String, int[]> d : DRONES.entrySet())
            if (d.getValue()[0] == en && d.getValue()[1] == sub) return d.getKey();
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

        /** Kopiert/verschiebt Dateien in den Zielordner */
        @JavascriptInterface
        public String transfer(String pathsJson, String dstPath, boolean move, boolean clear, String drone) {
            JSONObject res = new JSONObject();
            JSONArray errors = new JSONArray();
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
                    try {
                        byte[] converted = (drone == null || drone.isEmpty()) ? null : convertKmz(src, drone);
                        String name = converted != null ? swapDroneInName(src.getName(), drone) : src.getName();
                        File target = new File(dst, name);
                        if (src.getCanonicalPath().equals(target.getCanonicalPath())) {
                            errors.put(src.getName() + ": liegt bereits im Zielordner");
                            continue;
                        }
                        if (converted != null) {
                            try (OutputStream o = new FileOutputStream(target, false)) { o.write(converted); }
                            target.setLastModified(System.currentTimeMillis());
                            if (target.length() != converted.length) throw new Exception("Schreiben unvollständig");
                        } else {
                            copy(src, target);
                            if (target.length() != src.length()) throw new Exception("Kopie unvollständig");
                        }
                        if (move && !src.delete()) errors.put(src.getName() + ": kopiert, Original konnte nicht gelöscht werden");
                        done++;
                    } catch (Exception e) {
                        errors.put(src.getName() + ": " + e.getMessage());
                    }
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
        public String notams() {
            try {
                File f = notamFile();
                return new JSONObject().put("fetched", prefs.getLong("notam_fetched", 0))
                        .put("data", f.isFile() ? new JSONObject(readFile(f)) : new JSONObject()).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void notamSync(boolean force) {
            new Thread(() -> {
                try {
                    JSONObject b = new JSONObject(prefs.getString("af_bbox", "{}"));
                    if (b.has("n")) notamSyncImpl(b.getDouble("n"), b.getDouble("s"), b.getDouble("e"), b.getDouble("w"), force);
                } catch (Exception ignored) { }
                js("window.onNotams && window.onNotams()");
            }).start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { js("window.onGeoInfo && window.onGeoInfo(" + JSONObject.quote("{\"ok\":false,\"error\":\"Kein Browser zum Öffnen gefunden\"}") + ")"); }
            });
        }

        /** Gespeicherte Geozonen als {ebene:[features...]} */
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
        for (String base : new String[]{name, t}) {
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
                    uaveFields.put(f.getString("id"), f);
                }
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
                    if (mf.isFile()) { found = mf; status = mod > m.optLong("mod", 0) ? "changed" : "ok"; withMeta(m, f); }
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
                        status = mod > found.lastModified() ? "changed" : "ok";
                        man.put(id, withMeta(new JSONObject().put("file", found.getName()).put("mod", found.lastModified()), f));
                    }
                }
                if ("missing".equals(status)) missing++;
                if ("changed".equals(status)) changed++;
                result.put(new JSONObject().put("id", id).put("name", name).put("status", status)
                        .put("file", found != null ? found.getName() : JSONObject.NULL));
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
    private String uaveExportOne(String fieldId, String name, File dir, String tzOffset) throws Exception {
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
                .put("fieldIds", new JSONArray().put(fieldId))
                .put("outputs", new JSONArray().put(new JSONObject().put("format", "kml-pilot-2"))));
        form.put("items", items.toString());

        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Resp r = http("POST", UAVE_SERVICES + "/export", h, formEncode(form).getBytes(StandardCharsets.UTF_8));
        if (r.code >= 400) throw new Exception("Export abgelehnt (" + r.code + ")");
        Matcher m = Pattern.compile("exportStateId\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(r.text());
        if (!m.find()) throw new Exception("Export-Auftrag nicht erkannt");
        String stateId = m.group(1);

        // Status abfragen, bis fertig (max. 3 Minuten)
        JSONObject state = null;
        long until = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < until) {
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
            return first;
        }
        if (!fname.toLowerCase(Locale.ROOT).endsWith(".kmz")) fname = fname.replaceAll("\\.[A-Za-z0-9]{1,4}$", "") + ".kmz";
        try (OutputStream o = new FileOutputStream(new File(dir, fname))) { o.write(d.body); }
        return fname;
    }

    /** Exportiert die Felder nacheinander in den Ordner. Liefert {files:{id:datei}, ok, errors} */
    private JSONObject uaveExportLoop(JSONArray ids, File dir, String tzOffset) throws Exception {
        int ok = 0;
        JSONArray errors = new JSONArray();
        JSONObject files = new JSONObject();
        JSONObject man = manifest();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            JSONObject f = uaveFields.get(id);
            String name = f != null ? f.optString("name", id) : id;
            try {
                uaveEvent(new JSONObject().put("type", "progress").put("done", i).put("total", ids.length()).put("name", name));
                String file;
                try {
                    file = uaveExportOne(id, name, dir, tzOffset);
                } catch (Exception first) {
                    // Token evtl. abgelaufen: einmal neu anmelden und wiederholen
                    uaveLogin();
                    file = uaveExportOne(id, name, dir, tzOffset);
                }
                // alte Datei mit anderem Namen entfernen, damit keine Dubletten entstehen
                JSONObject old = man.optJSONObject(id);
                if (old != null && !file.equals(old.optString("file"))) {
                    File of = new File(dir, old.optString("file"));
                    if (of.isFile()) of.delete();
                }
                man.put(id, withMeta(new JSONObject().put("file", file).put("mod", System.currentTimeMillis()), f));
                prefs.edit().putString("uave_manifest", man.toString()).apply();
                files.put(id, file);
                ok++;
            } catch (Exception e) {
                errors.put(name + ": " + friendlyError(e));
            }
        }
        return new JSONObject().put("ok", ok).put("errors", errors).put("files", files);
    }

    private void uaveDownloadImpl(String idsJson, String srcDir, String tzOffset) {
        if (uaveBusy) return;
        uaveBusy = true;
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
    private volatile String geoLastError = null;

    private File geoVecDir() { return new File(getFilesDir(), "dipul_vec"); }
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

    private File osmFile() { return new File(getFilesDir(), "osm_power.json"); }

    private boolean osmCovers(double n, double s, double e, double w) {
        try {
            JSONObject b = new JSONObject(prefs.getString("osm_bbox", "{}"));
            return b.has("n") && b.getDouble("n") >= n && b.getDouble("s") <= s && b.getDouble("e") >= e && b.getDouble("w") <= w;
        } catch (Exception ex) { return false; }
    }

    /** Lädt alle Leitungen/Masten im Gebiet (alle 24 h oder wenn das Gebiet größer wird) */
    private void osmSync(double n, double s, double e, double w, boolean force) {
        long fetched = prefs.getLong("osm_fetched", 0);
        boolean fresh = System.currentTimeMillis() - fetched < DAY;
        if (!force && fresh && osmCovers(n, s, e, w) && osmFile().isFile()) return;
        try {
            String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
            String q = "[out:json][timeout:120];("
                    + "way[\"power\"~\"^(line|minor_line|cable)$\"](" + bb + ");"
                    + "node[\"power\"~\"^(tower|portal)$\"](" + bb + ");"
                    + "node[\"power\"=\"pole\"][\"height\"](" + bb + ");"
                    + ");out tags geom;";
            HttpURLConnection c = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000); c.setReadTimeout(150000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "KMZMissionen/1.5 (Kitzrettung, nicht-kommerziell)");
            try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) return;
            String txt;
            try (InputStream in = c.getInputStream()) { txt = readAll(in); }
            c.disconnect();
            JSONArray els = new JSONObject(txt).getJSONArray("elements");   // prüft, ob die Antwort gültig ist
            File tmp = new File(osmFile().getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(els.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(osmFile())) { osmFile().delete(); tmp.renameTo(osmFile()); }
            prefs.edit().putLong("osm_fetched", System.currentTimeMillis())
                    .putString("osm_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            osmCache = els;
            osmCacheLoaded = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    // ---------- Flugplätze (OSM) + Frequenzen (OurAirports, CC-BY) ----------
    private File afFile() { return new File(getFilesDir(), "airfields.json"); }
    private File afFreqFile() { return new File(getFilesDir(), "airfield_freqs.json"); }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    private static void writeFileAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    private String overpass(String q, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000); c.setReadTimeout(readTimeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "KMZMissionen/1.6 (Kitzrettung, nicht-kommerziell)");
        try (OutputStream o = c.getOutputStream()) { o.write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
        if (c.getResponseCode() != 200) throw new Exception("Overpass " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) { return readAll(in); } finally { c.disconnect(); }
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
            String bb = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", s, w, n, e);
            String txt = overpass("[out:json][timeout:60];(nwr[\"aeroway\"~\"^(aerodrome|heliport|airstrip)$\"](" + bb + "););out tags center;", 90000);
            JSONArray els = new JSONObject(txt).getJSONArray("elements");
            writeFileAtomic(afFile(), els.toString());
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
    private static final int[] WX_HEIGHTS = {10, 50, 100, 150};

    private void dipulWeatherImpl(String reqId, double lat, double lon) {
        JSONObject out = new JSONObject();
        try {
            JSONArray times = new JSONArray();
            java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 0; i < 24; i++) { times.put(iso.format(c.getTime())); c.add(java.util.Calendar.HOUR_OF_DAY, 1); }
            JSONArray wh = new JSONArray();
            for (int h : WX_HEIGHTS) wh.put(new JSONObject().put("reference", "AGL").put("unit", "m").put("value", h));
            JSONObject pos = new JSONObject().put("latitude", lat).put("longitude", lon).put("forecasts", times)
                    .put("qnh", new JSONObject().put("unit", "Pa"))
                    .put("temperature", new JSONObject().put("unit", "C").put("heights",
                            new JSONArray().put(new JSONObject().put("reference", "AGL").put("unit", "m").put("value", 2))))
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
            String txt = in != null ? readAll(in) : "";
            hc.disconnect();
            if (code >= 400) {
                String msg = "dipul-Wetter antwortet mit " + code;
                try { msg += ": " + new JSONObject(txt).optString("errorMessage"); } catch (Exception ignored) { }
                throw new Exception(msg);
            }
            out.put("ok", true).put("data", new JSONObject(txt));
        } catch (Exception e) {
            try { out.put("ok", false).put("error", e.getMessage() != null ? e.getMessage() : "keine Verbindung"); } catch (Exception ignored) { }
        }
        js("window.onDipulWx && window.onDipulWx(" + JSONObject.quote(reqId) + "," + JSONObject.quote(out.toString()) + ")");
    }

    // ---------- NOTAM / temporäre Betriebseinschränkungen (dipul WFS, CC BY-ND 4.0) ----------
    private File notamFile() { return new File(getFilesDir(), "notams.json"); }

    private void notamSyncImpl(double n, double s, double e, double w, boolean force) {
        try {
            if (!force && System.currentTimeMillis() - prefs.getLong("notam_fetched", 0) < 3600000L && notamFile().isFile()) return;
            JSONObject data = new JSONObject();
            for (String layer : new String[]{"temporaere_betriebseinschraenkungen", "inaktive_temporaere_betriebseinschraenkungen"}) {
                String url = "https://uas-betrieb.de/geoservices/dipul/wfs?service=WFS&version=1.1.0&request=GetFeature"
                        + "&typeName=dipul:" + layer + "&outputFormat=application/json&srsName=EPSG:4326"
                        + "&bbox=" + String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", w, s, e, n) + ",EPSG:4326";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "KMZMissionen/1.7 (Kitzrettung, nicht-kommerziell)");
                if (c.getResponseCode() != 200) { c.disconnect(); return; }
                String txt;
                try (InputStream in = c.getInputStream()) { txt = readAll(in); }
                c.disconnect();
                JSONObject fc = new JSONObject(txt);
                data.put(layer, fc.optJSONArray("features") != null ? fc.optJSONArray("features") : new JSONArray());
            }
            writeFileAtomic(notamFile(), data.toString());
            prefs.edit().putLong("notam_fetched", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
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
        if (geoRunning) return;
        geoRunning = true;
        geoLastError = null;
        try {
            JSONObject b = new JSONObject(areaJson);
            double n = b.getDouble("n"), s = b.getDouble("s"), e = b.getDouble("e"), w = b.getDouble("w");
            final double fn = n, fs = s, fe = e, fw = w;
            new Thread(() -> {
                osmSync(fn, fs, fe, fw, force);
                airfieldSync(fn, fs, fe, fw, force);
                notamSyncImpl(fn, fs, fe, fw, force);
                js("window.onNotams && window.onNotams()");
                js("window.onAirfields && window.onAirfields()");
            }).start();

            boolean covers = false;
            try {
                JSONObject ob = new JSONObject(prefs.getString("geo_vec_bbox", "{}"));
                covers = ob.has("n") && ob.getDouble("n") >= n && ob.getDouble("s") <= s && ob.getDouble("e") >= e && ob.getDouble("w") <= w;
            } catch (Exception ignored) { }
            boolean fresh = System.currentTimeMillis() - geoFetched() < DAY;
            if (!force && fresh && covers) {
                geoEvent(new JSONObject().put("type", "done").put("ok", 0).put("fail", 0).put("fetched", geoFetched()).put("tiles", geoTileCount()));
                return;
            }
            File dir = geoVecDir();
            if (!dir.isDirectory()) dir.mkdirs();
            int ok = 0, fail = 0, count = 0, k = 0;
            for (String layer : GEO_LAYERS) {
                geoEvent(new JSONObject().put("type", "progress").put("done", k++).put("total", GEO_LAYERS.length));
                JSONArray feats = wfsFetch(layer, n, s, e, w);
                if (feats == null) { fail++; continue; }
                writeFileAtomic(geoVecFile(layer), feats.toString());
                ok++;
                count += feats.length();
            }
            if (ok > 0 && fail <= 2) {
                prefs.edit().putLong("geo_vec_fetched", System.currentTimeMillis()).putInt("geo_vec_count", count)
                        .putString("geo_vec_bbox", new JSONObject().put("n", n).put("s", s).put("e", e).put("w", w).toString()).apply();
            }
            if (ok == 0) {
                geoEvent(new JSONObject().put("type", "error").put("message", "Geozonen konnten nicht aktualisiert werden"
                        + (geoLastError != null ? ": " + geoLastError : " (keine Verbindung zu dipul?)")).put("fetched", geoFetched()));
            } else {
                geoEvent(new JSONObject().put("type", "done").put("ok", ok).put("fail", fail).put("fetched", geoFetched()).put("tiles", count));
            }
        } catch (Exception ex) {
            try { geoEvent(new JSONObject().put("type", "error").put("message", ex.getMessage())); } catch (Exception ignored) { }
        } finally {
            geoRunning = false;
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }

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
            android.hardware.SensorManager.getOrientation(remapMat, orient);
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
            double h = (Math.toDegrees(orient[0]) + declination + 360) % 360;
            if (Double.isNaN(headingSmooth)) headingSmooth = h;
            else {
                double diff = ((h - headingSmooth + 540) % 360) - 180;   // kürzester Weg über 0°/360°
                headingSmooth = (headingSmooth + diff * 0.25 + 360) % 360;
            }
            if (now - headingSent > 150) {
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
