package dev.android.dynamicisland.xposed.hooks;

import android.view.View;
import dev.android.dynamicisland.island.window.PrivacyDotHider;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;

/**
 * Lets the island hide the camera / microphone privacy dot while it covers it: remembers every
 * {@code PrivacyDotViewControllerImpl} and keeps a dot that is shown under the island faded out.
 */
final class PrivacyDotHooks {
    private static final String CONTROLLER = "com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl";

    private PrivacyDotHooks() { }

    static void install(ClassLoader loader) {
        int built = Refl.hookConstructors(loader, CONTROLLER,
                SafeHook.after("privacy dot controller", p -> PrivacyDotHider.register(p.thisObject)));
        int shown = Refl.hookAll(loader, CONTROLLER, "showDotView", SafeHook.before("privacy dot show", p -> {
            if (p.args.length > 0 && p.args[0] instanceof View) PrivacyDotHider.onShowDot((View) p.args[0]);
        }));
        Diagnostics.fact("privacy dot", "controller " + built + ", show " + shown);
    }
}
