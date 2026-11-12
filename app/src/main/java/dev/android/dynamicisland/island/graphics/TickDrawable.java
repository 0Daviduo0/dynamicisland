package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Completion tick: a green disc with a dark check, drawn in its own colours (tints are ignored). {@link #draw(Canvas,
 * float, float, float, float, float)} also draws it half-done, for the stroke-in animation of a finished transfer.
 */
public final class TickDrawable extends Drawable {
    public static final int GREEN = 0xFF34C759;
    private static final Paint DISC = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path CHECK = new Path();
    private static final Path PART = new Path();
    /** Reused: draws run on the main thread only, and a fresh PathMeasure per frame is a native allocation. */
    private static final PathMeasure MEASURE = new PathMeasure();
    private int alpha = 255;

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * @param grow   0..1 scale-in of the disc
     * @param stroke 0..1 share of the check already drawn
     */
    public static void draw(Canvas c, float cx, float cy, float size, float grow, float stroke, float alpha) {
        float r = size / 2f * Math.max(0f, grow);
        if (r <= 0f) return;
        DISC.setColor(Colors.withAlpha(GREEN, alpha));
        c.drawCircle(cx, cy, r, DISC);
        if (stroke <= 0f) return;
        CHECK.reset();
        CHECK.moveTo(cx - r * .42f, cy + r * .02f);
        CHECK.lineTo(cx - r * .12f, cy + r * .32f);
        CHECK.lineTo(cx + r * .45f, cy - r * .3f);
        PART.reset();
        if (stroke >= 1f) {
            PART.set(CHECK);
        } else {
            MEASURE.setPath(CHECK, false);
            MEASURE.getSegment(0, MEASURE.getLength() * stroke, PART, true);
        }
        STROKE.setStrokeWidth(r * .2f);
        STROKE.setColor(Colors.withAlpha(0xFF0B2A12, alpha));
        c.drawPath(PART, STROKE);
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        draw(canvas, b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()), 1f, 1f, alpha / 255f);
    }

    @Override public int getIntrinsicWidth() { return 96; }
    @Override public int getIntrinsicHeight() { return 96; }
    @Override public void setAlpha(int value) { alpha = value; }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
