package dev.android.dynamicisland.xposed.hooks;

import android.app.KeyguardManager;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.island.source.AlertBridge;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Runs in Samsung Clock. When a timer ends Clock puts up its own window at the top of the screen (a
 * {@code KEYGUARD_DIALOG} with the timer's name, the time since it ended and Stop / Restart). With the phone
 * unlocked the island takes it over: the window is added invisible and the island opens a card with the same
 * buttons ({@link AlertWindowBridge}). On the lock screen Clock's window stays as it is.
 * Opt-in: it runs only when the user adds Clock to the module's LSPosed scope.
 */
public final class ClockAlertHooks {
    public static final String PACKAGE = "com.sec.android.app.clockpackage";
    /** Samsung Auto Switch puts its earbuds pop-ups (switched away, connected, Reconnect) up the same way. */
    public static final String MCFDS_PACKAGE = "com.samsung.android.mcfds";

    private ClockAlertHooks() {
    }

    public static void install(ClassLoader loader) {
        install(AlertBridge.SOURCE_TIMER);
    }

    /** Takes over every {@code KEYGUARD_DIALOG} window of this process, reported as {@code source}. */
    public static void install(String source) {
        Class<?> global = XposedHelpers.findClass("android.view.WindowManagerGlobal", null);
        int n = XposedBridge.hookAllMethods(global, "addView", SafeHook.before("clock alert window", p -> {
            if (p.args.length < 2 || !(p.args[0] instanceof View) || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
            if (lp.type != WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG) return;
            if (AlertBridge.SOURCE_AUTOSWITCH.equals(source)) {
                // Auto Switch's notice only informs: never dim the screen or swallow touches around it
                lp.flags = (lp.flags & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.dimAmount = 0f;
            }
            View view = (View) p.args[0];
            KeyguardManager km = view.getContext().getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) return;
            AlertWindowBridge.beforeAdd(source, view, lp);
        })).size();
        XLog.i(source + " alert hooks: " + n);
    }
}
