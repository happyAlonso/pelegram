package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ApplicationLoaderImpl extends ApplicationLoader {

    // Fork version = the release tag without the leading "v". Bump on every release.
    private static final String CURRENT_VERSION = "1.2.2";
    // /releases/latest returns only the newest full release (GitHub excludes prereleases and
    // drafts), so the in-app updater never offers a test prerelease - only promoted prod builds.
    private static final String RELEASES_API = "https://api.github.com/repos/happyAlonso/pelegram/releases/latest";

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
        Utilities.globalQueue.postRunnable(() -> {
            BetaUpdate update = null;
            String apkUrl = null;
            try {
                JSONObject root = new JSONObject(httpGetString(RELEASES_API));
                if (!root.optBoolean("prerelease", false) && !root.optBoolean("draft", false)) {
                    String tag = root.optString("tag_name", "");
                    String version = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;
                    String changelog = root.optString("body", "");
                    JSONArray assets = root.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }
                    if (apkUrl != null && compareVersions(version, CURRENT_VERSION) > 0) {
                        update = new BetaUpdate(version, versionToCode(version), changelog);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            pendingUpdate = update;
            pendingApkUrl = update != null ? apkUrl : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (whenDone != null) {
                    whenDone.run();
                }
            });
        });
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (!(context instanceof Activity) || update == null) {
            return false;
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
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
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
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "pelegram-updater");
        conn.connect();
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
