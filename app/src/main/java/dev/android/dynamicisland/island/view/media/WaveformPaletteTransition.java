package dev.android.dynamicisland.island.view.media;

import dev.android.dynamicisland.island.graphics.ArtworkPalette;
import dev.android.dynamicisland.island.graphics.Easing;

/**
 * Main-thread, allocation-free playback of a three-stop palette cross-fade. Independent of Android so
 * snapshot ordering and interrupted fades can also be tested on a plain JVM. No caller-owned arrays.
 */
final class WaveformPaletteTransition {
    static final long FADE_MS = 500L;

    private boolean primed;
    private long lastSeq;
    private long start;
    private int fromFirst;
    private int fromMiddle;
    private int fromLast;
    private int toPrimary;
    private int toFirst;
    private int toMiddle;
    private int toLast;

    /** Null palette is exactly a solid accent, without allocating a fallback on every sync. */
    void sync(ArtworkPalette palette, int accent, long seq, long now) {
        if (primed && seq < lastSeq) {
            return;
        }
        lastSeq = seq;
        int solid = accent | 0xFF000000; // ArtworkPalette.solid(accent), without the allocation
        int primary = palette == null ? solid : palette.primary;
        int first = palette == null ? solid : palette.first;
        int middle = palette == null ? solid : palette.middle;
        int last = palette == null ? solid : palette.last;
        if (!primed) {
            primed = true;
            fromFirst = first;
            fromMiddle = middle;
            fromLast = last;
            start = now - FADE_MS;
        } else {
            if (toPrimary == primary && toFirst == first && toMiddle == middle && toLast == last) {
                return; // repeated snapshots must not restart the fade
            }
            // Capture the DISPLAYED stops, not the previous target: an interrupted fade cannot jump.
            float t = fraction(now);
            fromFirst = dev.android.dynamicisland.island.graphics.Colors.blend(fromFirst, toFirst, t);
            fromMiddle = dev.android.dynamicisland.island.graphics.Colors.blend(fromMiddle, toMiddle, t);
            fromLast = dev.android.dynamicisland.island.graphics.Colors.blend(fromLast, toLast, t);
            start = now;
        }
        toPrimary = primary;
        toFirst = first;
        toMiddle = middle;
        toLast = last;
    }

    boolean running(long now) {
        return primed && now - start < FADE_MS;
    }

    /** Samples the same three evenly spaced stops used by the horizontal shader. No allocations. */
    int colorAt(float position, long now) {
        float t = fraction(now);
        int first = dev.android.dynamicisland.island.graphics.Colors.blend(fromFirst, toFirst, t);
        int middle = dev.android.dynamicisland.island.graphics.Colors.blend(fromMiddle, toMiddle, t);
        int last = dev.android.dynamicisland.island.graphics.Colors.blend(fromLast, toLast, t);
        float p = Float.isNaN(position) ? 0.0f : Easing.clamp01(position);
        return p <= 0.5f ? dev.android.dynamicisland.island.graphics.Colors.blend(first, middle, p * 2.0f)
                : dev.android.dynamicisland.island.graphics.Colors.blend(middle, last, (p - 0.5f) * 2.0f);
    }

    private float fraction(long now) {
        return Easing.easeInOut((now - start) / (float) FADE_MS);
    }
}
