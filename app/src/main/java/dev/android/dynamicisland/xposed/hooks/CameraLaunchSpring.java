package dev.android.dynamicisland.xposed.hooks;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.SurfaceControl;
import de.robv.android.xposed.XC_MethodHook;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * The camera opened with a double press of the power key is thrown out of the power key: the app's
 * window leaves the key as a small rounded card, flies to the centre of the screen and opens to full
 * screen from there, all on springs (like the iPhone's Camera Control).
 *
 * <p>System UI starts the camera ({@code CameraLauncher.launchCamera}, source 1 = power double tap) and
 * WM Shell, in the same process, then plays the opening transition ({@code DefaultTransitionHandler}).
 * The launch is noted, and the first opening transition of the camera that follows within a short time
 * is played here instead, on the camera's surface; every other transition is left to Shell. Without a
 * known power key position, or with a rotation in the transition, Shell plays it as before.
 */
public final class CameraLaunchSpring {
    private static final String LAUNCHER = "com.android.systemui.shade.CameraLauncher";
    private static final String CALLBACKS = "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks";
    private static final String HANDLER = "com.android.wm.shell.transition.DefaultTransitionHandler";
    /** StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP */
    private static final int SOURCE_POWER_DOUBLE_TAP = 1;
    private static final long CLAIM_WINDOW_MS = 3000;
    /** WindowManager.TRANSIT_OPEN / TRANSIT_TO_FRONT (hidden constants). */
    private static final int TRANSIT_OPEN = 1;
    private static final int TRANSIT_TO_FRONT = 3;

    static final long DURATION_MS = 820;
    /** Size of the card when it leaves the key and when it reaches the centre (fractions of the screen). */
    static final float START_SCALE = 0.06f;
    static final float CARD_SCALE = 0.3f;
    /** The card's corners, as a fraction of its width, until it opens to full screen. */
    static final float CARD_CORNER = 0.16f;
    /** When the card starts to open to full screen, in seconds. */
    static final double EXPAND_DELAY = 0.17;

    private static volatile long requestedAt;
    private static volatile String cameraPackage;

    private CameraLaunchSpring() {
    }

    static void install(ClassLoader cl) {
        int launch = Refl.hookAll(cl, LAUNCHER, "launchCamera", SafeHook.before("camera spring launch", CameraLaunchSpring::beforeLaunch));
        // the power double press reaches System UI here; on One UI it does not go through CameraLauncher
        int gesture = Refl.hookAll(cl, CALLBACKS, "onCameraLaunchGestureDetected", SafeHook.before("camera spring gesture", p -> {
            if (p.args.length == 1 && p.args[0] instanceof Integer && (Integer) p.args[0] == SOURCE_POWER_DOUBLE_TAP) {
                cameraPackage = null;
                requestedAt = SystemClock.uptimeMillis();
            }
        }));
        int play = Refl.hookAll(cl, HANDLER, "startAnimation", SafeHook.before("camera spring transition", CameraLaunchSpring::beforeTransition));
        XLog.i("camera spring hooks: launch=" + launch + " gesture=" + gesture + " transition=" + play);
    }

    private static void beforeLaunch(XC_MethodHook.MethodHookParam p) {
        if (p.args.length < 1 || !(p.args[0] instanceof Integer) || (Integer) p.args[0] != SOURCE_POWER_DOUBLE_TAP) return;
        Object helper = Refl.get(p.thisObject, "mCameraGestureHelper");
        Object intent = helper == null ? null : Refl.call(helper, "getStartCameraIntent");
        ComponentName component = intent instanceof Intent ? ((Intent) intent).getComponent() : null;
        if (component != null) cameraPackage = component.getPackageName();
        requestedAt = SystemClock.uptimeMillis();
    }

    /** startAnimation(transition, info, startT, finishT, finishCallback) */
    private static void beforeTransition(XC_MethodHook.MethodHookParam p) {
        long at = requestedAt;
        if (at == 0 || SystemClock.uptimeMillis() - at > CLAIM_WINDOW_MS || p.args.length != 5) return;
        Object info = p.args[1];
        Object camera = cameraChange(info, cameraPackage);
        if (camera == null) {
            XLog.i("camera spring: transition after the double press is not the camera opening");
            return;
        }
        Float powerY = powerKeyY();
        if (powerY == null) {
            XLog.i("camera spring: power key position unknown, stock animation");
            return;
        }
        requestedAt = 0;
        SurfaceControl.Transaction startT = (SurfaceControl.Transaction) p.args[2];
        SurfaceControl.Transaction finishT = (SurfaceControl.Transaction) p.args[3];
        Object finish = p.args[4];
        p.setResult(true);
        play(camera, powerY, startT, finish);
    }

    private static int intOf(Object o, String method) {
        Object v = Refl.call(o, method);
        return v instanceof Integer ? (Integer) v : -1;
    }

    /** The opening change (TransitionInfo.Change, a hidden API) of the camera's task, or null. */
    private static Object cameraChange(Object info, String pkg) {
        int type = intOf(info, "getType");
        if (type != TRANSIT_OPEN && type != TRANSIT_TO_FRONT) return null;
        Object changes = Refl.call(info, "getChanges");
        if (!(changes instanceof Iterable)) return null;
        Object found = null;
        for (Object c : (Iterable<?>) changes) {
            if (intOf(c, "getStartRotation") != intOf(c, "getEndRotation")) return null; // leave rotations to Shell
            int mode = intOf(c, "getMode");
            if (mode != TRANSIT_OPEN && mode != TRANSIT_TO_FRONT) continue;
            Object task = Refl.call(c, "getTaskInfo");
            Object topObj = task == null ? null : Refl.get(task, "topActivity");
            if (topObj == null && task != null) topObj = Refl.get(task, "baseActivity");
            ComponentName top = topObj instanceof ComponentName ? (ComponentName) topObj : null;
            if (top == null) continue;
            if (pkg != null ? pkg.equals(top.getPackageName()) : top.getPackageName().contains("camera")) found = c;
        }
        return found;
    }

    /** Default power key centre as fraction of display height (S25 Ultra: under volume rocker). */
    static final float DEFAULT_KEY_FRACTION = 0.42f;

    /**
     * Power key centre as fraction of display height. Tunable without rebuild:
     * {@code setprop persist.dynamicisland.powerkey_y 420} (per mille).
     */
    private static Float powerKeyY() {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("getInt", String.class, int.class)
                    .invoke(null, "persist.dynamicisland.powerkey_y", 0);
            int pm = v instanceof Integer ? (Integer) v : 0;
            return pm > 0 && pm < 1000 ? pm / 1000.0f : DEFAULT_KEY_FRACTION;
        } catch (ReflectiveOperationException | RuntimeException e) {
            XLog.once("camera spring power key", e);
            return DEFAULT_KEY_FRACTION;
        }
    }

    /** Damped spring from 0 to 1 (a little past it while it settles); t in seconds. Pure math. */
    static float spring(double t, double omega, double damping) {
        if (t <= 0) return 0.0f;
        double zw = damping * omega;
        double wd = omega * Math.sqrt(1.0 - damping * damping);
        return (float) (1.0 - Math.exp(-zw * t) * (Math.cos(wd * t) + zw / wd * Math.sin(wd * t)));
    }

    /** One frame of the window: uniform scale, its top-left on the screen, corner radius on screen, alpha. */
    static final class Frame {
        float scale;
        float x;
        float y;
        float radius;
        float alpha;
    }

    /**
     * A small rounded card comes out of the power key (its right edge on the screen's right edge, level
     * with the key), is thrown towards the centre of the screen while it grows to a card, and opens to
     * full screen from there, all on springs that overlap.
     *
     * @param t      seconds since the start
     * @param width  window width, height its height
     * @param keyY   power key centre, in the window's coordinates
     * @param corner the screen's corner radius
     */
    static Frame frame(double t, float width, float height, float keyY, float corner, Frame out) {
        float move = spring(t, 15.0, 0.78);          // key -> centre
        float grow = spring(t, 20.0, 0.8);           // tiny -> card
        float open = spring(t - EXPAND_DELAY, 13.0, 0.74); // card -> full screen
        float card = START_SCALE + (CARD_SCALE - START_SCALE) * grow;
        out.scale = card + (1.0f - CARD_SCALE) * open;
        float w = width * out.scale;
        float h = height * out.scale;
        float startX = width - width * START_SCALE / 2.0f;
        float cx = startX + (width / 2.0f - startX) * move;
        float cy = keyY + (height / 2.0f - keyY) * move;
        out.x = cx - w / 2.0f;
        out.y = cy - h / 2.0f;
        float cardRadius = width * out.scale * CARD_CORNER;
        float settle = Math.max(0.0f, Math.min(1.0f, open));
        out.radius = cardRadius + (corner - cardRadius) * settle;
        out.alpha = (float) Math.max(0.0, Math.min(1.0, t / 0.08));
        return out;
    }

    /** The last frame is exactly full screen. */
    static Frame last(float width, float height, float corner, Frame out) {
        out.scale = 1.0f;
        out.x = 0.0f;
        out.y = 0.0f;
        out.radius = corner;
        out.alpha = 1.0f;
        return out;
    }

    private static void play(Object camera, float powerY /* fraction of display height */, SurfaceControl.Transaction startT, Object finish) {
        SurfaceControl leash = (SurfaceControl) Refl.call(camera, "getLeash");
        Rect bounds = (Rect) Refl.call(camera, "getEndAbsBounds");
        Point offset = (Point) Refl.call(camera, "getEndRelOffset");
        float width = bounds.width();
        float height = bounds.height();
        float keyY = powerY * bounds.bottom - bounds.top;
        float corner = cornerRadius();
        Frame frame = new Frame();

        // first frame together with the transition's own start, so the full window never shows first
        apply(startT, leash, frame(0.0, width, height, keyY, corner, frame), offset, width, height);
        startT.apply();

        ValueAnimator a = ValueAnimator.ofFloat(0.0f, 1.0f);
        a.setDuration(DURATION_MS);
        a.setInterpolator(null);
        a.addUpdateListener(v -> {
            long ms = v.getCurrentPlayTime();
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            apply(t, leash, frame(ms / 1000.0, width, height, keyY, corner, frame), offset, width, height);
            t.apply();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                matrix(t, leash, 1.0f);
                t.setPosition(leash, offset.x, offset.y);
                corner(t, leash, 0.0f);
                t.setAlpha(leash, 1);
                t.apply();
                try {
                    Refl.method(finish.getClass(), "onTransitionFinished", 1).invoke(finish, (Object) null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    XLog.e("camera spring finish", e);
                }
            }
        });
        a.start();
        XLog.i("camera spring: playing from y=" + keyY + " on " + width + "x" + height);
    }

    private static void apply(SurfaceControl.Transaction t, SurfaceControl leash, Frame f, Point offset, float width, float height) {
        matrix(t, leash, f.scale);
        t.setPosition(leash, offset.x + f.x, offset.y + f.y);
        crop(t, leash, (int) width, (int) height);
        corner(t, leash, f.radius / Math.max(f.scale, 0.001f)); // the radius is in the window's own pixels
        t.setAlpha(leash, f.alpha);
    }

    // SurfaceControl.Transaction setters that are hidden from the SDK
    private static java.lang.reflect.Method setMatrix;
    private static java.lang.reflect.Method setCrop;
    private static java.lang.reflect.Method setCorner;

    private static java.lang.reflect.Method find(String name, Class<?>... types) {
        try {
            java.lang.reflect.Method m = SurfaceControl.Transaction.class.getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            XLog.once("camera spring " + name, e);
            return null;
        }
    }

    private static void invoke(java.lang.reflect.Method m, SurfaceControl.Transaction t, Object... args) {
        if (m == null) return;
        try {
            m.invoke(t, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            XLog.once("camera spring " + m.getName(), e);
        }
    }

    private static void matrix(SurfaceControl.Transaction t, SurfaceControl leash, float scale) {
        if (setMatrix == null) setMatrix = find("setMatrix", SurfaceControl.class, float.class, float.class, float.class, float.class);
        invoke(setMatrix, t, leash, scale, 0.0f, 0.0f, scale);
    }

    private static void crop(SurfaceControl.Transaction t, SurfaceControl leash, int w, int h) {
        if (setCrop == null) setCrop = find("setWindowCrop", SurfaceControl.class, int.class, int.class);
        invoke(setCrop, t, leash, w, h);
    }

    private static void corner(SurfaceControl.Transaction t, SurfaceControl leash, float radius) {
        if (setCorner == null) setCorner = find("setCornerRadius", SurfaceControl.class, float.class);
        invoke(setCorner, t, leash, radius);
    }

    /** The screen's corner radius, so the window ends with the shape of the screen. */
    private static float cornerRadius() {
        try {
            Context app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            int id = app.getResources().getIdentifier("rounded_corner_radius", "dimen", "android");
            return id == 0 ? 0.0f : app.getResources().getDimension(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0.0f;
        }
    }
}
