package app.inventario.pessoal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;

    private WebView web;
    private int pendingCb = 0;
    private String pendingExport = null;
    private WebView printView = null; // mantém referência durante a impressão

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setFitsSystemWindows(true);
        applySystemBars();
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
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
        int id = pendingCb;
        pendingCb = 0;
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
                } catch (Exception e) {
                    cb(id, false, String.valueOf(e.getMessage()));
                }
            }).start();
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
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

        /** Abre o diálogo de impressão do Android (permite "Salvar como PDF"). */
        @JavascriptInterface
        public void printHtml(String title, String html) {
            runOnUiThread(() -> {
                WebView pv = new WebView(MainActivity.this);
                pv.getSettings().setJavaScriptEnabled(false);
                pv.setWebViewClient(new WebViewClient() {
                    private boolean done = false;
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
                pv.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null);
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

        /**
         * Requisição S3 assinada (AWS Signature V4), feita no nativo para evitar CORS.
         * req: {endpoint, region, bucket, accessKey, secretKey, method, key, body, query}
         */
        @JavascriptInterface
        public void s3(int id, String reqJson) {
            new Thread(() -> {
                try {
                    JSONObject r = new JSONObject(reqJson);
                    String res = S3.request(r);
                    cb(id, true, res);
                } catch (Exception e) {
                    cb(id, false, String.valueOf(e.getMessage()));
                }
            }).start();
        }
    }

    // ---------- cliente S3 mínimo (SigV4) ----------
    static class S3 {
        static String request(JSONObject r) throws Exception {
            String region = r.optString("region", "us-east-1").trim();
            if (region.isEmpty()) region = "us-east-1";
            String bucket = r.getString("bucket").trim();
            String endpoint = r.optString("endpoint", "").trim();
            String method = r.optString("method", "GET");
            String key = r.optString("key", "");
            String body = r.optString("body", "");
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

            byte[] payload = "PUT".equals(method) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
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
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            c.disconnect();
            if (code >= 300) throw new Exception("HTTP " + code + ": " + shortErr(resp));
            return resp;
        }

        static String shortErr(String xml) {
            int a = xml.indexOf("<Message>"), b = xml.indexOf("</Message>");
            if (a >= 0 && b > a) return xml.substring(a + 9, b);
            return xml.length() > 200 ? xml.substring(0, 200) : xml;
        }

        static String enc(String s) throws Exception {
            StringBuilder sb = new StringBuilder();
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                char ch = (char) (b & 0xff);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                        || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch);
                else sb.append(String.format("%%%02X", b & 0xff));
            }
            return sb.toString();
        }

        static String encPath(String p) throws Exception {
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
