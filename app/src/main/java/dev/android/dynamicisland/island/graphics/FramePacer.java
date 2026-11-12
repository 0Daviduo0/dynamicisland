package dev.android.dynamicisland.island.graphics;

/** A per-surface render budget. Time remains real elapsed time; skipped frames never slow down clocks or springs. */
public final class FramePacer {
    private static final long INTERACTIVE_NS = 1_000_000_000L / 60;
    /**
     * Ambient motion (music waveform, glow, pulsing dot, spinners) never needs the panel's 120 Hz: 30 fps looks the
     * same for an audio level and costs a quarter of the frames. Always on; the "limit" setting lowers it further.
     */
    private static final long CONTINUOUS_NS = 1_000_000_000L / 30;
    private static final long CONTINUOUS_LIMITED_NS = 1_000_000_000L / 24;
    /** Vsync timestamps can differ by a few microseconds between refresh cycles. */
    private static final long TOLERANCE_NS = 100_000;
    private long lastRendered;

    /** The ambient budget as a display rate, so the panel itself can slow down while only ambient motion runs. */
    public static float ambientFps(boolean limited) {
        return limited ? 24f : 30f;
    }

    public boolean allow(boolean limited, boolean interactive, long frameNanos) {
        long interval = interval(limited, interactive);
        if (lastRendered != 0 && frameNanos >= lastRendered && frameNanos - lastRendered + TOLERANCE_NS < interval) return false;
        lastRendered = frameNanos;
        return true;
    }

    /** Delay before requesting vsync. Floor rounding lets the display choose the next suitable frame. */
    public long delayMillis(boolean limited, boolean interactive, long nowNanos) {
        if (lastRendered == 0 || nowNanos < lastRendered) return 0;
        long interval = interval(limited, interactive);
        if (interval == 0) return 0;
        // a paced callback is never immediate: with a clock that has not moved (or less than 1 ms left) a 0 delay
        // would refuse the same frame time over and over
        return Math.max(1, Math.max(0, interval - (nowNanos - lastRendered)) / 1_000_000);
    }

    public void reset() { lastRendered = 0; }

    private static long interval(boolean limited, boolean interactive) {
        if (interactive) return limited ? INTERACTIVE_NS : 0;
        return limited ? CONTINUOUS_LIMITED_NS : CONTINUOUS_NS;
    }
}
