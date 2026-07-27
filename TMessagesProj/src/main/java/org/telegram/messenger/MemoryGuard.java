package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.StatFs;
import android.os.SystemClock;

import org.telegram.messenger.vpn.SingBoxManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * pelegram: heap-pressure guard and out-of-memory reporter.
 *
 * Why this exists: opening a media-heavy channel while the embedded tunnel is slow could exhaust the
 * heap and restart the app. Chunk requests are issued per file-load operation and several operations
 * run in parallel per datacenter, so when the tunnel throttles them the in-flight buffers pile up
 * faster than they drain. Stock Telegram never reacts to Android's memory-pressure callbacks, so
 * nothing sheds cached bitmaps or slows the download fan-out before the process dies.
 *
 * Three parts:
 *   1. {@link #onTrimMemory(int)} / {@link #onLowMemory()} - shed the image caches when the system
 *      says memory is tight, and remember that we are under pressure for a short window.
 *   2. {@link #limitConcurrentDownloads(int, boolean)} - called by FileLoaderPriorityQueue. Caps how
 *      many file-load operations one queue starts at once while the tunnel is up or while memory is
 *      tight. Never returns less than 1, so a queue always keeps one operation running and therefore
 *      always produces the event that re-checks the queue once the pressure passes.
 *   3. {@link #install()} - on an OutOfMemoryError, write a small text report next to the logs (so
 *      "Send Logs" picks it up) and, when logging is enabled, an .hprof heap dump to pin the exact
 *      consumer. The report is what turns the next crash from a guess into a measurement.
 */
public class MemoryGuard {

    /** Fraction of the max heap above which we consider the process to be under pressure. */
    private static final float PRESSURE_HEAP_RATIO = 0.85f;
    /** How long a trim callback keeps us in the throttled state. */
    private static final long TRIM_PRESSURE_WINDOW = 60 * 1000L;
    /** Max operations per queue while the tunnel carries the traffic. */
    private static final int TUNNEL_MAX_SMALL_OPERATIONS = 3;
    private static final int TUNNEL_MAX_LARGE_OPERATIONS = 1;
    /** How many oom reports / heap dumps to keep around. */
    private static final int MAX_KEPT_REPORTS = 5;
    private static final int MAX_KEPT_HPROFS = 1;

    private static volatile boolean installed;
    private static volatile long pressureUntil;
    private static volatile int lastTrimLevel = -1;

    private MemoryGuard() {
    }

    // ---- install ----

    /** Chains an OOM reporter onto whatever uncaught handler is already installed. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (isOutOfMemory(error)) {
                    writeReport(thread, error);
                }
            } catch (Throwable ignore) {
                // never let the reporter replace the real crash
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static boolean isOutOfMemory(Throwable error) {
        Throwable e = error;
        for (int i = 0; e != null && i < 16; i++) {
            if (e instanceof OutOfMemoryError) {
                return true;
            }
            if (e.getCause() == e) {
                break;
            }
            e = e.getCause();
        }
        return false;
    }

    // ---- memory pressure ----

    public static void onTrimMemory(int level) {
        lastTrimLevel = level;
        // The TRIM_MEMORY_* constants are not ordered by severity (UI_HIDDEN = 20 sits between
        // RUNNING_CRITICAL = 15 and BACKGROUND = 40), so match them out instead of comparing.
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                enterPressure("trim level " + level);
                break;
            default:
                // RUNNING_MODERATE / UI_HIDDEN / BACKGROUND are routine, not a shortage.
                break;
        }
    }

    public static void onLowMemory() {
        enterPressure("onLowMemory");
    }

    private static void enterPressure(String reason) {
        pressureUntil = SystemClock.elapsedRealtime() + TRIM_PRESSURE_WINDOW;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("MemoryGuard: " + reason + ", " + heapLine() + ", shedding image caches");
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (ApplicationLoader.applicationContext != null) {
                    ImageLoader.getInstance().clearMemory();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** True while the system reported a shortage recently, or the heap is nearly full right now. */
    public static boolean isUnderMemoryPressure() {
        if (SystemClock.elapsedRealtime() < pressureUntil) {
            return true;
        }
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0) {
            return false;
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        return used > max * PRESSURE_HEAP_RATIO;
    }

    private static boolean isTunnelActive() {
        try {
            return SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Caps the number of file-load operations a single queue runs in parallel.
     *
     * Everything shares one tunnel, so extra parallel operations do not buy throughput - they only
     * multiply the buffers waiting on a link that is already the bottleneck. Under real memory
     * pressure we go down to a single operation. The floor of 1 matters: a queue with zero running
     * operations gets no completion event, and a completion event is what re-runs this check.
     */
    public static int limitConcurrentDownloads(int max, boolean largeFiles) {
        int limit = max;
        if (isTunnelActive()) {
            limit = Math.min(limit, largeFiles ? TUNNEL_MAX_LARGE_OPERATIONS : TUNNEL_MAX_SMALL_OPERATIONS);
        }
        if (isUnderMemoryPressure()) {
            limit = Math.min(limit, 1);
        }
        return Math.max(1, limit);
    }

    // ---- oom report ----

    private static String heapLine() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return "heap " + AndroidUtilities.formatFileSize(used) + " / " + AndroidUtilities.formatFileSize(max)
                + " (native " + AndroidUtilities.formatFileSize(Debug.getNativeHeapAllocatedSize()) + ")";
    }

    private static void writeReport(Thread thread, Throwable error) {
        File dir = AndroidUtilities.getLogsDir();
        if (dir == null) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("pelegram out-of-memory report\n");
        sb.append("time: ").append(stamp).append('\n');
        sb.append("version: ").append(BuildVars.BUILD_VERSION_STRING).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (android ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("thread: ").append(thread != null ? thread.getName() : "?").append('\n');
        sb.append('\n');

        Runtime runtime = Runtime.getRuntime();
        sb.append("java heap used: ").append(runtime.totalMemory() - runtime.freeMemory()).append('\n');
        sb.append("java heap total: ").append(runtime.totalMemory()).append('\n');
        sb.append("java heap max: ").append(runtime.maxMemory()).append('\n');
        sb.append("native heap allocated: ").append(Debug.getNativeHeapAllocatedSize()).append('\n');
        sb.append("native heap size: ").append(Debug.getNativeHeapSize()).append('\n');
        try {
            ActivityManager am = (ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            sb.append("memory class: ").append(am.getMemoryClass())
                    .append(" (large ").append(am.getLargeMemoryClass()).append(")\n");
            sb.append("system avail: ").append(info.availMem).append(" of ").append(info.totalMem)
                    .append(", low=").append(info.lowMemory).append(", threshold=").append(info.threshold).append('\n');
        } catch (Throwable ignore) {
        }
        sb.append("last trim level: ").append(lastTrimLevel).append('\n');
        sb.append('\n');

        sb.append("tunnel state: ").append(safeTunnelState()).append('\n');
        sb.append("download queues: ").append(FileLoaderPriorityQueue.dumpAllQueues()).append('\n');
        sb.append('\n');

        StringWriter trace = new StringWriter();
        PrintWriter tracePrinter = new PrintWriter(trace);
        error.printStackTrace(tracePrinter);
        tracePrinter.flush();
        sb.append(trace.toString());

        File report = new File(dir, "oom_" + stamp + ".txt");
        try (FileOutputStream out = new FileOutputStream(report)) {
            out.write(sb.toString().getBytes("UTF-8"));
        } catch (Throwable e) {
            return;
        }
        prune(dir, "oom_", ".txt", MAX_KEPT_REPORTS);
        dumpHprof(stamp);
    }

    private static String safeTunnelState() {
        try {
            SingBoxManager manager = SingBoxManager.getInstance();
            return manager.getState() + " (port " + manager.getLocalPort() + ")";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /**
     * Heap dump, only when logging is enabled and there is room for it. An .hprof is roughly the size
     * of the heap, so it goes to files/oom/ rather than the logs dir - "Send Logs" must not try to
     * zip a few hundred megabytes.
     */
    private static void dumpHprof(String stamp) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        try {
            File base = ApplicationLoader.applicationContext.getExternalFilesDir(null);
            if (base == null) {
                return;
            }
            File dir = new File(base, "oom");
            dir.mkdirs();
            prune(dir, "heap_", ".hprof", 0);
            long needed = Runtime.getRuntime().maxMemory() * 2;
            StatFs stat = new StatFs(dir.getAbsolutePath());
            if (stat.getAvailableBytes() < needed) {
                return;
            }
            Debug.dumpHprofData(new File(dir, "heap_" + stamp + ".hprof").getAbsolutePath());
            prune(dir, "heap_", ".hprof", MAX_KEPT_HPROFS);
        } catch (Throwable ignore) {
        }
    }

    /** Keeps the newest {@code keep} files matching prefix/suffix, deletes the rest. */
    private static void prune(File dir, String prefix, String suffix, int keep) {
        try {
            File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(suffix));
            if (files == null || files.length <= keep) {
                return;
            }
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < files.length; i++) {
                files[i].delete();
            }
        } catch (Throwable ignore) {
        }
    }
}
