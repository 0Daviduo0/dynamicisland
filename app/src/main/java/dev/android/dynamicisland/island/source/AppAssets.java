package dev.android.dynamicisland.island.source;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Custom-action icons for the media cards. Players push a new playback state every second or so,
 * so each icon is loaded once and later calls are served from memory. App names and icons: {@link AppLook}.
 */
final class AppAssets {
    private final Context ctx;
    /** Custom-action icons, keyed {@code package/resourceId}. */
    private final Map<String, Drawable.ConstantState> actionIcons = new HashMap<>();

    AppAssets(Context ctx) {
        this.ctx = ctx;
    }

    /** Icon of a player's custom action; resources belong to the player's package. */
    Drawable actionIcon(String pkg, int resId) {
        String key = pkg + "/" + resId;
        Drawable.ConstantState known = actionIcons.get(key);
        if (known != null) {
            return known.newDrawable().mutate();
        }
        Drawable icon = Icon.createWithResource(pkg, resId).loadDrawable(ctx);
        if (icon != null && icon.getConstantState() != null) {
            actionIcons.put(key, icon.getConstantState());
        }
        return icon;
    }

    /** Drops the action icons of players that are gone. */
    void retainActionIconsOf(Set<String> livePackages) {
        actionIcons.keySet().removeIf(key -> !livePackages.contains(key.substring(0, key.indexOf('/'))));
    }
}
