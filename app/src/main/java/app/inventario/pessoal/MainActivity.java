package app.inventario.pessoal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_GALLERY = 1004;

    private WebView web;
    private WebViewAssetLoader loader;
    private int pendingCb = 0;
    private String pendingExport = null;
    private File cameraFile = null;
    private WebView printView = null; // mantém referência durante a impressão

    private File photosDir() {
        File d = new File(getFilesDir(), "photos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setFitsSystemWindows(true);
        applySystemBars();
        setContentView(web);

        loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/photos/", new WebViewAssetLoader.InternalStoragePathHandler(this, photosDir()))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    /** Barras do sistema acompanham o tema claro/escuro do app. */
    private void applySystemBars() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#0B0D12") : Color.parseColor("#F5F6FA");
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (web != null) web.setBackgroundColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.onAppPause && window.onAppPause()", null);
    }

    // ---------- callbacks para o JS ----------
    private void cb(int id, boolean ok, String data) {
        final String js = "window.__nativeCb(" + id + "," + ok + "," + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final int id = pendingCb;
        pendingCb = 0;

        if (requestCode == REQ_CAMERA) {
            final File f = cameraFile;
            cameraFile = null;
            if (resultCode != RESULT_OK || f == null || !f.exists() || f.length() == 0) { cb(id, false, "Cancelado"); return; }
            new Thread(() -> {
                try {
                    JSONArray out = new JSONArray();
                    out.put(Photos.fromUri(this, Uri.fromFile(f), photosDir()));
                    f.delete();
                    cb(id, true, out.toString());
                } catch (Throwable e) { cb(id, false, "Erro na foto: " + e.getMessage()); }
            }).start();
            return;
        }
        if (requestCode == REQ_GALLERY) {
            if (resultCode != RESULT_OK || data == null) { cb(id, false, "Cancelado"); return; }
            final List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) { cb(id, false, "Cancelado"); return; }
            new Thread(() -> {
                JSONArray out = new JSONArray();
                for (Uri u : uris) {
                    try { out.put(Photos.fromUri(this, u, photosDir())); } catch (Throwable ignored) { }
                }
                if (out.length() == 0) cb(id, false, "Não foi possível ler as imagens");
                else cb(id, true, out.toString());
            }).start();
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null;
            cb(id, false, "Cancelado");
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            final String content = pendingExport;
            pendingExport = null;
            new Thread(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                    cb(id, true, "Arquivo salvo");
                } catch (Exception e) {
                    cb(id, false, String.valueOf(e.getMessage()));
                }
            }).start();
        } else if (requestCode == REQ_IMPORT) {
            new Thread(() -> {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    cb(id, true, new String(readAll(is), StandardCharsets.UTF_8));
                } catch (Throwable e) {
                    cb(id, false, String.valueOf(e.getMessage()));
                }
            }).start();
        }
    }

    static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ---------- ponte JS <-> Android ----------
    private class Bridge {

        private File f(String name) {
            String safe = name.replaceAll("[^a-zA-Z0-9_.-]", "");
            return new File(getFilesDir(), safe);
        }

        @JavascriptInterface
        public String read(String name) {
            try {
                File file = f(name);
                if (!file.exists()) return null;
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        /** Gravação atômica: escreve em .tmp e renomeia. */
        @JavascriptInterface
        public boolean write(String name, String content) {
            try {
                File target = f(name);
                File tmp = new File(target.getPath() + ".tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    fo.write(content.getBytes(StandardCharsets.UTF_8));
                    fo.getFD().sync();
                }
                return tmp.renameTo(target);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String version() {
            try {
                PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                return p.versionName + " (" + p.getLongVersionCode() + ")";
            } catch (Exception e) {
                return "?";
            }
        }

        @JavascriptInterface
        public void exportFile(int id, String fileName, String mime, String content) {
            runOnUiThread(() -> {
                pendingCb = id;
                pendingExport = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { cb(id, false, e.getMessage()); }
            });
        }

        @JavascriptInterface
        public void importFile(int id) {
            runOnUiThread(() -> {
                pendingCb = id;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { cb(id, false, e.getMessage()); }
            });
        }

        // ----- fotos -----

        /** source: "camera" ou "gallery". Retorna (callback) JSON com os ids das fotos salvas. */
        @JavascriptInterface
        public void pickPhotos(int id, String source) {
            runOnUiThread(() -> {
                pendingCb = id;
                try {
                    if ("camera".equals(source)) {
                        cameraFile = new File(getCacheDir(), "camera-" + System.currentTimeMillis() + ".jpg");
                        Uri out = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", cameraFile);
                        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(i, REQ_CAMERA);
                    } else {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("image/*");
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(Intent.createChooser(i, "Escolher fotos"), REQ_GALLERY);
                    }
                } catch (Exception e) {
                    pendingCb = 0;
                    cb(id, false, "Não foi possível abrir: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void deletePhoto(String pid) {
            String s = Photos.safe(pid);
            new File(photosDir(), s + ".webp").delete();
            new File(photosDir(), s + "_t.webp").delete();
        }

        @JavascriptInterface
        public boolean hasPhoto(String pid) {
            return new File(photosDir(), Photos.safe(pid) + ".webp").exists();
        }

        @JavascriptInterface
        public String photoB64(String pid) {
            try {
                File file = new File(photosDir(), Photos.safe(pid) + ".webp");
                if (!file.exists()) return null;
                return Base64.encodeToString(Files.readAllBytes(file.toPath()), Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        /** Salva foto vinda de backup/importação (qualquer formato de imagem em base64). */
        @JavascriptInterface
        public boolean savePhoto(String pid, String b64) {
            try {
                return Photos.saveBytes(photosDir(), Photos.safe(pid), Base64.decode(b64, Base64.DEFAULT));
            } catch (Throwable e) {
                return false;
            }
        }

        @JavascriptInterface
        public String photoStats() {
            long bytes = 0; int count = 0;
            File[] fs = photosDir().listFiles();
            if (fs != null) for (File x : fs) { bytes += x.length(); if (!x.getName().endsWith("_t.webp")) count++; }
            return "{\"count\":" + count + ",\"bytes\":" + bytes + "}";
        }

        @JavascriptInterface
        public String listPhotos() {
            JSONArray a = new JSONArray();
            File[] fs = photosDir().listFiles();
            if (fs != null) for (File x : fs) {
                String n = x.getName();
                if (n.endsWith(".webp") && !n.endsWith("_t.webp")) a.put(n.substring(0, n.length() - 5));
            }
            return a.toString();
        }

        /** Abre o diálogo de impressão do Android (permite "Salvar como PDF"). */
        @JavascriptInterface
        public void printHtml(String title, String html) {
            runOnUiThread(() -> {
                WebView pv = new WebView(MainActivity.this);
                pv.getSettings().setJavaScriptEnabled(false);
                pv.setWebViewClient(new WebViewClient() {
                    private boolean done = false;

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return loader.shouldInterceptRequest(request.getUrl());
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (done) return;
                        done = true;
                        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                        String job = title.replaceAll("[\\\\/:*?\"<>|]", "_");
                        pm.print(job, view.createPrintDocumentAdapter(job),
                                new PrintAttributes.Builder()
                                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                        .build());
                    }
                });
                printView = pv;
                pv.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", html, "text/html", "UTF-8", null);
            });
        }

        /**
         * Requisição S3 assinada (AWS Signature V4), feita no nativo para evitar CORS.
         * req: {endpoint, region, bucket, accessKey, secretKey, method, key, body, query, photoUp, photoDown}
         */
        @JavascriptInterface
        public void s3(int id, String reqJson) {
            new Thread(() -> {
                try {
                    JSONObject r = new JSONObject(reqJson);
                    String res = S3.request(r, photosDir());
                    cb(id, true, res);
                } catch (Throwable e) {
                    cb(id, false, String.valueOf(e.getMessage()));
                }
            }).start();
        }
    }

    // ---------- fotos: redimensiona e salva em WebP ----------
    static class Photos {
        static final int FULL = 1280, THUMB = 320;
        static final SecureRandom RND = new SecureRandom();

        static String safe(String id) { return id == null ? "x" : id.replaceAll("[^a-zA-Z0-9_-]", ""); }

        static String newId() {
            return "p" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RND.nextInt(1 << 20), 36);
        }

        @SuppressWarnings("deprecation")
        static void writeWebp(Bitmap b, File f, int q) throws Exception {
            Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                if (!b.compress(fmt, q, fo)) throw new Exception("falha ao comprimir");
            }
            if (!tmp.renameTo(f)) throw new Exception("falha ao gravar");
        }

        static Bitmap scaleMax(Bitmap b, int max) {
            int w = b.getWidth(), h = b.getHeight(), m = Math.max(w, h);
            if (m <= max) return b;
            float k = (float) max / m;
            return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        }

        static Bitmap squareThumb(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight(), s = Math.min(w, h);
            Bitmap c = Bitmap.createBitmap(b, (w - s) / 2, (h - s) / 2, s, s);
            return s > THUMB ? Bitmap.createScaledBitmap(c, THUMB, THUMB, true) : c;
        }

        static Bitmap rotate(Bitmap b, int orientation) {
            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
                default: return b;
            }
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }

        static Bitmap decode(byte[] data) throws Exception {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) throw new Exception("imagem inválida");
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= FULL) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) throw new Exception("imagem inválida");
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(new ByteArrayInputStream(data))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Throwable ignored) { }
            return rotate(b, orientation);
        }

        static void saveBitmap(File dir, String id, Bitmap b) throws Exception {
            Bitmap full = scaleMax(b, FULL);
            writeWebp(full, new File(dir, id + ".webp"), 75);
            writeWebp(squareThumb(full), new File(dir, id + "_t.webp"), 70);
        }

        static String fromUri(Activity a, Uri u, File dir) throws Exception {
            byte[] data;
            try (InputStream is = a.getContentResolver().openInputStream(u)) { data = readAll(is); }
            String id = newId();
            saveBitmap(dir, id, decode(data));
            return id;
        }

        static boolean isWebp(byte[] d) {
            return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                    && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
        }

        /** WebP já pequeno é gravado como está (sem perder qualidade); o resto é convertido. */
        static boolean saveBytes(File dir, String id, byte[] data) throws Exception {
            Bitmap b = decode(data);
            if (isWebp(data) && Math.max(b.getWidth(), b.getHeight()) <= FULL) {
                File f = new File(dir, id + ".webp");
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
                writeWebp(squareThumb(b), new File(dir, id + "_t.webp"), 70);
            } else {
                saveBitmap(dir, id, b);
            }
            return true;
        }
    }

    // ---------- cliente S3 mínimo (SigV4) ----------
    static class S3 {
        static String request(JSONObject r, File photosDir) throws Exception {
            String region = r.optString("region", "us-east-1").trim();
            if (region.isEmpty()) region = "us-east-1";
            String bucket = r.getString("bucket").trim();
            String endpoint = r.optString("endpoint", "").trim();
            String method = r.optString("method", "GET");
            String key = r.optString("key", "");
            String photoUp = r.optString("photoUp", "");
            String photoDown = r.optString("photoDown", "");
            JSONObject q = r.optJSONObject("query");

            String scheme, host, path;
            if (endpoint.isEmpty()) {
                scheme = "https";
                host = bucket + ".s3." + region + ".amazonaws.com";
                path = "/" + encPath(key);
            } else {
                if (!endpoint.contains("://")) endpoint = "https://" + endpoint;
                URL eu = new URL(endpoint);
                scheme = eu.getProtocol();
                host = eu.getHost() + (eu.getPort() > 0 ? ":" + eu.getPort() : "");
                String base = eu.getPath() == null ? "" : eu.getPath().replaceAll("/+$", "");
                path = base + "/" + encPath(bucket) + "/" + encPath(key);
                if (key.isEmpty()) path = base + "/" + encPath(bucket) + "/";
            }

            TreeMap<String, String> qm = new TreeMap<>();
            if (q != null) {
                java.util.Iterator<String> it = q.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    qm.put(enc(k), enc(q.getString(k)));
                }
            }
            StringBuilder qs = new StringBuilder();
            for (String k : qm.keySet()) {
                if (qs.length() > 0) qs.append('&');
                qs.append(k).append('=').append(qm.get(k));
            }

            byte[] payload = new byte[0];
            String contentType = "application/json";
            if ("PUT".equals(method)) {
                if (!photoUp.isEmpty()) {
                    payload = Files.readAllBytes(new File(photosDir, Photos.safe(photoUp) + ".webp").toPath());
                    contentType = "image/webp";
                } else {
                    payload = r.optString("body", "").getBytes(StandardCharsets.UTF_8);
                }
            }
            String payloadHash = hex(sha256(payload));

            SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            String amzDate = df.format(new Date());
            String date = amzDate.substring(0, 8);

            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonical = method + "\n" + path + "\n" + qs + "\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + payloadHash + "\n"
                    + "x-amz-date:" + amzDate + "\n\n"
                    + signedHeaders + "\n" + payloadHash;

            String scope = date + "/" + region + "/s3/aws4_request";
            String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));

            byte[] k = hmac(("AWS4" + r.getString("secretKey").trim()).getBytes(StandardCharsets.UTF_8), date);
            k = hmac(k, region);
            k = hmac(k, "s3");
            k = hmac(k, "aws4_request");
            String sig = hex(hmac(k, toSign));

            String auth = "AWS4-HMAC-SHA256 Credential=" + r.getString("accessKey").trim() + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + sig;

            URL url = new URL(scheme + "://" + host + path + (qs.length() > 0 ? "?" + qs : ""));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod(method);
            c.setRequestProperty("x-amz-date", amzDate);
            c.setRequestProperty("x-amz-content-sha256", payloadHash);
            c.setRequestProperty("Authorization", auth);
            if ("PUT".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] resp = is == null ? new byte[0] : readAll(is);
            c.disconnect();
            if (code >= 300) throw new Exception("HTTP " + code + ": " + shortErr(new String(resp, StandardCharsets.UTF_8)));
            if (!photoDown.isEmpty()) {
                Photos.saveBytes(photosDir, Photos.safe(photoDown), resp);
                return "ok";
            }
            return new String(resp, StandardCharsets.UTF_8);
        }

        static String shortErr(String xml) {
            int a = xml.indexOf("<Message>"), b = xml.indexOf("</Message>");
            if (a >= 0 && b > a) return xml.substring(a + 9, b);
            return xml.length() > 200 ? xml.substring(0, 200) : xml;
        }

        static String enc(String s) {
            StringBuilder sb = new StringBuilder();
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                char ch = (char) (b & 0xff);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                        || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch);
                else sb.append(String.format("%%%02X", b & 0xff));
            }
            return sb.toString();
        }

        static String encPath(String p) {
            String[] parts = p.split("/", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(enc(parts[i]));
            }
            return sb.toString();
        }

        static byte[] sha256(byte[] d) throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(d);
        }

        static byte[] hmac(byte[] key, String data) throws Exception {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }

        static String hex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
    }
}
