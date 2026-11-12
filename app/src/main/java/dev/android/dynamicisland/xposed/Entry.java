package dev.android.dynamicisland.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.android.dynamicisland.xposed.hooks.EasySetupHooks;
import dev.android.dynamicisland.xposed.hooks.SystemUiHooks;

/**
 * LSPosed entry point (named in {@code assets/xposed_init}). Installs the hooks once, in the main
 * System UI process only, and not at all when {@link CrashGuard} has switched the module off.
 */
public final class Entry implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static boolean installed;
    private static boolean easySetupInstalled;
    private static boolean quickShareInstalled;

    /** Path of the apk this code was loaded from (tells an old install from the new one), or "?". */
    private static String moduleApk() {
        try {
            String loader = String.valueOf(Entry.class.getClassLoader());
            int from = loader.indexOf("zip file \"");
            if (from >= 0) {
                int start = from + "zip file \"".length();
                return loader.substring(start, loader.indexOf('"', start));
            }
            return loader;
        } catch (Throwable t) {
            return "?";
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (dev.android.dynamicisland.island.source.QuickShareState.PACKAGE.equals(lpparam.packageName)) {
            // opt-in: only reached when the user adds Quick Share to the LSPosed scope
            if (!quickShareInstalled && lpparam.packageName.equals(lpparam.processName)) {
                quickShareInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.QuickShareHooks.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XLog.e("quick share hooks failed", t);
                }
            }
            return;
        }
        if (EasySetupHooks.PACKAGE.equals(lpparam.packageName)) {
            // opt-in: only reached when the user adds EasySetup to the LSPosed scope
            if (!easySetupInstalled) {
                easySetupInstalled = true;
                try {
                    EasySetupHooks.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XLog.e("easysetup hooks failed", t);
                }
            }
            return;
        }
        boolean mainSystemUi = SYSTEM_UI.equals(lpparam.packageName) && SYSTEM_UI.equals(lpparam.processName);
        if (!mainSystemUi || installed) {
            return;
        }
        installed = true;
        Diagnostics.fact("entry", "module loaded into " + lpparam.processName);
        Diagnostics.fact("module", CrashGuard.BUILD + " from " + moduleApk());
        if (!CrashGuard.enter(lpparam.appInfo)) {
            Diagnostics.stage("crash guard", false, new IllegalStateException("switched the module off for this System UI process"));
            CrashGuard.publishReportLater();
            return;
        }
        Diagnostics.stage("crash guard", true, null);
        try {
            SystemUiHooks.install(lpparam.classLoader);
            Diagnostics.stage("hooks", true, null);
        } catch (Throwable t) {
            Diagnostics.stage("hooks", false, t);
            XLog.e("install failed", t);
        }
    }
}
