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
    private static boolean launcherInstalled;
    private static boolean briefPopupInstalled;
    private static boolean clockInstalled, mcfdsInstalled, voiceInstalled;

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
        if (dev.android.dynamicisland.xposed.hooks.LauncherHooks.PACKAGE.equals(lpparam.packageName)) {
            if (!launcherInstalled && lpparam.packageName.equals(lpparam.processName)) {
                launcherInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.LauncherHooks.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XLog.e("launcher hooks failed", t);
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
        if (dev.android.dynamicisland.xposed.hooks.ClockAlertHooks.PACKAGE.equals(lpparam.packageName)) {
            // opt-in: only reached when the user adds Clock to the LSPosed scope
            if (!clockInstalled && lpparam.packageName.equals(lpparam.processName)) {
                clockInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.ClockAlertHooks.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XLog.e("clock hooks failed", t);
                }
            }
            return;
        }
        if (dev.android.dynamicisland.xposed.hooks.VoiceRecorderHooks.PACKAGE.equals(lpparam.packageName)) {
            // opt-in: Voice Recorder's input level for the island's waveform
            if (!voiceInstalled && lpparam.packageName.equals(lpparam.processName)) {
                voiceInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.VoiceRecorderHooks.install();
                } catch (Throwable t) {
                    XLog.e("voice recorder hooks failed", t);
                }
            }
            return;
        }
        if (dev.android.dynamicisland.xposed.hooks.ClockAlertHooks.MCFDS_PACKAGE.equals(lpparam.packageName)) {
            // opt-in: Auto Switch's earbuds pop-ups, only when MCFDs is in the LSPosed scope
            if (!mcfdsInstalled && lpparam.packageName.equals(lpparam.processName)) {
                mcfdsInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.ClockAlertHooks.install(
                            dev.android.dynamicisland.island.source.AlertBridge.SOURCE_AUTOSWITCH);
                } catch (Throwable t) {
                    XLog.e("mcfds hooks failed", t);
                }
            }
            return;
        }
        if (SYSTEM_UI.equals(lpparam.packageName) && dev.android.dynamicisland.xposed.hooks.BriefPopupHole.PROCESS.equals(lpparam.processName)) {
            // the brief notification pop-up lives in its own System UI process; no crash guard here, it
            // shares its state file with the main process (every hook body is contained by SafeHook)
            if (!briefPopupInstalled) {
                briefPopupInstalled = true;
                try {
                    dev.android.dynamicisland.xposed.hooks.BriefPopupHole.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XLog.e("brief popup hooks failed", t);
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
