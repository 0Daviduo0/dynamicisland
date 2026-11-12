package dev.android.dynamicisland.island.source;

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

/**
 * The colour the stock player (SecMediaControlPanel) themes its wave from: the art's
 * {@code WallpaperColors} primary colour, or the app icon's when there is no art.
 */
final class WaveSeed {
    private static final int SAMPLE = 64;

    private WaveSeed() {
    }

    static int of(Bitmap art, Drawable appIcon, int fallback) {
        try {
            if (art != null && !art.isRecycled()) {
                Bitmap small = Bitmap.createScaledBitmap(art, SAMPLE, SAMPLE, true);
                Bitmap soft = small.getConfig() == Bitmap.Config.HARDWARE
                        ? small.copy(Bitmap.Config.ARGB_8888, false) : small;
                int seed = WallpaperColors.fromBitmap(soft).getPrimaryColor().toArgb();
                if (soft != small && soft != art) soft.recycle();
                if (small != art) small.recycle();
                return seed;
            }
            if (appIcon != null) {
                return WallpaperColors.fromDrawable(appIcon.getConstantState() != null
                        ? appIcon.getConstantState().newDrawable().mutate() : appIcon)
                        .getPrimaryColor().toArgb();
            }
        } catch (Throwable ignored) {
            // racing/recycled bitmap: fall back to our own palette
        }
        return fallback;
    }
}
