package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

/** Static circular level gauge shared by compact and expanded battery presentations. */
public final class BatteryGauge {
    private int shownPercent = Integer.MIN_VALUE;
    private String shownText;
    /** Semibold UI face for the number; Paints replaces it when the system font changes. Main thread only. */
    private static android.graphics.Typeface numberFace;

    public static void setNumberFace(android.graphics.Typeface face) {
        numberFace = face;
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public void setColorFilter(ColorFilter filter) { stroke.setColorFilter(filter); text.setColorFilter(filter); }

    public void draw(Canvas canvas, float cx, float cy, float diameter, int percent, int colour,
                     float alpha, boolean number, boolean bolt) {
        if (diameter <= 0) return;
        float thickness = Math.max(1.5f, diameter * .08f);
        float radius = (diameter - thickness) / 2f;
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(thickness);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(Colors.withAlpha(colour, alpha * .19f));
        canvas.drawCircle(cx, cy, radius, stroke);
        if (percent > 0) {
            stroke.setColor(Colors.withAlpha(colour, alpha));
            canvas.drawArc(bounds, -90, Math.min(100, percent) * 3.6f, false, stroke);
        }
        text.setStyle(Paint.Style.FILL);
        text.setColor(Colors.withAlpha(number ? Colors.WHITE : colour, alpha));
        if (number) {
            if (percent != shownPercent || shownText == null) {
                shownPercent = percent;
                shownText = percent >= 0 ? percent + "%" : "—";
            }
            String value = shownText;
            // Fonts.of reads reflective fields and creates a Typeface: once per font change, not per frame
            android.graphics.Typeface face = numberFace;
            if (face == null) numberFace = face = Fonts.of(600);
            text.setTypeface(face);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(diameter * .28f);
            Paint.FontMetrics fm = Fonts.metrics(text);
            canvas.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        } else if (bolt) {
            Glyphs.draw(canvas, Glyphs.BOLT, cx, cy, diameter * .5f, text);
        }
    }
}
