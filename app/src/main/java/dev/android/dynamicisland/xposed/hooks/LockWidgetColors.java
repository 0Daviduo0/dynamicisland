package dev.android.dynamicisland.xposed.hooks;

import android.os.SystemClock;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Skips the lock screen widgets' repeated clock-color refresh. During the AOD / lock screen animation the
 * AOD plugin's {@code KeyguardClockProvider} posts the clock date color on every frame, and each post makes
 * {@code WidgetColorManager} recolor every widget view and recompute the wallpaper area color, the widget
 * settings (JSON) and the blur type, only to find that nothing changed. Here a post that repeats the color
 * the widgets already have, within {@link #WINDOW_MS} of the previous one, is skipped; the first post of each
 * screen on / off still runs in full, and any color change always does.
 * The AOD classes live in the plugin's own class loader, so they are hooked when System UI creates it.
 */
final class LockWidgetColors {
    static final String FACTORY = "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";
    static final String COLOR_MANAGER = "com.samsung.android.app.facewidget.complication.WidgetColorManager";
    /** {@code createClockColorObserver$lambda$0(WidgetColorManager, Function0, int)}: the observer of the clock date color. */
    static final String OBSERVER = "createClockColorObserver$lambda$0";
    /** Repeats closer than this to the previous post (one animation) are skipped. */
    static final long WINDOW_MS = 1000;
    private static final int STATS_EVERY = 500;

    /** Last post per color manager: {color, uptime of the last post}. Main thread only. */
    private static final Map<Object, long[]> LAST = new WeakHashMap<>();
    private static final Set<ClassLoader> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static int posts;
    private static int skipped;

    private LockWidgetColors() {
    }

    /**
     * True when this post repeats, within {@link #WINDOW_MS}, the color the manager already applied
     * ({@code current} is its {@code clockColor}). Each post, skipped or not, extends the window.
     */
    static boolean skip(Object manager, int color, int current, long now) {
        long[] last = LAST.get(manager);
        boolean repeat = last != null && last[0] == color && current == color && now - last[1] < WINDOW_MS;
        if (last == null) {
            LAST.put(manager, new long[] {color, now});
        } else {
            last[0] = color;
            last[1] = now;
        }
        return repeat;
    }

    static void reset() {
        LAST.clear();
    }

    static void install(ClassLoader cl) {
        int hooked = Refl.hookAll(cl, FACTORY, "createClassLoader", SafeHook.after("lock widget colors loader", p -> {
            if (p.getResult() instanceof ClassLoader) {
                hookPlugin((ClassLoader) p.getResult());
            }
        }));
        Diagnostics.fact("lock widget colors", hooked > 0 ? "waiting for the AOD plugin" : "plugin factory not found");
    }

    private static void hookPlugin(ClassLoader plugin) {
        synchronized (HOOKED) {
            if (!HOOKED.add(plugin)) {
                return;
            }
        }
        if (!Refl.hasClass(COLOR_MANAGER, plugin)) {
            return;
        }
        Class<?> manager = Refl.findClass(COLOR_MANAGER, plugin);
        if (Refl.field(manager, "clockColor") == null) {
            Diagnostics.fact("lock widget colors", "clockColor field not found, stock behaviour");
            return;
        }
        int hooked = Refl.hookAll(manager, OBSERVER, SafeHook.before("lock widget colors", p -> {
            if (p.args.length != 3 || !(p.args[2] instanceof Integer) || p.args[0] == null) {
                return;
            }
            int color = (Integer) p.args[2];
            int current = Refl.getInt(p.args[0], "clockColor", color + 1);
            posts++;
            if (skip(p.args[0], color, current, SystemClock.uptimeMillis())) {
                skipped++;
                p.setResult(null);
            }
            if (posts % STATS_EVERY == 0) {
                XLog.i("lock widget colors: " + skipped + " of " + posts + " repeated posts skipped");
            }
        }));
        Diagnostics.fact("lock widget colors", hooked > 0 ? "repeated posts skipped" : "observer not found, stock behaviour");
    }
}
