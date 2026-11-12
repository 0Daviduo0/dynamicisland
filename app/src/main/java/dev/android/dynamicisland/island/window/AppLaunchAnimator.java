package dev.android.dynamicisland.island.window;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Opens an app growing out of the island, with System UI's own activity transition animator (the one that expands a
 * tapped notification into its app). The island card grows to full screen, drawn in the island window (kept full
 * screen meanwhile so the shape is not cut), and the app fades in over it as its window grows behind.
 *
 * <p>The launch goes through System UI's {@code ActivityStarter}, captured when it is built, with an
 * {@code ActivityTransitionAnimator.Controller} implemented here as a proxy, because those classes exist only in
 * System UI. Every failure returns false so the caller falls back to a plain start. Main thread only.
 */
public final class AppLaunchAnimator {
    private static final String CONTROLLER = "com.android.systemui.animation.ActivityTransitionAnimator$Controller";
    private static final String STATE = "com.android.systemui.animation.TransitionAnimator$State";
    /** Corner radius cap for a tall card, in dp; a pill uses half its height. */
    private static final float MAX_RADIUS_DP = 32.0f;

    /** The island card's colour, which the expanding shape keeps until the app shows. */
    private static final int CARD_COLOR = 0xFF000000;
    /** Restores the island window if System UI never reports the end of the animation. */
    private static final long SAFETY_RESET_MS = 3000;

    private static volatile Object starter;

    private AppLaunchAnimator() {
    }

    /** Remembers System UI's {@code ActivityStarterImpl}. */
    public static void setStarter(Object activityStarter) {
        starter = activityStarter;
    }

    /**
     * Starts {@code pi} (or else {@code intent}) growing from {@code fromInView}, a rect in {@code anchor}'s coordinates.
     *
     * @return false when the animated start is not available; nothing was started then
     */
    public static boolean launch(View anchor, RectF fromInView, PendingIntent pi, Intent intent, Consumer<Boolean> fullscreen) {
        Object activityStarter = starter;
        if (activityStarter == null || anchor == null || fromInView == null || fromInView.isEmpty()
                || !anchor.isAttachedToWindow() || !(anchor.getRootView() instanceof ViewGroup)) {
            return false;
        }
        try {
            ClassLoader cl = activityStarter.getClass().getClassLoader();
            Class<?> controllerType = Class.forName(CONTROLLER, false, cl);
            Runnable done = new Runnable() {
                boolean ran;

                @Override
                public void run() {
                    if (!ran) {
                        ran = true;
                        anchor.removeCallbacks(this);
                        fullscreen.accept(false);
                    }
                }
            };
            fullscreen.accept(true);
            anchor.postDelayed(done, SAFETY_RESET_MS);
            Object controller = controller(cl, controllerType, (ViewGroup) anchor.getRootView(), screenRect(anchor, fromInView),
                    anchor.getResources().getDisplayMetrics().density, done);
            if (pi != null) {
                Method m = activityStarter.getClass().getMethod("postStartActivityDismissingKeyguard", PendingIntent.class, controllerType);
                m.invoke(activityStarter, pi, controller);
            } else if (intent != null) {
                Method m = activityStarter.getClass().getMethod("postStartActivityDismissingKeyguard", Intent.class, int.class, controllerType);
                m.invoke(activityStarter, intent, 0, controller);
            } else {
                done.run();
                return false;
            }
            return true;
        } catch (Throwable t) {
            XLog.once("AppLaunchAnimator.launch", t);
            fullscreen.accept(false);
            return false;
        }
    }

    private static RectF screenRect(View anchor, RectF r) {
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        return new RectF(r.left + at[0], r.top + at[1], r.right + at[0], r.bottom + at[1]);
    }

    private static Object controller(ClassLoader cl, Class<?> type, ViewGroup root, RectF from, float density, Runnable done) throws ReflectiveOperationException {
        Constructor<?> stateCtor = Class.forName(STATE, false, cl)
                .getConstructor(int.class, int.class, int.class, int.class, float.class, float.class);
        float radius = Math.min(from.height() / 2.0f, MAX_RADIUS_DP * density);
        ViewGroup[] container = {root};
        return Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "createAnimatorState":
                    return stateCtor.newInstance(Math.round(from.top), Math.round(from.bottom), Math.round(from.left),
                            Math.round(from.right), radius, radius);
                case "getTransitionContainer":
                    return container[0];
                case "setTransitionContainer":
                    container[0] = (ViewGroup) args[0];
                    return null;
                case "isLaunching":
                    return true;
                case "getTransitionColor":
                    // the island card itself grows to full screen, and the app fades in over it once drawn
                    return CARD_COLOR;
                case "onTransitionAnimationEnd":
                case "onTransitionAnimationCancelled":
                case "onDispose":
                    root.post(done);
                    return null;
                case "onIntentStarted":
                    if (args != null && Boolean.FALSE.equals(args[0])) {
                        root.post(done); // no animation will run
                    }
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "IslandLaunchController";
                default:
                    return Refl.defaultValue(method.getReturnType());
            }
        });
    }
}
