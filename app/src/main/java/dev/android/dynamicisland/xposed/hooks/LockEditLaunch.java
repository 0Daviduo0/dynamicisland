package dev.android.dynamicisland.xposed.hooks;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens the lock screen editor without a frozen frame. On a secured lock screen, Samsung's
 * {@code KeyguardEditModeControllerImpl.startEditActivity(context, true)} fetches a screenshot of the
 * wallpaper and decodes it on the main thread (about 100 ms at the idle CPU cap) only to save it as the
 * editor's preview file, then asks the bouncer to start the editor. That bitmap is never shown by System UI.
 * Here the fetch and the save run on a background thread, and the editor start that System UI requested is
 * passed on to the main thread once the screenshot is taken, while the file is written, as System UI does
 * (its save also runs in the background). If the fetch fails or takes longer than {@link #START_TIMEOUT_MS},
 * the start goes on anyway.
 * Calls of {@code getWallpaperBitmap} from anywhere else (the visible preview of the edit animation) are not touched.
 */
final class LockEditLaunch {
    static final String CONTROLLER = "com.android.systemui.keyguard.KeyguardEditModeControllerImpl";
    static final String STARTER = "com.android.systemui.statusbar.phone.ActivityStarterImpl";
    static final String START_EDIT = "startEditActivity";
    static final String CAPTURE = "getWallpaperBitmap";
    static final String SAVE = "access$saveWallpaperBitmap";
    static final String POST_START = "postStartActivityDismissingKeyguard";
    /** Longest wait for the preview before the editor start is passed on regardless. */
    static final long START_TIMEOUT_MS = 1500;

    /** What a deferred launch does; the hooks call System UI's original methods, the tests use fakes. */
    interface Ops {
        /** The wallpaper screenshot for the preview, or null. Runs on the background thread. */
        Object capture(Launch launch) throws Throwable;

        /** Saves the preview file the editor reads. Runs on the background thread. */
        void save(Launch launch, Object bitmap) throws Throwable;

        /** The editor start System UI asked for. Runs on the main thread, at most once. */
        void start(Launch launch) throws Throwable;
    }

    /** One {@code startEditActivity(context, true)} call: what was skipped and must be done later. */
    static final class Launch {
        final Object controller;
        Context context;
        Member capture;
        boolean captureSkipped;
        Object starter;
        Member startMethod;
        Object[] startArgs;

        Launch(Object controller) {
            this.controller = controller;
        }

        boolean captureSkipped() {
            return captureSkipped;
        }
    }

    /** The launch in progress inside {@code startEditActivity}; main thread only. */
    private static Launch current;

    private LockEditLaunch() {
    }

    /** Starts tracking a {@code startEditActivity(context, lockBouncer)} call; only the secured (bouncer) case, only on the main thread. */
    static Launch begin(Object controller, Object[] args, boolean onMain) {
        if (!onMain || current != null || args == null || args.length != 2 || !Boolean.TRUE.equals(args[1])) {
            return null;
        }
        current = new Launch(controller);
        return current;
    }

    /**
     * True when this {@code getWallpaperBitmap(context, true)} call is the one {@code startEditActivity} makes
     * for the preview file, so it can be skipped and done in the background.
     */
    static boolean skipCapture(Object controller, Object[] args, Member method, StackTraceElement[] stack) {
        Launch launch = current;
        if (launch == null || launch.controller != controller || launch.captureSkipped()
                || args == null || args.length != 2 || !(args[0] instanceof Context) || !Boolean.TRUE.equals(args[1])
                || !calledFromStartEdit(stack)) {
            return false;
        }
        launch.context = (Context) args[0];
        launch.capture = method;
        launch.captureSkipped = true;
        return true;
    }

    /**
     * True when the first System UI frame above {@code getWallpaperBitmap} is {@code startEditActivity}, i.e. the
     * call is not one of the edit animation's (whose bitmap is displayed). Frames of the hook bridge are skipped.
     */
    static boolean calledFromStartEdit(StackTraceElement[] stack) {
        if (stack == null) {
            return false;
        }
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (!cls.startsWith("com.android.systemui.") || CAPTURE.equals(frame.getMethodName())) {
                continue;
            }
            return CONTROLLER.equals(cls) && START_EDIT.equals(frame.getMethodName());
        }
        return false;
    }

    /** True when this {@code postStartActivityDismissingKeyguard(intent, flags)} is the editor start of the launch, which then waits for the preview. */
    static boolean deferStart(Object starter, Object[] args, Member method) {
        Launch launch = current;
        if (launch == null || !launch.captureSkipped() || launch.starter != null
                || args == null || args.length != 2 || !(args[0] instanceof Intent)) {
            return false;
        }
        launch.starter = starter;
        launch.startMethod = method;
        launch.startArgs = args.clone();
        return true;
    }

    /** Ends tracking when {@code startEditActivity} returns; the launch if something was skipped and is still to do. */
    static Launch end() {
        Launch launch = current;
        current = null;
        return launch != null && launch.captureSkipped() ? launch : null;
    }

    /** Fetches the preview on {@code background}, passes the editor start on to {@code main}, then saves the preview. */
    static void finish(Launch launch, Ops ops, Executor background, Handler main) {
        AtomicBoolean started = new AtomicBoolean();
        Runnable start = () -> {
            if (launch.starter == null || !started.compareAndSet(false, true)) {
                return;
            }
            try {
                ops.start(launch);
            } catch (Throwable t) {
                XLog.once("lock editor start", t);
            }
        };
        main.postDelayed(start, START_TIMEOUT_MS);
        try {
            background.execute(() -> {
                try {
                    Object bitmap = ops.capture(launch);
                    // as in System UI: the editor is requested once the screenshot is taken, the file is written meanwhile
                    main.post(start);
                    if (bitmap != null) {
                        ops.save(launch, bitmap);
                    }
                } catch (Throwable t) {
                    XLog.once("lock editor preview", t);
                } finally {
                    main.post(start);
                }
            });
        } catch (Throwable t) {
            XLog.once("lock editor background", t);
            main.post(start);
        }
    }

    // ---- hooks -------------------------------------------------------------------------------------

    static void install(ClassLoader cl) {
        if (!Refl.hasClass(CONTROLLER, cl) || !Refl.hasClass(STARTER, cl)) {
            Diagnostics.fact("lock editor launch", "classes not found, stock behaviour");
            return;
        }
        Class<?> controller = Refl.findClass(CONTROLLER, cl);
        Method startEdit = Refl.method(controller, START_EDIT, 2);
        Method capture = Refl.method(controller, CAPTURE, 2);
        Method save = Refl.method(controller, SAVE, 3);
        Method postStart = postStart(Refl.findClass(STARTER, cl));
        boolean shapes = startEdit != null && capture != null && save != null && postStart != null
                && Context.class.isAssignableFrom(startEdit.getParameterTypes()[0]) && startEdit.getParameterTypes()[1] == boolean.class
                && Context.class.isAssignableFrom(capture.getParameterTypes()[0]) && capture.getParameterTypes()[1] == boolean.class
                && Bitmap.class.isAssignableFrom(capture.getReturnType())
                && save.getParameterTypes()[0] == controller && Context.class.isAssignableFrom(save.getParameterTypes()[1])
                && Bitmap.class.isAssignableFrom(save.getParameterTypes()[2]);
        if (!shapes) {
            Diagnostics.fact("lock editor launch", "methods changed, stock behaviour");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        Ops ops = new Ops() {
            @Override
            public Object capture(Launch launch) throws Throwable {
                return XposedBridge.invokeOriginalMethod(launch.capture, launch.controller, new Object[] {launch.context, Boolean.TRUE});
            }

            @Override
            public void save(Launch launch, Object bitmap) throws Throwable {
                save.invoke(null, launch.controller, launch.context, bitmap);
            }

            @Override
            public void start(Launch launch) throws Throwable {
                XposedBridge.invokeOriginalMethod(launch.startMethod, launch.starter, launch.startArgs);
            }
        };
        Executor background = task -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                task.run();
            }, "di-lock-edit-preview");
            thread.setDaemon(true);
            thread.start();
        };
        XposedBridge.hookMethod(startEdit, new SafeHook("lock editor launch") {
            @Override
            protected void before(XC_MethodHook.MethodHookParam p) {
                begin(p.thisObject, p.args, Looper.myLooper() == Looper.getMainLooper());
            }

            @Override
            protected void after(XC_MethodHook.MethodHookParam p) {
                Launch launch = end();
                if (launch != null) {
                    finish(launch, ops, background, main);
                }
            }
        });
        XposedBridge.hookMethod(capture, SafeHook.before("lock editor preview", p -> {
            if (current != null && skipCapture(p.thisObject, p.args, p.method, new Throwable().getStackTrace())) {
                p.setResult(null);
            }
        }));
        XposedBridge.hookMethod(postStart, SafeHook.before("lock editor start", p -> {
            if (current != null && deferStart(p.thisObject, p.args, p.method)) {
                p.setResult(null);
            }
        }));
        Diagnostics.fact("lock editor launch", "preview fetched off the main thread");
    }

    /** {@code postStartActivityDismissingKeyguard(Intent, int)} of the starter, or null. */
    private static Method postStart(Class<?> starter) {
        for (Method m : starter.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (POST_START.equals(m.getName()) && types.length == 2 && types[0] == Intent.class && types[1] == int.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
