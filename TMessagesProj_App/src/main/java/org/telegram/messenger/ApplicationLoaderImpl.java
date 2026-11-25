package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class ApplicationLoaderImpl extends ApplicationLoader {

    // Fork version = the release tag without the leading "v". Single source of truth is
    // APP_VERSION_NAME in gradle.properties -> manifest versionName -> here. Bump it there only.
    private static final String CURRENT_VERSION = BuildConfig.VERSION_NAME;
    // /releases/latest returns only the newest full release (GitHub excludes prereleases and
    // drafts), so the in-app updater never offers a test prerelease - only promoted prod builds.
    private static final String RELEASES_API = "https://api.github.com/repos/happyAlonso/pelegram/releases/latest";
    // the release page redirects /releases/latest to /releases/tag/<tag> of the same newest full
    // release, and it is not under the API limit of 60 unauthenticated requests per hour and IP.
    // A shared VPN exit runs out of that limit (403 "API rate limit exceeded", 25 Nov 2026), so
    // the tag comes from the redirect and the API is asked only for the release notes
    private static final String RELEASES_PAGE = "https://github.com/happyAlonso/pelegram/releases/latest";
    private static final String DOWNLOAD_BASE = "https://github.com/happyAlonso/pelegram/releases/download/";
    private static final Pattern TAG = Pattern.compile("v?\\d+\\.\\d+\\.\\d+");
    // checks come on every resume of LaunchActivity: an answer is reused for CHECK_INTERVAL_MS
    // and a failed check is tried again after RETRY_MS, unless the user asks (force)
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 10 * 60 * 1000L;
    private volatile long lastAnswerElapsed;
    private volatile long lastTryElapsed;
    private volatile boolean inFlight;
    // at a cold start with the VPN on, onResume comes before the tunnel is up and a check would
    // only hit the dead port, so it waits for the tunnel (UI thread only, one waiter at a time)
    private static final long TUNNEL_WAIT_STEP_MS = 2000;
    private static final int TUNNEL_WAIT_STEPS = 15;
    private boolean waitingForTunnel;
    private int tunnelWaits;

    private volatile BetaUpdate pendingUpdate;
    private volatile String pendingApkUrl;
    private volatile File downloadedFile;
    private volatile boolean downloading;
    private volatile float downloadProgress;

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // ---- custom (GitHub Releases) updater ----

    @Override
    public boolean isCustomUpdate() {
        return true;
    }

    @Override
    public BetaUpdate getUpdate() {
        return pendingUpdate;
    }

    @Override
    public boolean isDownloadingUpdate() {
        return downloading;
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return downloadProgress;
    }

    @Override
    public File getDownloadedUpdateFile() {
        return downloadedFile;
    }

    @Override
    public void cancelDownloadingUpdate() {
        downloading = false;
    }

    @Override
    public void downloadUpdate() {
        // Downloading is driven by the update dialog (showCustomUpdateAppPopup).
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        if (!force && org.telegram.messenger.vpn.WebViewProxyController.isBlocked()) {
            if (waitingForTunnel) {
                done(whenDone);
                return;
            }
            waitingForTunnel = true;
            tunnelWaits = 0;
            AndroidUtilities.runOnUIThread(() -> waitForTunnel(whenDone), TUNNEL_WAIT_STEP_MS);
            return;
        }
        checkNow(force, whenDone);
    }

    private void waitForTunnel(Runnable whenDone) {
        if (org.telegram.messenger.vpn.WebViewProxyController.isBlocked() && ++tunnelWaits < TUNNEL_WAIT_STEPS) {
            AndroidUtilities.runOnUIThread(() -> waitForTunnel(whenDone), TUNNEL_WAIT_STEP_MS);
            return;
        }
        waitingForTunnel = false;
        if (org.telegram.messenger.vpn.WebViewProxyController.isBlocked()) {
            // still no tunnel: no request, the next resume tries again
            done(whenDone);
            return;
        }
        checkNow(false, whenDone);
    }

    private static void done(Runnable whenDone) {
        AndroidUtilities.runOnUIThread(() -> {
            if (whenDone != null) {
                whenDone.run();
            }
        });
    }

    private void checkNow(boolean force, Runnable whenDone) {
        long now = SystemClock.elapsedRealtime();
        if (!force && (inFlight || (lastAnswerElapsed != 0 && now - lastAnswerElapsed < CHECK_INTERVAL_MS)
                || (lastTryElapsed != 0 && now - lastTryElapsed < RETRY_MS))) {
            // pendingUpdate still holds the last answer
            done(whenDone);
            return;
        }
        inFlight = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String tag = latestTagFromReleasePage();
                BetaUpdate update = null;
                String apkUrl = null;
                if (tag != null) {
                    String version = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
                    if (compareVersions(version, CURRENT_VERSION) > 0) {
                        // every release ships its APK under this name (arm64-v8a only)
                        apkUrl = DOWNLOAD_BASE + tag + "/pelegram-" + tag + "-arm64-v8a.apk";
                        String changelog = "";
                        try {
                            JSONObject root = new JSONObject(httpGetString(RELEASES_API));
                            if (tag.equals(root.optString("tag_name", ""))) {
                                changelog = root.optString("body", "");
                                String assetUrl = firstApkUrl(root);
                                if (assetUrl != null) {
                                    apkUrl = assetUrl;
                                }
                            }
                        } catch (Exception e) {
                            // the dialog falls back to the VpnUpdateAvailable text
                            FileLog.e("updater: no release notes for " + tag + ": " + e.getMessage());
                        }
                        update = new BetaUpdate(version, versionToCode(version), changelog);
                    }
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("updater: newest release " + tag + ", this build " + CURRENT_VERSION
                            + (update != null ? ", update offered" : ", nothing newer"));
                }
                pendingUpdate = update;
                pendingApkUrl = apkUrl;
                lastAnswerElapsed = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                // keep an update found earlier. Only an answer from GitHub (a status, a bad tag)
                // holds the next try for RETRY_MS, no network or no tunnel retries at the next resume
                FileLog.e("updater: check failed: " + e);
                boolean noAnswer = e instanceof ConnectException || e instanceof UnknownHostException
                        || e instanceof SocketTimeoutException || org.telegram.messenger.vpn.WebViewProxyController.isBlocked();
                if (!noAnswer) {
                    lastTryElapsed = SystemClock.elapsedRealtime();
                }
            }
            inFlight = false;
            done(whenDone);
        });
    }

    /** the tag /releases/latest redirects to, or null while the repo has no full release */
    private static String latestTagFromReleasePage() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) org.telegram.messenger.vpn.WebViewProxyController.openConnection(new URL(RELEASES_PAGE));
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "pelegram-updater");
        try {
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code / 100 != 3 || location == null) {
                throw new IOException("release page answered HTTP " + code + describeFailure(conn));
            }
            return tagFromLocation(location);
        } finally {
            conn.disconnect();
        }
    }

    static String tagFromLocation(String location) throws Exception {
        String marker = "/releases/tag/";
        int i = location.indexOf(marker);
        if (i < 0) {
            return null; // github.com sends /releases when there is no full release yet
        }
        String tag = location.substring(i + marker.length());
        for (char c : new char[]{'?', '#', '/'}) {
            int end = tag.indexOf(c);
            if (end >= 0) {
                tag = tag.substring(0, end);
            }
        }
        tag = URLDecoder.decode(tag, "UTF-8");
        if (!TAG.matcher(tag).matches()) {
            throw new IOException("unexpected release tag in redirect: " + tag);
        }
        return tag;
    }

    private static String firstApkUrl(JSONObject root) throws Exception {
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                    return a.optString("browser_download_url", null);
                }
            }
        }
        return null;
    }

    /** status details for the log: rate-limit headers and the start of the error body, addresses masked */
    private static String describeFailure(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        for (String h : new String[]{"X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After", "X-GitHub-Request-Id"}) {
            String v = conn.getHeaderField(h);
            if (v != null) {
                sb.append(", ").append(h).append('=').append(v);
            }
        }
        try (InputStream err = conn.getErrorStream()) {
            if (err != null) {
                byte[] buf = new byte[200];
                int n = err.read(buf);
                if (n > 0) {
                    String body = new String(buf, 0, n, StandardCharsets.UTF_8)
                            .replaceAll("\\d{1,3}(\\.\\d{1,3}){3}", "<ip>")
                            .replaceAll("[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){2,7}", "<ip>");
                    sb.append(", body: ").append(body.replace('\n', ' '));
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (!(context instanceof Activity) || update == null) {
            return false;
        }
        if (((Activity) context).isFinishing() || ((Activity) context).isDestroyed()) {
            return false; // a check that waited for the tunnel can outlive the activity
        }
        AndroidUtilities.runOnUIThread(() -> {
            Activity activity = (Activity) context;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.AppName) + " " + update.version);
            String changelog = update.changelog;
            if (changelog != null && changelog.length() > 1500) {
                changelog = changelog.substring(0, 1500) + "…";
            }
            builder.setMessage(TextUtils.isEmpty(changelog) ? LocaleController.getString(R.string.VpnUpdateAvailable) : changelog);
            builder.setPositiveButton(LocaleController.getString(R.string.Update), (dialog, which) -> startDownloadAndInstall(activity));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        });
        return true;
    }

    private void startDownloadAndInstall(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ApplicationLoader.getApplicationId())));
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }
        final String url = pendingApkUrl;
        if (url == null || downloading) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progressDialog.setMessage(LocaleController.getString(R.string.VpnUpdateDownloading));
        progressDialog.setCanCancel(true);
        // Don't let a stray tap on the dimmed area outside the dialog cancel the download (that fired
        // onCancel -> downloading=false and threw away the partial APK). Back button still cancels.
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setOnCancelListener(d -> downloading = false);
        progressDialog.show();
        downloading = true;
        downloadProgress = 0;
        Utilities.globalQueue.postRunnable(() -> {
            File out = null;
            try {
                out = downloadApk(url, progressDialog);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File file = out;
            downloading = false;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignored) {
                }
                if (file != null && file.exists()) {
                    downloadedFile = file;
                    installApk(activity, file);
                }
            });
        });
    }

    private File downloadApk(String urlStr, AlertDialog progressDialog) throws Exception {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "updates");
        dir.mkdirs();
        File out = new File(dir, "pelegram-update.apk");
        HttpURLConnection conn = (HttpURLConnection) org.telegram.messenger.vpn.WebViewProxyController.openConnection(new URL(urlStr));
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "pelegram-updater");
        conn.connect();
        int total = conn.getContentLength();
        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[32768];
            int read;
            long done = 0;
            while (downloading && (read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                done += read;
                if (total > 0) {
                    downloadProgress = (float) done / total;
                    final int percent = (int) (downloadProgress * 100);
                    AndroidUtilities.runOnUIThread(() -> progressDialog.setProgress(percent));
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!downloading) {
            out.delete();
            return null;
        }
        return out;
    }

    private void installApk(Activity activity, File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = AndroidUtilities.getFileProviderUri(file);
            } else {
                uri = Uri.fromFile(file);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- version helpers (semantic "a.b.c") ----

    private static int compareVersions(String a, String b) {
        int[] pa = parseVer(a), pb = parseVer(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return Integer.compare(pa[i], pb[i]);
            }
        }
        return 0;
    }

    private static int versionToCode(String v) {
        int[] p = parseVer(v);
        return p[0] * 1000000 + p[1] * 1000 + p[2];
    }

    private static int[] parseVer(String v) {
        int[] r = new int[]{0, 0, 0};
        if (v == null) {
            return r;
        }
        String[] parts = v.trim().split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                r[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    private static String httpGetString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) org.telegram.messenger.vpn.WebViewProxyController.openConnection(new URL(urlStr));
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "pelegram-updater");
        conn.connect();
        int code = conn.getResponseCode();
        if (code >= 400) {
            // without this, Android's HttpURLConnection reports any of these as FileNotFoundException(url)
            String detail = describeFailure(conn);
            conn.disconnect();
            throw new IOException("HTTP " + code + detail);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
        } finally {
            conn.disconnect();
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
