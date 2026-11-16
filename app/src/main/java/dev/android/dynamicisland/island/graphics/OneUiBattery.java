package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * One UI style battery: a rounded horizontal cell with a small cap, filled to the level in the charging green, with
 * the bolt cut over it while charging. Used by the compact pill and by the charging card's bar.
 */
public final class OneUiBattery {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF fill = new RectF();

    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    /**
     * The status bar battery of One UI: a pill (fully rounded, no cap), the level filled from the left in
     * {@code colour}, the bolt over it while charging. {@code width} wide, centred on ({@code cx}, {@code cy}).
     */
    public void drawCell(Canvas canvas, float cx, float cy, float width, int percent, int colour, boolean charging, float alpha) {
        if (width <= 0) return;
        float height = width * 0.5f;
        float r = height / 2f;
        body.set(cx - width / 2f, cy - r, cx + width / 2f, cy + r);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Colors.withAlpha(Colors.WHITE, alpha * 0.3f));
        canvas.drawRoundRect(body, r, r, paint);
        int level = Math.max(0, Math.min(100, percent));
        if (level > 0) {
            int save = canvas.save();
            canvas.clipRect(body.left, body.top, body.left + body.width() * level / 100f, body.bottom);
            paint.setColor(Colors.withAlpha(colour, alpha));
            canvas.drawRoundRect(body, r, r, paint);
            canvas.restoreToCount(save);
        }
        if (charging) {
            paint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
            Glyphs.draw(canvas, Glyphs.BOLT, body.centerX(), cy, height * 0.85f, paint);
        }
    }

    /** A thick level bar (the card's), rounded at both ends, from {@code left} to {@code right}. */
    public void drawBar(Canvas canvas, float left, float top, float right, float height, int percent, int colour, float alpha) {
        float r = height / 2f;
        body.set(left, top, right, top + height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Colors.withAlpha(colour, alpha * 0.2f));
        canvas.drawRoundRect(body, r, r, paint);
        int level = Math.max(0, Math.min(100, percent));
        if (level <= 0) return;
        fill.set(left, top, left + Math.max(height, (right - left) * level / 100f), top + height);
        paint.setColor(Colors.withAlpha(colour, alpha));
        canvas.drawRoundRect(fill, r, r, paint);
    }
}
