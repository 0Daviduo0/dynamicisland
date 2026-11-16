package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * A contact without a photo, the way iOS shows one in a call: the initials, white, on a grey gradient disc.
 * Drawn to whatever bounds it gets, so it scales with the card.
 */
public final class Monogram extends Drawable {
    private final String initials;
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    private Monogram(String initials) {
        this.initials = initials;
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** The monogram of {@code name}, or null when it has no letters (a bare phone number). */
    public static Monogram of(CharSequence name) {
        String initials = initialsOf(name);
        return initials == null ? null : new Monogram(initials);
    }

    static String initialsOf(CharSequence name) {
        if (name == null) return null;
        StringBuilder out = new StringBuilder();
        for (String part : name.toString().trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            int cp = part.codePointAt(0);
            if (!Character.isLetter(cp)) continue;
            out.appendCodePoint(Character.toUpperCase(cp));
            if (out.codePointCount(0, out.length()) == 2) break;
        }
        return out.length() == 0 ? null : out.toString();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        disc.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xFFA5A9B4, 0xFF858993, Shader.TileMode.CLAMP));
        text.setTextSize(b.height() * (initials.length() > 1 ? 0.38f : 0.44f));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        disc.setAlpha(alpha);
        text.setAlpha(alpha);
        float r = Math.min(b.width(), b.height()) / 2f;
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, disc);
        Paint.FontMetrics fm = Fonts.metrics(text);
        c.drawText(initials, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, text);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 120; }
    @Override public int getIntrinsicHeight() { return 120; }
}
