package dev.android.dynamicisland.island.source;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import dev.android.dynamicisland.xposed.XLog;
import java.util.HashMap;
import java.util.Map;

/**
 * Name and icon of an app, looked up once and cached. One instance is shared by every source that shows
 * cards for other apps, so each package is resolved once per process instead of once per source.
 */
final class AppLook {
    private static AppLook shared;

    private final Context ctx;
    /** A null value marks a package whose label could not be resolved. */
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, Drawable.ConstantState> icons = new HashMap<>();

    private AppLook(Context ctx) {
        this.ctx = ctx;
    }

    static synchronized AppLook shared(Context ctx) {
        if (shared == null) {
            Context app = ctx.getApplicationContext();
            shared = new AppLook(app != null ? app : ctx);
        }
        return shared;
    }

    /** The launcher label of {@code pkg}; the package name itself when it cannot be resolved. */
    String label(String pkg) {
        String label = labelOrNull(pkg);
        return label != null ? label : pkg;
    }

    /** The launcher label of {@code pkg}, or null when the package is unknown. */
    synchronized String labelOrNull(String pkg) {
        if (pkg == null) {
            return null;
        }
        if (labels.containsKey(pkg)) {
            return labels.get(pkg);
        }
        String label = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            // unknown package
        }
        labels.put(pkg, label);
        return label;
    }

    /** A fresh copy of the app icon, or null. */
    synchronized Drawable icon(String pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            Drawable.ConstantState cs = icons.get(pkg);
            if (cs != null) {
                return cs.newDrawable().mutate();
            }
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d.getConstantState() != null) {
                icons.put(pkg, d.getConstantState());
            }
            return d;
        } catch (Throwable t) {
            XLog.once("AppLook.icon", t);
            return null;
        }
    }
}
