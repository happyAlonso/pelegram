package org.telegram.messenger.vpn;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * A browser download that stays in this process while the VPN is on. Android's DownloadManager runs
 * in another process, so its request would go out direct with the real address. This one fetches
 * the file through {@link WebViewProxyController#openConnection(URL)} and writes it straight into
 * Downloads (MediaStore on Android 10+, the public Download folder before that).
 */
public final class TunnelDownload {

    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_NAME = 120;

    private TunnelDownload() {
    }

    /** Whether a browser download must use {@link #start} instead of DownloadManager. */
    public static boolean needed() {
        return WebViewProxyController.isRouted();
    }

    private static final class Failure extends Exception {
        Failure(String reason) {
            super(reason);
        }
    }

    /**
     * Downloads {@code url} through the tunnel into Downloads as {@code filename}. {@code onDone}
     * runs once on the UI thread: null when the file is saved, else the reason it is not, as text for
     * the user.
     */
    public static void start(Context context, String url, String userAgent, String mimeType, String filename,
                             Utilities.Callback<String> onDone) {
        if (WebViewProxyController.isBlocked()) {
            onDone.run(LocaleController.getString(R.string.VpnWebDownloadNotConnected));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Activity activity = findActivity(context);
            if (activity != null) {
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            }
            onDone.run(LocaleController.getString(R.string.VpnWebDownloadNoStorage));
            return;
        }
        final String name = safeName(filename);
        new Thread(() -> {
            String error = null;
            try {
                long bytes = download(url, userAgent, mimeType, name);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("TunnelDownload: " + bytes + " bytes through the VPN");
                }
            } catch (Failure e) {
                error = e.getMessage();
                FileLog.e("TunnelDownload: failed: " + error);
            } catch (Throwable e) {
                FileLog.e("TunnelDownload: failed: " + e);
                error = LocaleController.getString(WebViewProxyController.isBlocked()
                        ? R.string.VpnWebDownloadNotConnected : R.string.VpnWebDownloadBroken);
            }
            final String result = error;
            AndroidUtilities.runOnUIThread(() -> onDone.run(result));
        }, "TunnelDownload").start();
    }

    private static long download(String startUrl, String userAgent, String mimeType, String name) throws Exception {
        URL url = new URL(startUrl);
        HttpURLConnection conn = null;
        try {
            // redirects by hand, so each hop gets the cookies of its own host
            for (int hop = 0; ; hop++) {
                String scheme = url.getProtocol();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    throw new Failure(LocaleController.getString(R.string.VpnWebDownloadBroken));
                }
                URLConnection c = WebViewProxyController.openConnection(url);
                conn = (HttpURLConnection) c;
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                if (!TextUtils.isEmpty(userAgent)) {
                    conn.setRequestProperty("User-Agent", userAgent);
                }
                String cookies = cookiesFor(url.toString());
                if (!TextUtils.isEmpty(cookies)) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || hop >= MAX_REDIRECTS) {
                        throw new Failure(LocaleController.formatString(R.string.VpnWebDownloadHttp, code));
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new Failure(LocaleController.formatString(R.string.VpnWebDownloadHttp, code));
                }
                break;
            }
            String mime = mimeFor(name, conn.getContentType(), mimeType);
            try (InputStream in = conn.getInputStream()) {
                return save(in, name, mime);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Writes the stream into Downloads; a partial file is removed when the copy fails. */
    private static long save(InputStream in, String name, String mime) throws Exception {
        Context context = ApplicationLoader.applicationContext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null) {
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
            }
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) {
                throw new Failure(LocaleController.getString(R.string.VpnWebDownloadNoStorage));
            }
            boolean ok = false;
            try {
                long n;
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new Failure(LocaleController.getString(R.string.VpnWebDownloadNoStorage));
                    }
                    n = copy(in, out);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                ok = true;
                return n;
            } finally {
                if (!ok) {
                    try {
                        resolver.delete(uri, null, null);
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Failure(LocaleController.getString(R.string.VpnWebDownloadNoStorage));
        }
        File file = unique(dir, name);
        boolean ok = false;
        try {
            long n;
            try (OutputStream out = new FileOutputStream(file)) {
                n = copy(in, out);
            }
            ok = true;
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, mime == null ? null : new String[]{mime}, null);
            return n;
        } finally {
            if (!ok) {
                file.delete();
            }
        }
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static File unique(File dir, String name) {
        File file = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int i = 1; file.exists() && i < 100; i++) {
            file = new File(dir, dot > 0 ? name.substring(0, dot) + "(" + i + ")" + name.substring(dot) : name + "(" + i + ")");
        }
        return file;
    }

    /** A real MIME type: from the extension, else the server's, else the page's; never "image/*". */
    private static String mimeFor(String name, String contentType, String pageMime) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (m != null) {
                return m;
            }
        }
        for (String m : new String[]{contentType, pageMime}) {
            if (m == null) {
                continue;
            }
            int semi = m.indexOf(';');
            m = (semi >= 0 ? m.substring(0, semi) : m).trim();
            if (m.indexOf('/') > 0 && !m.contains("*")) {
                return m;
            }
        }
        return null;
    }

    private static String cookiesFor(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String safeName(String filename) {
        StringBuilder sb = new StringBuilder();
        if (filename != null) {
            for (int i = 0; i < filename.length(); i++) {
                char ch = filename.charAt(i);
                sb.append(ch < 0x20 || ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?'
                        || ch == '"' || ch == '<' || ch == '>' || ch == '|' ? '_' : ch);
            }
        }
        String name = sb.toString().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "download";
        }
        if (name.length() > MAX_NAME) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_NAME - ext.length()) + ext;
        }
        return name;
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
