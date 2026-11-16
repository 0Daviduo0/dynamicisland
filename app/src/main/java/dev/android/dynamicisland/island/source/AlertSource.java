package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.xposed.XLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System UI side of {@link AlertBridge}: the timer that ended (Samsung Clock) and earbuds asking to connect
 * (EasySetup) become island cards with the pop-up's own buttons, opened at once since they wait for an answer.
 */
public final class AlertSource {
    /** How long the card stays open by itself: the alert waits for an answer, so long. */
    public static final long OPEN_MS = 60_000;
    private static final int TIMER_ORANGE = 0xFFFF9F0A;

    private final Context ctx;
    private final AppLook apps;
    private final Handler main;
    private final Runnable onChange;
    private final Map<String, Alert> alerts = new LinkedHashMap<>();
    private final Map<String, Drawable> icons = new java.util.HashMap<>();
    private String pendingOpen;

    private static final class Alert {
        String source, pkg, title, text;
        int id;
        long startedAt;
        List<String> buttons;
        /** The timer island's symbol, taken from Clock's own timer item while it was there. */
        Drawable glyph;
    }

    public AlertSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.apps = AppLook.shared(ctx);
        this.main = main;
        this.onChange = onChange;
    }

    public void start() {
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    try {
                        onAlert(intent);
                    } catch (Throwable t) {
                        XLog.once("AlertSource.onAlert", t);
                    }
                }
            }, new IntentFilter(AlertBridge.ACTION_ALERT), AlertBridge.PERMISSION, main, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("alert receiver", t);
        }
    }

    private void onAlert(Intent intent) {
        String source = intent.getStringExtra(AlertBridge.EXTRA_SOURCE);
        if (source == null) return;
        String key = "alert:" + source;
        if (intent.getBooleanExtra(AlertBridge.EXTRA_GONE, false)) {
            Alert old = alerts.get(key);
            if (old != null && old.id == intent.getIntExtra(AlertBridge.EXTRA_ID, -1)) {
                alerts.remove(key);
                if (key.equals(pendingOpen)) pendingOpen = null;
                onChange.run();
            }
            return;
        }
        Alert a = new Alert();
        a.source = source;
        a.id = intent.getIntExtra(AlertBridge.EXTRA_ID, -1);
        a.pkg = intent.getStringExtra(AlertBridge.EXTRA_PACKAGE);
        a.title = intent.getStringExtra(AlertBridge.EXTRA_TITLE);
        a.text = intent.getStringExtra(AlertBridge.EXTRA_TEXT);
        ArrayList<String> labels = intent.getStringArrayListExtra(AlertBridge.EXTRA_BUTTONS);
        a.buttons = labels == null ? new ArrayList<>() : labels;
        Alert old = alerts.get(key);
        a.startedAt = old != null && old.id == a.id ? old.startedAt : System.currentTimeMillis();
        if (old != null) a.glyph = old.glyph;
        alerts.put(key, a);
        pendingOpen = key;
        XLog.i("alert " + source + " from " + a.pkg + ": " + a.buttons.size() + " buttons");
        onChange.run();
    }

    /** Key of an alert card that should open now (once). */
    public String takePendingOpen() {
        String key = pendingOpen;
        pendingOpen = null;
        return key;
    }

    /** The card is on screen: the app may keep its own pop-up hidden. */
    public void opened(String key) {
        command(alerts.get(key), AlertBridge.CMD_ACK, -1);
    }

    /** The card could not open: the app shows its own pop-up again. */
    public void failed(String key) {
        command(alerts.get(key), AlertBridge.CMD_RESTORE, -1);
    }

    public void collect(List<IslandItem> out) {
        for (Map.Entry<String, Alert> e : alerts.entrySet()) {
            Alert a = e.getValue();
            if (AlertBridge.SOURCE_TIMER.equals(a.source)) {
                // the ended timer replaces Clock's own timer island, which would show the same timer twice
                for (IslandItem it : out) {
                    if (it.clockKind == IslandItem.CLOCK_TIMER && it.icon != null && a.pkg != null && a.pkg.equals(it.packageName)) {
                        a.glyph = it.icon; // Clock's own timer symbol, kept for the ended timer's card
                        break;
                    }
                }
                out.removeIf(it -> it.clockKind == IslandItem.CLOCK_TIMER && a.pkg != null && a.pkg.equals(it.packageName));
            }
            if (AlertBridge.SOURCE_AUTOSWITCH.equals(a.source)) {
                // the earbuds' own picture rather than the Auto Switch service's icon
                for (IslandItem it : out) {
                    if (it.key.startsWith("event:buds:") && it.icon != null) { a.glyph = it.icon; break; }
                }
            }
            out.add(item(e.getKey(), a));
        }
    }

    private IslandItem item(String key, Alert a) {
        IslandItem it = new IslandItem(key);
        it.kind = IslandItem.Kind.ALARM;
        it.packageName = a.pkg;
        it.appName = appName(a.pkg);
        it.icon = icon(a.pkg);
        it.title = a.title != null ? a.title : AlertBridge.SOURCE_TIMER.equals(a.source) ? "Timer" : it.appName;
        it.postTime = a.startedAt;
        if (AlertBridge.SOURCE_TIMER.equals(a.source)) {
            // the Clock card, counting up from the end the way Clock's pop-up does
            it.clockKind = IslandItem.CLOCK_TIMER;
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoCountDown = true;
            it.chronoBase = a.startedAt;
            it.accent = TIMER_ORANGE;
            if (a.glyph != null) it.icon = a.glyph;
        } else {
            it.subtitle = a.text;
            if (a.glyph != null) it.icon = a.glyph;
        }
        for (int i = 0; i < a.buttons.size(); i++) {
            final int index = i;
            IslandItem.Action action = new IslandItem.Action(a.buttons.get(i), null, null,
                    () -> command(a, AlertBridge.CMD_CLICK, index), false);
            // the pop-up's last button is its main one (Connect, Restart)
            action.affirmative = !AlertBridge.SOURCE_TIMER.equals(a.source) && i == a.buttons.size() - 1;
            if (AlertBridge.SOURCE_TIMER.equals(a.source)) {
                // Clock's pop-up: Stop, then Restart
                action.glyph = i == 0 ? dev.android.dynamicisland.island.graphics.Glyphs.STOP
                        : dev.android.dynamicisland.island.graphics.Glyphs.RESTART;
            }
            it.actions.add(action);
        }
        return it;
    }

    private void command(Alert a, String cmd, int index) {
        if (a == null || a.pkg == null) return;
        try {
            Intent intent = new Intent(AlertBridge.ACTION_COMMAND).setPackage(a.pkg)
                    .putExtra(AlertBridge.EXTRA_SOURCE, a.source).putExtra(AlertBridge.EXTRA_ID, a.id)
                    .putExtra(AlertBridge.EXTRA_COMMAND, cmd).putExtra(AlertBridge.EXTRA_INDEX, index);
            ctx.sendBroadcast(intent, AlertBridge.PERMISSION);
            if (!AlertBridge.CMD_ACK.equals(cmd)) XLog.i("alert " + a.source + ": " + cmd + " " + index + " sent to " + a.pkg);
        } catch (Throwable t) {
            XLog.once("AlertSource.command", t);
        }
    }

    private CharSequence appName(String pkg) {
        String label = apps.label(pkg);
        return pkg.equals(label) ? null : label; // unknown package: no name, as before
    }

    private Drawable icon(String pkg) {
        if (pkg == null) return null;
        Drawable d = icons.get(pkg);
        if (d == null) {
            try {
                d = ctx.getPackageManager().getApplicationIcon(pkg);
                icons.put(pkg, d);
            } catch (Throwable ignored) {
                // no icon: the card draws without it
            }
        }
        return d;
    }
}
