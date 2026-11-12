package dev.android.dynamicisland.xposed.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.island.source.QuickShareState;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs inside Quick Share. Its receiving sheet (accept / decline, then the progress) is kept alive but made invisible
 * and untouchable while the island stands in for it: every state of the sheet goes to System UI, and the island's
 * buttons come back as commands that press the sheet's own buttons, so Quick Share keeps doing all the work.
 *
 * <p>The sheet is hidden as soon as it opens and shown again when System UI does not confirm within
 * {@link #ACK_TIMEOUT_MS} (module off in System UI, feature switched off), so Quick Share never ends up without UI.
 * Opt-in: runs only when the user adds Quick Share to the module's LSPosed scope.
 */
public final class QuickShareHooks {
    private static final String DIALOG_ACTIVITY = "ReceivingDialogActivity";
    private static final String VIEW_MODEL = "com.samsung.android.app.sharelive.presentation.receivingdialog.dialogfragment.ReceivingDialogViewModel";
    private static final long ACK_TIMEOUT_MS = 1500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static final List<WeakReference<Dialog>> DIALOGS = new ArrayList<>();
    private static BroadcastReceiver commands;
    /** System UI confirmed it shows the session on the island. */
    private static boolean islandOwns;
    private static boolean hidden;
    private static String lastState;
    private static int sessionId = -1;
    private static final Runnable NO_ACK = () -> {
        if (!islandOwns) {
            XLog.i("quick share: no answer from the island, sheet shown");
            setHidden(false);
        }
    };

    private QuickShareHooks() {
    }

    public static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", SafeHook.after("quick share sheet resume", param -> {
            Activity a = (Activity) param.thisObject;
            if (!a.getClass().getName().endsWith(DIALOG_ACTIVITY)) return;
            activity = new WeakReference<>(a);
            register(a);
            if (!islandOwns) {
                setHidden(true);
                MAIN.removeCallbacks(NO_ACK);
                MAIN.postDelayed(NO_ACK, ACK_TIMEOUT_MS);
            } else {
                setHidden(true);
            }
            if (lastState != null) send(a, lastState);
        }));
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", SafeHook.after("quick share sheet destroy", param -> {
            Activity a = (Activity) param.thisObject;
            if (!a.getClass().getName().endsWith(DIALOG_ACTIVITY)) return;
            if (commands != null) {
                try { a.getApplicationContext().unregisterReceiver(commands); } catch (Throwable ignored) { }
                commands = null;
            }
            Intent gone = new Intent(QuickShareState.ACTION_STATE).setPackage("com.android.systemui")
                    .putExtra(QuickShareState.EXTRA_GONE, true).putExtra(QuickShareState.EXTRA_ID, sessionId);
            a.getApplicationContext().sendBroadcast(gone);
            lastState = null;
            loadedPreviews = null;
            islandOwns = false;
            hidden = false;
            DIALOGS.clear();
            activity = new WeakReference<>(null);
        }));
        XposedHelpers.findAndHookMethod(Dialog.class, "show", SafeHook.after("quick share sheet dialog", param -> {
            Dialog d = (Dialog) param.thisObject;
            Activity owner = activityOf(d.getContext());
            if (owner == null || !owner.getClass().getName().endsWith(DIALOG_ACTIVITY)) return;
            DIALOGS.add(new WeakReference<>(d));
            if (hidden) hide(d.getWindow(), true);
        }));
        hookStates(loader);
        QuickShareSendWatch.install();
        XLog.i("quick share: hooks installed");
    }

    /** Every view-state change of the sheet's view model, read back through its state getter. */
    private static void hookStates(ClassLoader loader) {
        Class<?> vm = XposedHelpers.findClassIfExists(VIEW_MODEL, loader);
        if (vm == null || vm.getSuperclass() == null) {
            XLog.i("quick share: receiving view model not found, island cannot follow the sheet");
            return;
        }
        Class<?> base = vm.getSuperclass();
        Method getter = null;
        for (Method m : base.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == Object.class && !Modifier.isStatic(m.getModifiers())) {
                getter = m;
                break;
            }
        }
        if (getter == null) {
            XLog.i("quick share: state getter not found");
            return;
        }
        getter.setAccessible(true);
        final Method read = getter;
        int hooked = 0;
        for (Method m : base.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class || p.length != 1 || p[0] != Object.class) continue;
            XposedBridge.hookMethod(m, SafeHook.after("quick share state", param -> {
                if (!vm.isInstance(param.thisObject)) return;
                Object state;
                try {
                    state = read.invoke(param.thisObject);
                } catch (Throwable t) {
                    return; // not initialised yet
                }
                String text = String.valueOf(state);
                if (!text.startsWith("ReceivingDialogState(") || text.equals(lastState)) return;
                lastState = text;
                QuickShareState parsed = QuickShareState.parse(text);
                if (parsed != null) sessionId = parsed.id;
                Activity a = activity.get();
                if (a != null) {
                    send(a, text);
                    loadPreviews(a, sessionId, QuickShareState.previewUris(text));
                }
            }));
            hooked++;
        }
        XLog.i("quick share: following " + hooked + " state setters of " + base.getName());
    }

    private static String loadedPreviews;

    /**
     * Small JPEG copies of the shared pictures for the island card: Quick Share can read its own preview and received
     * file URIs, System UI cannot. Off the main thread; each set of URIs is sent once.
     */
    private static void loadPreviews(Activity a, int id, List<String> uris) {
        if (uris.isEmpty()) return;
        String keyOf = id + uris.toString();
        if (keyOf.equals(loadedPreviews)) return;
        loadedPreviews = keyOf;
        Context app = a.getApplicationContext();
        new Thread(() -> {
            Intent out = new Intent(QuickShareState.ACTION_STATE).setPackage("com.android.systemui")
                    .putExtra(QuickShareState.EXTRA_ID, id);
            int n = 0;
            for (String uri : uris) {
                byte[] jpeg = thumbnail(app, uri);
                if (jpeg != null) out.putExtra(QuickShareState.EXTRA_PREVIEW + n++, jpeg);
            }
            if (n > 0) app.sendBroadcast(out);
        }, "IslandQuickSharePreview").start();
    }

    private static byte[] thumbnail(Context ctx, String uri) {
        android.graphics.Bitmap bmp = null;
        try {
            if (uri.startsWith("/")) {
                bmp = android.media.ThumbnailUtils.createImageThumbnail(new java.io.File(uri), new android.util.Size(384, 384), null);
            } else {
                bmp = ctx.getContentResolver().loadThumbnail(android.net.Uri.parse(uri), new android.util.Size(384, 384), null);
            }
        } catch (Throwable t) {
            XLog.i("quick share: no preview for one item (" + t.getClass().getSimpleName() + ")");
        }
        if (bmp == null) return null;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bytes);
        bmp.recycle();
        return bytes.toByteArray();
    }

    private static void send(Context ctx, String state) {
        ctx.getApplicationContext().sendBroadcast(new Intent(QuickShareState.ACTION_STATE).setPackage("com.android.systemui")
                .putExtra(QuickShareState.EXTRA_STATE, state));
    }

    private static void register(Activity a) {
        if (commands != null) return;
        commands = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    command(intent.getStringExtra(QuickShareState.EXTRA_COMMAND));
                } catch (Throwable t) {
                    XLog.e("quick share command", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(QuickShareState.ACTION_COMMAND);
        a.getApplicationContext().registerReceiver(commands, filter, QuickShareState.PERMISSION, MAIN, Context.RECEIVER_EXPORTED);
    }

    private static void command(String cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case QuickShareState.CMD_ACK:
                islandOwns = true;
                MAIN.removeCallbacks(NO_ACK);
                setHidden(true);
                return;
            case QuickShareState.CMD_RELEASE:
                islandOwns = false;
                setHidden(false);
                return;
            case QuickShareState.CMD_ACCEPT:
                press("action_accept", "action_receive", "accept");
                return;
            case QuickShareState.CMD_DECLINE:
                press("action_decline", "call_notification_decline_action", "decline");
                return;
            case QuickShareState.CMD_CANCEL:
                press("action_cancel", "cancel");
                return;
            case QuickShareState.CMD_OPEN:
                if (!press("receiving_dialog_open", "receiving_dialog_view", "action_other_open")) openApp();
                return;
            case QuickShareState.CMD_CLOSE:
                Activity a = activity.get();
                if (a != null && !a.isFinishing()) a.finish();
                return;
            default:
        }
    }

    /** Presses the sheet's button whose label is one of Quick Share's own strings {@code names}. */
    private static boolean press(String... names) {
        Activity a = activity.get();
        if (a == null) {
            XLog.i("quick share: no sheet for command");
            return false;
        }
        List<String> labels = new ArrayList<>();
        for (String name : names) {
            int id = a.getResources().getIdentifier(name, "string", a.getPackageName());
            if (id != 0) labels.add(a.getString(id).trim().toLowerCase(Locale.ROOT));
        }
        List<View> roots = new ArrayList<>();
        for (WeakReference<Dialog> ref : DIALOGS) {
            Dialog d = ref.get();
            if (d != null && d.isShowing() && d.getWindow() != null) roots.add(d.getWindow().getDecorView());
        }
        if (a.getWindow() != null) roots.add(a.getWindow().getDecorView());
        List<String> seen = new ArrayList<>();
        for (View root : roots) {
            View hit = find(root, labels, seen);
            if (hit != null) {
                hit.performClick();
                XLog.i("quick share: pressed " + names[0]);
                return true;
            }
        }
        XLog.i("quick share: no button " + labels + " among " + seen);
        return false;
    }

    /** No Open button on the sheet (anymore): Quick Share's own screen, which lists what was received. */
    private static void openApp() {
        Activity a = activity.get();
        if (a == null) return;
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(a.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(launch);
        if (!a.isFinishing()) a.finish();
    }

    private static View find(View v, List<String> labels, List<String> seen) {
        if (v == null || !v.isShown()) return null;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                String label = t.toString().trim().toLowerCase(Locale.ROOT);
                seen.add(label);
                if (labels.contains(label)) {
                    for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
                        if (c.isClickable() && c.isEnabled()) return c;
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = find(g.getChildAt(i), labels, seen);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void setHidden(boolean value) {
        hidden = value;
        Activity a = activity.get();
        if (a != null) hide(a.getWindow(), value);
        for (WeakReference<Dialog> ref : DIALOGS) {
            Dialog d = ref.get();
            if (d != null) hide(d.getWindow(), value);
        }
    }

    /** Invisible and click-through, no dim; the window stays so the sheet keeps running. */
    private static void hide(Window w, boolean value) {
        if (w == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        Object saved = XposedHelpers.getAdditionalInstanceField(w, "islandSaved");
        if (value) {
            if (saved == null) {
                XposedHelpers.setAdditionalInstanceField(w, "islandSaved", new int[]{Float.floatToIntBits(lp.alpha), Float.floatToIntBits(lp.dimAmount), lp.flags});
            }
            lp.alpha = 0f;
            lp.dimAmount = 0f;
            lp.flags = (lp.flags & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            w.setWindowAnimations(0);
        } else if (saved instanceof int[]) {
            int[] s = (int[]) saved;
            lp.alpha = Float.intBitsToFloat(s[0]);
            lp.dimAmount = Float.intBitsToFloat(s[1]);
            lp.flags = s[2];
            XposedHelpers.removeAdditionalInstanceField(w, "islandSaved");
        } else {
            return;
        }
        w.setAttributes(lp);
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
