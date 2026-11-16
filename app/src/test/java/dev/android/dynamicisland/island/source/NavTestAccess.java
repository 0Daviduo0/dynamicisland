package dev.android.dynamicisland.island.source;

import android.os.Bundle;
import dev.android.dynamicisland.island.model.IslandItem;

/** Lets view tests build a navigation item the way the ongoing-activity source does. */
public final class NavTestAccess {
    private NavTestAccess() { }

    public static void bind(IslandItem it, Bundle extras, CharSequence chip) {
        OaCardBuilder.bindNavigation(it, extras, chip);
    }
}
