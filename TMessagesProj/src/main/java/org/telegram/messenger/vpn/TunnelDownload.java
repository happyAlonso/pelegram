package org.telegram.messenger.vpn;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
 * A download that stays in this process while the VPN is on: the in-app browser's page downloads
 * and bot mini-app downloads. Android's DownloadManager runs in another process, so its request
 * would go out direct with the real address. This one fetches the file through
 * {@link WebViewProxyController#openConnection(URL)} and writes it straight into Downloads
 * (MediaStore on Android 10+, the public Download folder before that).
 */
public final class TunnelDownload {

    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_NAME = 120;

    private TunnelDownload() {
    }

    /** Whether a download must use {@link #start} instead of DownloadManager. */
    public static boolean needed() {
        return WebViewProxyController.isRouted();
    }

    /** A running download: progress for a list row, and a way to stop it. */
    public static final class Job {
        public volatile long loaded;
        /** -1 while the size is unknown. */
        public volatile long total = -1;
        volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    public interface Listener {
        /**
         * Runs once on the UI thread. {@code error} is null when the file is saved, else the reason
         * it is not, as text for the user. {@code file} is the saved file when its path is known.
         */
        void onDone(File file, String error);
    }

    private static final class Failure extends Exception {
        Failure(String reason) {
            super(reason);
        }
    }

    /** {@link #start(Context, String, String, String, String, Listener)} for a caller that needs only the result text. */
    public static Job start(Context context, String url, String userAgent, String mimeType, String filename,
                            Utilities.Callback<String> onDone) {
        return start(context, url, userAgent, mimeType, filename, (file, error) -> onDone.run(error));
    }

    /** Downloads {@code url} through the tunnel into Downloads as {@code filename}. */
    public static Job start(Context context, String url, String userAgent, String mimeType, String filename,
                            Listener listener) {
        final Job job = new Job();
        if (WebViewProxyController.isBlocked()) {
            // posted, so a caller that registers the download after start() returns sees the result
            final String reason = LocaleController.getString(R.string.VpnWebDownloadNotConnected);
            AndroidUtilities.runOnUIThread(() -> listener.onDone(null, reason));
            return job;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28 && context != null
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Activity activity = findActivity(context);
            if (activity != null) {
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            }
            final String reason = LocaleController.getString(R.string.VpnWebDownloadNoStorage);
            AndroidUtilities.runOnUIThread(() -> listener.onDone(null, reason));
            return job;
        }
        final String name = safeName(filename);
        new Thread(() -> {
            String error = null;
            File saved = null;
            try {
                saved = download(job, url, userAgent, mimeType, name);
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("TunnelDownload: " + job.loaded + " bytes through the VPN");
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
            final File file = saved;
            AndroidUtilities.runOnUIThread(() -> listener.onDone(file, result));
        }, "TunnelDownload").start();
        return job;
    }

    private static File download(Job job, String startUrl, String userAgent, String mimeType, String name) throws Exception {
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
            job.total = conn.getContentLength();
            String mime = mimeFor(name, conn.getContentType(), mimeType);
            try (InputStream in = conn.getInputStream()) {
                return save(job, in, name, mime);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Writes the stream into Downloads; a partial file is removed when the copy fails. */
    private static File save(Job job, InputStream in, String name, String mime) throws Exception {
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
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new Failure(LocaleController.getString(R.string.VpnWebDownloadNoStorage));
                    }
                    copy(job, in, out);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                ok = true;
                return pathOf(resolver, uri);
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
            try (OutputStream out = new FileOutputStream(file)) {
                copy(job, in, out);
            }
            ok = true;
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, mime == null ? null : new String[]{mime}, null);
            return file;
        } finally {
            if (!ok) {
                file.delete();
            }
        }
    }

    /** The file behind a MediaStore row, for a caller that opens it later; null when unknown. */
    @SuppressWarnings("deprecation")
    private static File pathOf(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                return TextUtils.isEmpty(path) ? null : new File(path);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void copy(Job job, InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (job.cancelled) {
                throw new Failure("cancelled");
            }
            out.write(buf, 0, n);
            job.loaded += n;
        }
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
