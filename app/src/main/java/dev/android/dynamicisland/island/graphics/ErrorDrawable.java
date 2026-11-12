package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Failure mark, the counterpart of {@link TickDrawable}: a red disc with a white exclamation mark, in its own colours. */
public final class ErrorDrawable extends Drawable {
    public static final int RED = 0xFFFF453A;
    private static final Paint DISC = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint MARK = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    static {
        MARK.setStyle(Paint.Style.STROKE);
        MARK.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f;
        if (r <= 0f) return;
        float a = alpha / 255f;
        DISC.setColor(Colors.withAlpha(RED, a));
        canvas.drawCircle(cx, cy, r, DISC);
        MARK.setColor(Colors.withAlpha(0xFFFFFFFF, a));
        MARK.setStrokeWidth(r * .2f);
        canvas.drawLine(cx, cy - r * .48f, cx, cy + r * .1f, MARK);
        MARK.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + r * .42f, r * .12f, MARK);
        MARK.setStyle(Paint.Style.STROKE);
    }

    @Override public int getIntrinsicWidth() { return 96; }
    @Override public int getIntrinsicHeight() { return 96; }
    @Override public void setAlpha(int value) { alpha = value; }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
