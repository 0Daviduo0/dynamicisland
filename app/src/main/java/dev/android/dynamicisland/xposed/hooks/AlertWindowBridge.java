package dev.android.dynamicisland.xposed.hooks;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.island.source.AlertBridge;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.util.ArrayList;
import java.util.List;

/**
 * App side of {@link AlertBridge}: makes one pop-up window invisible, tells System UI what it shows, presses its
 * buttons on command and puts it back when System UI does not answer. One alert at a time per process; main thread.
 */
final class AlertWindowBridge {
    /** System UI must answer within this, or the stock pop-up comes back. */
    private static final long ACK_TIMEOUT_MS = 900;
    /** Wait for the pop-up's first layout before its texts and buttons are read. */
    private static final long READ_DELAY_MS = 150;
    /** Invisible, untouchable and no dimming of the screen behind (a dim layer ignores the window's alpha). */
    private static final int HIDING = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    private static final int DIM = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View root;
    private static String source;
    private static int id;
    private static boolean acked, hidden, removalHooked, receiverOn;
    private static float savedAlpha;
    private static int savedFlags;
    private static CharSequence givenTitle, givenText;
    private static final List<View> buttons = new ArrayList<>();
    private static final Runnable restore = () -> showStock(true);

    private AlertWindowBridge() {
    }

    /** Before the window is added: it is added already invisible, so it never flashes. */
    static void beforeAdd(String src, View view, WindowManager.LayoutParams lp) {
        start(src, view, null, null);
        hide(lp);
    }

    /** A window already on screen (a dialog's decor view). */
    static void adopt(String src, View decor, CharSequence title, CharSequence text) {
        if (!(decor.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        start(src, decor, title, text);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) decor.getLayoutParams();
        hide(lp);
        update(decor, lp);
    }

    private static void start(String src, View view, CharSequence title, CharSequence text) {
        installRemovalHook();
        ensureReceiver();
        if (root != null && root != view) gone();
        root = view;
        source = src;
        id++;
        acked = false;
        givenTitle = title;
        givenText = text;
        final int session = id;
        MAIN.postDelayed(() -> { if (session == id && root != null) publish(); }, READ_DELAY_MS);
        MAIN.removeCallbacks(restore);
        MAIN.postDelayed(restore, READ_DELAY_MS + ACK_TIMEOUT_MS);
    }

    private static void hide(WindowManager.LayoutParams lp) {
        savedAlpha = lp.alpha;
        savedFlags = lp.flags & (HIDING | DIM);
        lp.alpha = 0f;
        lp.flags = (lp.flags | HIDING) & ~DIM;
        hidden = true;
    }

    /** Back to the stock pop-up; {@code timedOut}: System UI never took it, so it forgets the card too. */
    private static void showStock(boolean timedOut) {
        View view = root;
        if (view == null || !hidden || (timedOut && acked)) return;
        hidden = false;
        if (view.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
            lp.alpha = savedAlpha;
            lp.flags = (lp.flags & ~(HIDING | DIM)) | savedFlags;
            update(view, lp);
        }
        XLog.i("alert " + source + ": stock pop-up shown again" + (timedOut ? " (no answer from System UI)" : ""));
        if (timedOut) send(new Intent(AlertBridge.ACTION_ALERT).putExtra(AlertBridge.EXTRA_GONE, true));
    }

    private static void update(View view, WindowManager.LayoutParams lp) {
        try {
            if (view.isAttachedToWindow()) view.getContext().getSystemService(WindowManager.class).updateViewLayout(view, lp);
        } catch (Throwable t) {
            XLog.once("AlertWindowBridge.update", t);
        }
    }

    private static void publish() {
        buttons.clear();
        List<CharSequence> texts = new ArrayList<>();
        scan(root, texts);
        ArrayList<String> labels = new ArrayList<>();
        for (View b : buttons) labels.add(label(b));
        // running clocks ("-00:00:03") are not labels: the island counts the time itself
        List<CharSequence> labels2 = new ArrayList<>();
        for (CharSequence t : texts) if (!AlertBridge.isClockText(t)) labels2.add(t);
        CharSequence title = givenTitle != null ? givenTitle : labels2.isEmpty() ? null : labels2.get(0);
        CharSequence text = givenText != null ? givenText : labels2.size() > 1 ? labels2.get(1) : null;
        send(new Intent(AlertBridge.ACTION_ALERT)
                .putExtra(AlertBridge.EXTRA_TITLE, title == null ? null : title.toString())
                .putExtra(AlertBridge.EXTRA_TEXT, text == null ? null : text.toString())
                .putStringArrayListExtra(AlertBridge.EXTRA_BUTTONS, labels));
        XLog.i("alert " + source + ": sent to System UI with " + labels);
    }

    /** Collects plain texts in order, and every clickable view that carries a label (not looking inside it). */
    private static void scan(View v, List<CharSequence> texts) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v.isClickable() && v != root && label(v) != null) {
            buttons.add(v);
            return;
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().trim().length() > 0) texts.add(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scan(g.getChildAt(i), texts);
        }
    }

    private static String label(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String inner = label(g.getChildAt(i));
                if (inner != null) return inner;
            }
        }
        CharSequence d = v.getContentDescription();
        return d == null || d.toString().trim().isEmpty() ? null : d.toString().trim();
    }

    private static void gone() {
        if (root == null) return;
        MAIN.removeCallbacks(restore);
        send(new Intent(AlertBridge.ACTION_ALERT).putExtra(AlertBridge.EXTRA_GONE, true));
        root = null;
        hidden = false;
        buttons.clear();
    }

    private static void send(Intent intent) {
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        intent.setPackage("com.android.systemui")
                .putExtra(AlertBridge.EXTRA_SOURCE, source)
                .putExtra(AlertBridge.EXTRA_ID, id)
                .putExtra(AlertBridge.EXTRA_PACKAGE, app.getPackageName());
        try {
            app.sendBroadcast(intent, AlertBridge.PERMISSION);
        } catch (Throwable t) {
            XLog.once("AlertWindowBridge.send", t);
        }
    }

    private static void ensureReceiver() {
        if (receiverOn) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        receiverOn = true;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    onCommand(intent);
                } catch (Throwable t) {
                    XLog.once("AlertWindowBridge.command", t);
                }
            }
        }, new IntentFilter(AlertBridge.ACTION_COMMAND), AlertBridge.PERMISSION, MAIN, Context.RECEIVER_EXPORTED);
    }

    private static void onCommand(Intent intent) {
        XLog.i("alert " + source + ": command " + intent.getStringExtra(AlertBridge.EXTRA_COMMAND) + " for " + intent.getIntExtra(AlertBridge.EXTRA_ID, -1) + " (current " + id + ", " + buttons.size() + " buttons)");
        if (root == null || intent.getIntExtra(AlertBridge.EXTRA_ID, -1) != id
                || !String.valueOf(source).equals(intent.getStringExtra(AlertBridge.EXTRA_SOURCE))) return;
        String cmd = intent.getStringExtra(AlertBridge.EXTRA_COMMAND);
        if (AlertBridge.CMD_ACK.equals(cmd)) {
            acked = true;
            MAIN.removeCallbacks(restore);
        } else if (AlertBridge.CMD_CLICK.equals(cmd)) {
            int index = intent.getIntExtra(AlertBridge.EXTRA_INDEX, -1);
            if (index >= 0 && index < buttons.size()) buttons.get(index).performClick();
        } else if (AlertBridge.CMD_RESTORE.equals(cmd)) {
            showStock(false);
        }
    }

    /** Tells System UI when the pop-up's window goes, however the app removes it. */
    private static void installRemovalHook() {
        if (removalHooked) return;
        removalHooked = true;
        Class<?> global = XposedHelpers.findClass("android.view.WindowManagerGlobal", null);
        XposedBridge.hookAllMethods(global, "removeView", SafeHook.before("alert window removed", p -> {
            if (p.args.length > 0 && p.args[0] != null && p.args[0] == root) gone();
        }));
        // the app may re-lay its pop-up out with fresh parameters (Clock does when it shrinks it over an app):
        // those must stay invisible too while the island shows it
        XposedBridge.hookAllMethods(global, "updateViewLayout", SafeHook.before("alert window relaid", p -> {
            if (p.args.length < 2 || p.args[0] == null || p.args[0] != root || !hidden
                    || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
            if (lp.alpha != 0f) savedAlpha = lp.alpha;
            if ((lp.flags & DIM) != 0) savedFlags |= DIM;
            lp.alpha = 0f;
            lp.flags = (lp.flags | HIDING) & ~DIM;
        }));
    }
}
