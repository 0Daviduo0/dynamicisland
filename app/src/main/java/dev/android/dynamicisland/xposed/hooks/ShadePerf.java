package dev.android.dynamicisland.xposed.hooks;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Small System UI performance hooks that keep the shade and the lock screen smooth while the island runs:
 * no repeated blur calls, no GPU cache trimming each time the shade closes, remembered tile animation frames
 * and resource ids, and less log spam (shade, lock screen and AOD). Each hook only skips work that would be
 * redone for nothing.
 * Hooks run on whichever thread System UI calls them from; shared state is thread-safe or main-thread only.
 */
public final class ShadePerf {
    private static final int MAX_FRAMES = 32;
    /** Frames larger than this (either side, in pixels) are not worth keeping. */
    private static final int MAX_FRAME_PX = 512;
    /** {@code ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN}. */
    private static final int TRIM_MEMORY_UI_HIDDEN = 20;
    private static final String BLUR_VIEW = "com.android.systemui.blur.SecQSNewBlurView";

    /** System UI log tags that are chatty enough to drop at the native log call. */
    private static final Set<String> NOISY_TAGS = new HashSet<>(Arrays.asList(
        "QuickPanelLog", "PanelSplitOpenAnimator", "View", "QuickTile", "SwipeToCollapseViewModel",
        "NowBarCardViewModel", "KeyguardUpdateMonitor", "NowBarGradientBlurController",
        "PluginLockMediatorImpl", "ShadeExpansionStateManager", "SecQuickSettingScene", "EditableContainer",
        "SecQpBlurController", "SpannableGrid", "BackgroundBlurDrawable", "SecPanelWindowBlurInteractor",
        "SecPanelWindowBlurBinder", "ExpandableNotifRow", "{OngoingActivityDataHelper}",
        "SecPanelSplitHelper", "{OngoingActivityController}", "ImmersiveScrollAnimator",
        "collectAsStateWithControl", "HoneySpace.OnGoingChip", "NotificationIconManager",
        "PriorityNotiScenarioManager", "LocalImageResolver", "NotificationPanelView", "KeyguardVisible",
        "StackScrollerController", "Tile.CustomTile", "LockscreenNotificationIconsOnlyController",
        "AODNotificationManager", "SamsungMumButtonViewModel", "NowBarUtils", "CentralSurfaces",
        "SamsungHomeStatusBarViewModelExt", "StackScroller", "SystemUIAnalytics",
        "SecPanelExpansionStateRepository", "SecMultiSIMPreferredBarViewModel", "ScrimController",
        "QuickTileBox", "PanelTransitionAnimator", "SamsungHomeStatusBarInteractor", "Highlights", "Bubbles",
        "SatelliteModeObserver", "TileLifecycleManager", "NavBarStateManager",
        "ImmersiveScrollPopOverAnimator", "KeyguardSecIndicationPolicy",
        "SecNotificationsQSContainerController", "HotspotTile", "BackActionInteractor",
        "BLASTBufferQueue_Java", "MultiWindowEdgeDetector", "ImeFocusController",
        // lock screen clock, widgets and AOD: thousands of lines per screen on / off on the main thread
        "SemWallpaperColorsArea", "FaceWidgetPagesController", "KeyguardFace", "KeyguardFingerprint",
        "FaceWidgetClockPage", "SecKeyguardClockPositionAlgorithm", "PluginFaceWidgetManager",
        "ComplicationWidgetHostViewContainer", "FaceWidget_updatePosition", "SecKeyguardClockRepositoryImpl",
        "MediaExpandStateObserver", "SGGLockStarManager", "WallpaperManager", "KeyguardClockProvider",
        "Navbar.Store", "NotificationIconsOnlyContainer", "AOD_CONFIG@AODConfigurationController",
        "FaceWidgetViewModel", "AdaptiveTextColorCalculator", "ViewPositionControllerManager",
        "FaceWidgetWallpaperUtilsWrapper", "SystemUITextView", "AlwaysOnDisplayEx", "WallpaperEventNotifier",
        "PluginAODManager"));
    private static final String[] NOISY_TAG_PREFIXES = {"VRI[", "NowBarItemManager", "SamsungAnalytics", "QuickTile[",
        "DigitalHorizontalClockView_", "[AOD]", "ClockBlurManager@", "ClockBlurController[", "NowBarColorModel#", "AODDataSettings["};

    /** Blur views whose blur info is already cleared, so clearing again can be skipped. */
    private static final Map<View, Boolean> BLUR_CLEARED = new WeakHashMap<>();
    /** Small tile frames by "file#density", least recently used evicted first. */
    private static final Map<String, Drawable.ConstantState> FRAMES =
            new LinkedHashMap<String, Drawable.ConstantState>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Drawable.ConstantState> eldest) {
                    return size() > MAX_FRAMES;
                }
            };
    /** Counts skipped UI_HIDDEN trims; only the first one is logged. */
    private static int skippedTrims;

    private ShadePerf() {
    }

    static void install(ClassLoader cl) {
        keepGpuCachesOnShadeClose();
        dropDebugStacks(cl);
        dropLogNoise();
        skipRepeatedBlurReset();
        rememberTileFrames();
        rememberResourceIds();
    }

    /**
     * Swallows {@code WindowManagerGlobal.trimMemory(TRIM_MEMORY_UI_HIDDEN)}: System UI gets it each time
     * the shade closes and would free the GPU caches, which the next shade open must rebuild.
     */
    private static void keepGpuCachesOnShadeClose() {
        Refl.hookAll(Refl.findClass("android.view.WindowManagerGlobal", null), "trimMemory", SafeHook.before("ui hidden trim", p -> {
            if (!Integer.valueOf(TRIM_MEMORY_UI_HIDDEN).equals(p.args[0])) {
                return;
            }
            if (skippedTrims++ == 0) {
                XLog.i("skipping UI_HIDDEN trims");
            }
            p.setResult((Object) null);
        }));
    }

    /** Stubs out the QS animation's debug-caller stack capture and the panel state logger, costly on every shade frame. */
    private static void dropDebugStacks(ClassLoader cl) {
        Refl.hookAll(cl, "com.android.systemui.qs.animator.QSAnimView", "nowWithDebugCallers", SafeHook.before("qs debug callers", p -> p.setResult("")));
        Refl.hookAll(cl, "com.android.systemui.log.QuickPanelLoggerHelper$PanelStateLogger", "log", SafeHook.before("panel state log", p -> p.setResult(null)));
    }

    static boolean isNoisy(String tag) {
        if (NOISY_TAGS.contains(tag)) {
            return true;
        }
        for (String prefix : NOISY_TAG_PREFIXES) {
            if (tag.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code Log.println_native(bufId, priority, tag, msg)}: lines of info priority or lower from a noisy tag are dropped. */
    private static void dropLogNoise() {
        Refl.hookAll(Log.class, "println_native", SafeHook.before("log noise", p -> {
            boolean droppable = p.args.length >= 4
                    && p.args[1] instanceof Integer && (Integer) p.args[1] <= Log.INFO
                    && p.args[2] instanceof String && isNoisy((String) p.args[2]);
            if (droppable) {
                p.setResult(0);
            }
        }));
    }

    /** Clearing the QS blur view's blur info again ({@code semSetBlurInfo(null)}) forces a relayout for nothing. */
    private static void skipRepeatedBlurReset() {
        int hooked = Refl.hookAll(View.class, "semSetBlurInfo", SafeHook.before("blur reset", p -> {
            if (p.args.length != 1 || !BLUR_VIEW.equals(p.thisObject.getClass().getName())) {
                return;
            }
            View blurView = (View) p.thisObject;
            if (p.args[0] != null) {
                BLUR_CLEARED.remove(blurView);
            } else if (BLUR_CLEARED.put(blurView, Boolean.TRUE) != null) {
                p.setResult((Object) null);
            }
        }));
        if (hooked == 0) {
            XLog.i("semSetBlurInfo not found");
        }
    }

    /**
     * Remembers what {@code ResourcesImpl.getIdentifier(name, type, package)} found. The lock screen clock looks
     * its resources up by name on every layout pass of the screen on / off animation. An id never changes for one
     * {@code ResourcesImpl} (overlay or configuration changes bring a new one), and a name not found is not kept.
     */
    private static void rememberResourceIds() {
        Class<?> resourcesImpl = Refl.findClass("android.content.res.ResourcesImpl", null);
        int hooked = Refl.hookAll(resourcesImpl, "getIdentifier", new SafeHook("resource ids") {
            @Override
            protected void before(XC_MethodHook.MethodHookParam p) {
                int id = ResourceIds.find(p.thisObject, p.args);
                if (id != 0) {
                    p.setResult(id);
                }
            }

            @Override
            protected void after(XC_MethodHook.MethodHookParam p) {
                if (p.getThrowable() == null && p.getResult() instanceof Integer) {
                    ResourceIds.keep(p.thisObject, p.args, (Integer) p.getResult());
                }
            }
        });
        if (hooked == 0) {
            XLog.i("ResourcesImpl.getIdentifier not found");
        }
    }

    /** Ids found by name, per {@code ResourcesImpl}; weakly held, so a dropped impl takes its ids along. */
    static final class ResourceIds {
        private static final int MAX_PER_IMPL = 512;
        private static final Map<Object, Map<String, Integer>> IDS = new WeakHashMap<>();

        private ResourceIds() {
        }

        /** {@code getIdentifier(String name, String defType, String defPackage)} arguments as a key, or null. */
        static String key(Object[] args) {
            if (args == null || args.length != 3 || !(args[0] instanceof String)) {
                return null;
            }
            for (int i = 1; i < 3; i++) {
                if (args[i] != null && !(args[i] instanceof String)) {
                    return null;
                }
            }
            return args[0] + "\0" + args[1] + "\0" + args[2];
        }

        /** The id already found for these arguments, or 0. */
        static int find(Object impl, Object[] args) {
            String key = key(args);
            if (impl == null || key == null) {
                return 0;
            }
            synchronized (IDS) {
                Map<String, Integer> ids = IDS.get(impl);
                Integer id = ids == null ? null : ids.get(key);
                return id == null ? 0 : id;
            }
        }

        static void keep(Object impl, Object[] args, int id) {
            String key = key(args);
            if (impl == null || key == null || id == 0) {
                return;
            }
            synchronized (IDS) {
                Map<String, Integer> ids = IDS.get(impl);
                if (ids == null) {
                    ids = new java.util.HashMap<>();
                    IDS.put(impl, ids);
                }
                if (ids.size() < MAX_PER_IMPL || ids.containsKey(key)) {
                    ids.put(key, id);
                }
            }
        }
    }

    /** Keeps the constant state of small {@code .webp} frames that {@code ResourcesImpl.loadDrawable} decodes. */
    private static void rememberTileFrames() {
        Class<?> resourcesImpl = Refl.findClass("android.content.res.ResourcesImpl", null);
        if (Refl.hookAll(resourcesImpl, "loadDrawable", SafeHook.after("tile frames", ShadePerf::keepFrame)) == 0) {
            XLog.i("ResourcesImpl.loadDrawable not found");
        }
    }

    private static void keepFrame(XC_MethodHook.MethodHookParam p) {
        if (!(p.getResult() instanceof BitmapDrawable)) {
            return;
        }
        TypedValue value = null;
        for (Object arg : p.args) {
            if (arg instanceof TypedValue) {
                value = (TypedValue) arg;
            }
        }
        if (value == null || value.string == null || !value.string.toString().endsWith(".webp")) {
            return;
        }
        BitmapDrawable frame = (BitmapDrawable) p.getResult();
        if (frame.getBitmap() == null || frame.getBitmap().getWidth() > MAX_FRAME_PX || frame.getBitmap().getHeight() > MAX_FRAME_PX) {
            return;
        }
        Drawable.ConstantState state = frame.getConstantState();
        if (state != null) {
            synchronized (FRAMES) {
                FRAMES.put(value.string + "#" + value.density, state);
            }
        }
    }
}
