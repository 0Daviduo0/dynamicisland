package dev.android.dynamicisland.island.graphics;

import android.graphics.Color;

/**
 * Colour palette of the island ({@code 0xAARRGGBB}; whites at a given alpha are named for their use)
 * plus small colour helpers. Stateless, any thread.
 */
public final class Colors {
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRACK = 0x2EFFFFFF;
    public static final int TEXT_SECONDARY = 0x99FFFFFF;
    public static final int TEXT_BODY = 0xD9FFFFFF;
    public static final int TEXT_ARTIST = 0x8CFFFFFF;
    public static final int SEEK_FILL = 0xF2FFFFFF;
    public static final int SEEK_TRACK = 0x33FFFFFF;
    public static final int CONTROL_FILL = 0x24FFFFFF;
    public static final int BUTTON_ACTION_FILL = 0x32FFFFFF;
    public static final int CARD_STROKE = 0x40FFFFFF;
    public static final int PILL_OUTLINE = 0x47FFFFFF;
    public static final int RIM_TOP = 0x38FFFFFF;
    public static final int RIM_BOTTOM = 0x0FFFFFFF;
    public static final int CALL_GREEN = 0xFF3DB653; // One UI call accept green
    public static final int BUTTON_GRAY = 0xFF3B3B3B; // neutral One UI grey
    public static final int DESTRUCTIVE_RED = 0xFFF0453A; // One UI end call / delete red
    public static final int CARD_GLASS = 0xFF181818;
    public static final int CARD_SOLID = 0xFF1F1F1F; // One UI dark notification surface
    /** Marks a button without a fill (the glyph is drawn alone). */
    public static final int TRANSPARENT_MARK = 0x00FFFFFF;
    public static final int SEGMENT_UNREACHED = 0xFF9B9F9F;
    /** Recording dot, waveform and send-progress red. */
    public static final int RECORDING_RED = 0xFFFF5B62;
    /** A timer that ran out, and the clock card's alert bar. */
    public static final int TIMER_ENDED = 0xFFFC6C65;

    private static final int MAX_LIFT_PASSES = 12;
    private static final float MIN_LUMINANCE_ON_BLACK = 0.22f;
    private static final float LIFT_STEP = 0.12f;

    private Colors() {
    }

    /** Relative luminance (WCAG weights) of an sRGB colour, 0..1; alpha is ignored. */
    static float luminance(int color) {
        return 0.2126f * toLinear(Color.red(color))
                + 0.7152f * toLinear(Color.green(color))
                + 0.0722f * toLinear(Color.blue(color));
    }

    /** sRGB channel (0..255) to linear light. */
    private static float toLinear(int channel) {
        float c = channel / 255.0f;
        if (c <= 0.04045f) {
            return c / 12.92f;
        }
        return (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    /** A light, nearly opaque fill (white or a pale accent): text on it should be black. */
    public static boolean isLightFill(int color) {
        return Color.alpha(color) > 200 && luminance(color) > 0.5f;
    }

    /** Opaque version of {@code color}, lightened towards white until it is readable on a black island. */
    public static int readableOnBlack(int color) {
        int lifted = color | BLACK;
        for (int pass = 0; pass < MAX_LIFT_PASSES && luminance(lifted) < MIN_LUMINANCE_ON_BLACK; pass++) {
            lifted = blend(lifted, WHITE, LIFT_STEP);
        }
        return lifted;
    }

    /** Per-channel linear mix: {@code from} at t = 0, {@code to} at t = 1. */
    public static int blend(int from, int to, float t) {
        float keep = 1.0f - t;
        int a = Math.round((from >>> 24) * keep + (to >>> 24) * t);
        int r = Math.round(((from >> 16) & 255) * keep + ((to >> 16) & 255) * t);
        int g = Math.round(((from >> 8) & 255) * keep + ((to >> 8) & 255) * t);
        int b = Math.round((from & 255) * keep + (to & 255) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Scales the alpha channel of {@code color} by {@code alpha} (clamped to 0..1). */
    public static int withAlpha(int color, float alpha) {
        float factor = Math.max(0.0f, Math.min(1.0f, alpha));
        int scaled = Math.round(Color.alpha(color) * factor);
        return (color & 0x00FFFFFF) | (scaled << 24);
    }
}
