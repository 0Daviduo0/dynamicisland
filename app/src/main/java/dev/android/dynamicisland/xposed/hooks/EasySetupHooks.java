package dev.android.dynamicisland.xposed.hooks;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Hides the small Galaxy Buds battery pill that Samsung's EasySetup ("intelligent continuity") shows at the top of
 * the screen when earbuds connect, since the island already shows a Bluetooth card for the same event.
 *
 * <p>The pill is an AlertDialog built by an obfuscated class in {@code com.samsung.android.intelligentcontinuity}
 * whose {@code show(String title, CharSequence body, String screen)} method takes one of three screen names:
 * {@code "connectScreen"} (a new device nearby, with a Connect button), {@code "connectingScreen"} and
 * {@code "batteryScreen"} (already connected, battery levels). Only {@code batteryScreen} is suppressed, so pairing
 * and connect prompts keep working.
 *
 * <p>The class is found by its constants instead of its name, so a rebuilt EasySetup with different obfuscation
 * still matches. Opt-in: it runs only when the user adds EasySetup to the module's LSPosed scope.
 */
public final class EasySetupHooks {
    public static final String PACKAGE = "com.samsung.android.easysetup";
    private static final String PKG = "com.samsung.android.intelligentcontinuity.";
    private static final String BATTERY_SCREEN = "batteryScreen";
    /** Name in EasySetup 11.1.24.8 (One UI 9.0); tried first, then every short obfuscated name in the package. */
    private static final String KNOWN_CLASS = PKG + "f";

    private EasySetupHooks() {
    }

    public static void install(ClassLoader loader) {
        Method show = find(loader, KNOWN_CLASS);
        for (char a = 'a'; show == null && a <= 'z'; a++) {
            show = find(loader, PKG + a);
        }
        if (show == null) {
            XLog.i("easysetup: battery popup class not found, popup left as is");
            return;
        }
        XposedBridge.hookMethod(show, SafeHook.before("easysetup battery popup", param -> {
            if (BATTERY_SCREEN.equals(param.args[2])) {
                param.setResult(null);
            }
        }));
        XLog.i("easysetup: hiding battery popup via " + show.getDeclaringClass().getName() + "#" + show.getName());
    }

    /** The popup's show method in {@code className}, or null when that class is not the popup controller. */
    private static Method find(ClassLoader loader, String className) {
        Class<?> c = XposedHelpers.findClassIfExists(className, loader);
        if (c == null || !hasConstant(c, BATTERY_SCREEN)) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == String.class && p[1] == CharSequence.class && p[2] == String.class
                    && m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        return null;
    }

    private static boolean hasConstant(Class<?> c, String value) {
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    if (value.equals(f.get(null))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // not readable: not ours
                }
            }
        }
        return false;
    }
}
