package dev.android.dynamicisland.xposed.hooks;

import android.graphics.RectF;
import android.os.SystemClock;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Runs in One UI Home. When an app closes into the island, SystemUI hands the launcher the camera hole as the
 * target (see {@code ExitAnimationBridge}), but the launcher's close spring is tuned for flying into an icon: it fades
 * the window out halfway and lets the icon take over. With no icon to take over, the window simply vanished on its
 * way up. For those closes only, the window here stays opaque all the way and its corners round into a circle
 * (a capsule when the target is the pill), so it visibly drops into the camera or under the island.
 */
public final class LauncherHooks {
    public static final String PACKAGE = "com.sec.android.app.launcher";

    private static final String DELEGATE = "com.honeyspace.transition.delegate.AppCloseAnimationDelegate";
    private static final String CHIP_VIEW = "com.honeyspace.transition.ongoingchip.FakeOnGoingChipView";
    private static final String SPRING = "com.honeyspace.transition.anim.floating.calculator.SpringCalculator";

    /** A chip close not followed by its animation within this time is forgotten. */
    private static final long CHIP_CLOSE_WINDOW_MS = 2000;
    /** Progress from which the corners grow toward a full circle. */
    private static final float ROUND_FROM = 0.35f;

    private static volatile long chipCloseAt;

    private LauncherHooks() {
    }

    public static void install(ClassLoader cl) {
        int target = Refl.hookAll(cl, DELEGATE, "findTargetView", SafeHook.after("launcher close target", p -> {
            Object view = Refl.get(p.thisObject, "targetView");
            boolean chip = view != null && CHIP_VIEW.equals(view.getClass().getName());
            chipCloseAt = chip ? SystemClock.uptimeMillis() : 0L;
        }));
        int spring = Refl.hookAll(cl, SPRING, "calculate", SafeHook.after("launcher close spring", LauncherHooks::intoHole));
        XLog.i("launcher hooks: target=" + target + " spring=" + spring);
    }

    private static void intoHole(de.robv.android.xposed.XC_MethodHook.MethodHookParam p) {
        long at = chipCloseAt;
        if (at == 0L || SystemClock.uptimeMillis() - at > CHIP_CLOSE_WINDOW_MS) {
            return;
        }
        Object input = Refl.call(p.thisObject, "getInput");
        Object output = Refl.call(p.thisObject, "getOutput");
        if (input == null || output == null || Boolean.TRUE.equals(Refl.call(input, "isForward"))) {
            return;
        }
        float progress = p.args.length > 1 && p.args[1] instanceof Float ? (Float) p.args[1] : 0.0f;
        // never faded: the window drops into the hole (or under the pill) whole, and the island then opens
        Refl.call(output, "setWindowAlpha", 1.0f);
        Refl.call(output, "setFgAlpha", 0.0f);

        // the radius is in window pixels, before the window is scaled down to the animated rect
        Object rectObj = Refl.call(output, "getCurrentRectF");
        Object scaleObj = Refl.call(output, "getScale");
        Object radiusObj = Refl.call(output, "getRadius");
        if (rectObj instanceof RectF && scaleObj instanceof Float && radiusObj instanceof Float && (Float) scaleObj > 0.0f) {
            RectF rect = (RectF) rectObj;
            float circle = Math.min(rect.width(), rect.height()) / 2.0f / (Float) scaleObj;
            float t = range(progress, ROUND_FROM, 1.0f);
            float radius = (Float) radiusObj;
            Refl.call(output, "setRadius", radius + (Math.max(radius, circle) - radius) * t);
        }
    }

    private static float range(float v, float from, float to) {
        return Math.max(0.0f, Math.min(1.0f, (v - from) / (to - from)));
    }
}
