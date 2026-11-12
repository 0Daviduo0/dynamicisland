package dev.android.dynamicisland.xposed.hooks;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.island.source.QuickShareState;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.io.File;

/**
 * Runs inside Quick Share. Its "Sending files" notification carries no progress bar, so while one is up the
 * byte counters Quick Share keeps in its own Room database ({@code share_live.db}, table {@code devices}) are read
 * and sent to System UI. Table and column names are Room's stable SQL names, not obfuscated code.
 */
final class QuickShareSendWatch {
    private static final String DB = "share_live.db";
    private static final long POLL_MS = 400;
    /** Counters standing still (waiting for the recipient, slow link): poll less, up to this. */
    private static final long POLL_MAX_MS = 3000;
    private static final long MAX_WATCH_MS = 30 * 60 * 1000L;

    private static Handler worker;
    private static Context app;
    private static String shareId;
    private static long startedAt;
    private static SQLiteDatabase db;
    private static String lastSent;
    private static long interval = POLL_MS;
    /** Notification id of the watched share, so cancelling it (with no end notification) stops the watch. */
    private static volatile int watchedId = Integer.MIN_VALUE;

    private QuickShareSendWatch() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(NotificationManager.class, "notify", String.class, int.class, Notification.class,
                SafeHook.after("quick share send notify", param -> {
                    Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    seen(ctx, (int) param.args[1], (Notification) param.args[2]);
                }));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel", String.class, int.class,
                SafeHook.after("quick share send cancel", param -> cancelled((int) param.args[1])));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAll",
                SafeHook.after("quick share send cancel all", param -> cancelled(watchedId)));
        XposedHelpers.findAndHookMethod(Service.class, "startForeground", int.class, Notification.class,
                SafeHook.after("quick share send foreground", param -> seen((Context) param.thisObject, (int) param.args[0], (Notification) param.args[1])));
        try {
            XposedHelpers.findAndHookMethod(Service.class, "startForeground", int.class, Notification.class, int.class,
                    SafeHook.after("quick share send foreground typed", param -> seen((Context) param.thisObject, (int) param.args[0], (Notification) param.args[1])));
        } catch (Throwable ignored) {
            // older Android: two-argument form only
        }
    }

    /** A notification of the outgoing channel: progress starts the watch, anything else for the share ends it. */
    /** The watched notification went away without an end notification: last read, then stop. */
    private static void cancelled(int id) {
        if (id != watchedId || worker == null) return;
        worker.post(() -> {
            poll();
            stop();
        });
    }

    private static void seen(Context ctx, int notificationId, Notification n) {
        if (ctx == null || n == null || n.extras == null || !QuickShareState.SEND_CHANNEL.equals(n.getChannelId())) return;
        String id = n.extras.getString(QuickShareState.EXTRA_SHARE_ID);
        if (id == null) return;
        Handler h = worker();
        if ("progress".equals(n.category)) {
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            h.post(() -> start(appCtx, id, notificationId));
        } else {
            h.post(() -> {
                if (id.equals(shareId)) {
                    poll(); // the last counters, then stop
                    stop();
                }
            });
        }
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("IslandQuickShareSend");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (shareId == null) return;
            if (SystemClock.uptimeMillis() - startedAt > MAX_WATCH_MS) {
                stop();
                return;
            }
            // standing counters: back off; moving again: back to the fast pace
            interval = poll() ? POLL_MS : Math.min(POLL_MAX_MS, interval * 2);
            worker.postDelayed(this, interval);
        }
    };

    private static void start(Context ctx, String id, int notificationId) {
        watchedId = notificationId;
        if (id.equals(shareId)) return;
        stop();
        watchedId = notificationId;
        interval = POLL_MS;
        app = ctx;
        shareId = id;
        startedAt = SystemClock.uptimeMillis();
        lastSent = null;
        worker.post(TICK);
    }

    private static void stop() {
        shareId = null;
        watchedId = Integer.MIN_VALUE;
        worker.removeCallbacks(TICK);
        if (db != null) {
            try { db.close(); } catch (Throwable ignored) { }
            db = null;
        }
    }

    /** Reads the counters; true when something new was sent to System UI. */
    private static boolean poll() {
        if (shareId == null || app == null) return false;
        try {
            if (db == null) {
                File path = app.getDatabasePath(DB);
                if (!path.exists()) return false;
                db = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            }
            long total = 0, done = 0;
            int files = 0;
            String device = null;
            try (Cursor c = db.rawQuery("SELECT device_name, display_name, file_total_size, file_on_progress_size, file_total_count "
                    + "FROM devices WHERE share_id = ?", new String[]{shareId})) {
                while (c.moveToNext()) {
                    if (device == null) device = !c.isNull(1) && !c.getString(1).isEmpty() ? c.getString(1) : c.getString(0);
                    total += c.getLong(2);
                    done += c.getLong(3);
                    files = Math.max(files, c.getInt(4));
                }
            }
            String file = null;
            try (Cursor c = db.rawQuery("SELECT c.file_name FROM content c JOIN shares s ON c.box_id = s.box_id WHERE s.id = ? LIMIT 1",
                    new String[]{shareId})) {
                if (c.moveToNext()) file = c.getString(0);
            } catch (Throwable ignored) {
                // older schema: no file name, the island shows the count
            }
            float progress = total > 0 && done > 0 ? Math.min(1f, done / (float) total) : -1f;
            // whole percents: the island shows no finer step, so finer changes would only redraw it
            String key = shareId + "|" + Math.round(progress * 100) + "|" + device + "|" + file + "|" + files;
            if (key.equals(lastSent)) return false;
            lastSent = key;
            Intent out = new Intent(QuickShareState.ACTION_STATE).setPackage("com.android.systemui")
                    .putExtra(QuickShareState.EXTRA_SEND_SHARE, shareId)
                    .putExtra(QuickShareState.EXTRA_SEND_PROGRESS, progress)
                    .putExtra(QuickShareState.EXTRA_SEND_DEVICE, device)
                    .putExtra(QuickShareState.EXTRA_SEND_FILE, file)
                    .putExtra(QuickShareState.EXTRA_SEND_COUNT, files);
            app.sendBroadcast(out);
            return true;
        } catch (Throwable t) {
            XLog.once("QuickShareSendWatch.poll", t);
            stop();
            return false;
        }
    }
}
