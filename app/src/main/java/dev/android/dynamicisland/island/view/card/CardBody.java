package dev.android.dynamicisland.island.view.card;

import dev.android.dynamicisland.island.view.media.MediaFx;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Laid-out content of one expanded card. The constructor of a subclass measures the text and places the
 * buttons (so {@link #h} is known before the first draw); {@link #draw} paints it. Rows fade in one after
 * another ({@link #rowAlpha}). Main thread only.
 */
public abstract class CardBody {
    /** Fade-in duration of one row, see {@link #rowAlpha}. */
    private static final float ROW_FADE_MS = 200.0f;
    /** Extra touch area around each button. */
    private static final float HIT_SLOP_DP = 6.0f;
    /** Fraction by which a fully pressed button shrinks. */
    private static final float PRESS_SHRINK = 0.14f;
    private static final float PRESS_HALO_ALPHA = 0.16f;
    private static final int MAX_TEXT_BUTTONS = 3;

    final List<Btn> buttons = new ArrayList<>();
    /** Total height of the body; set by the subclass constructor (plus {@link #topInset}). */
    public float h;
    /**
     * Empty band above the content that holds the camera hole when the island opens around it (0 = none).
     * The content is drawn and hit-tested below it; see {@link #insetTop}.
     */
    public float topInset;
    final IslandView owner;
    public final IslandItem item;
    /** Width of the body. */
    public final float w;

    CardBody(IslandView owner, IslandItem item, float w) {
        this.owner = owner;
        this.item = item;
        this.w = w;
    }

    /** Draws the body with its top-left corner at ({@code x}, {@code y}); {@code elapsed} is ms since it appeared. */
    public abstract void draw(Canvas canvas, float x, float y, float alpha, long elapsed);

    /** One shape policy for the card fill, blur surface and connecting bridge. */
    public final float cornerRadius(float maximum, float height) {
        if (ModuleSettings.concentricCorners()) return Math.min(height / 2f, owner.concentricCornerRadius(w));
        if (!ModuleSettings.customCardRoundness()) return Math.min(height / 2f,
                ModuleSettings.frostedMaterial() && !ModuleSettings.lowPerformance() ? w * (100f / 1072f) : maximum);
        // Limit tall cards so the rounding cannot cut into their first and last rows.
        return Math.min(height / 2f, owner.dp(ModuleSettings.cardCornerRadiusDp()));
    }

    /**
     * Draws what fills the whole card behind the content (e.g. the music glow) into {@code frame}, the card's
     * current shape. Separate from {@link #draw} so it follows the card while it stretches and bounces,
     * instead of being cut off or leaving a gap. Nothing by default.
     */
    public void drawBackground(Canvas canvas, RectF frame, float alpha) {
    }

    public final float density() { return owner.dp(1); }

    public MediaFx materialMusic() { return null; }
    public boolean animating(long now) { return false; }

    // ---- around the camera hole -------------------------------------------------------------------

    /**
     * The body put its first row next to the camera hole ({@link #layoutBesideHole}) instead of keeping a band
     * free above its content. The fields below are in body coordinates and valid while this is true.
     */
    public boolean holeRow;
    /** Centre line of the row next to the hole (the hole's centre), the lane edges left and right of it, its bottom. */
    public float holeMid, holeLeft, holeRight, holeBottom;
    /** Height of the compact pill: the row next to the hole is this tall. */
    public float holePillH;
    /** Black at the top of the card hides the hole: solid down to here (px from the card top), then a fade. */
    public float shadeBottom;
    /** Length of the fade under that black in px; 0 = the default. A body without artwork can ask for a long, soft one. */
    public float shadeFade;
    /** Height before any band or hole layout, to go back to. */
    private float baseH = -1.0f;
    /** How far {@link #buttons} were moved by {@link #shiftButtons}. */
    private float buttonShift;

    /** The island opens below the hole: no band, no row next to it. */
    public final void clearHole() {
        if (baseH < 0.0f) baseH = h - topInset;
        shiftButtons(0.0f);
        holeRow = false;
        topInset = 0.0f;
        shadeBottom = 0.0f;
        shadeFade = 0.0f;
        h = baseH;
        onClearHole();
    }

    /** Puts back whatever {@link #layoutBesideHole} moved, other than the buttons and the height. */
    protected void onClearHole() {
    }

    /**
     * The card opens around the camera hole: lets the body lay out a row next to it, or keeps a {@code band} px
     * band above its content when it cannot. Safe to call again.
     */
    public final void fitHole(float mid, float left, float right, float bottom, float pillH, float band) {
        clearHole();
        holeMid = mid;
        holeLeft = left;
        holeRight = right;
        holeBottom = bottom;
        holePillH = pillH;
        float fitted = layoutBesideHole();
        if (fitted > 0.0f) {
            holeRow = true;
            h = fitted;
            shadeBottom = bottom;
        } else {
            shiftButtons(0.0f);
            // the body's own top padding sits under the band instead of below it: no double gap
            insetTop(Math.max(0.0f, band - bandOverlap()));
            shadeBottom = band;
        }
    }

    /**
     * Empty space the body keeps above its first row, in px, that the black band around the camera hole may
     * cover when the body has no layout beside the hole. 0 = the content starts right under the band.
     */
    protected float bandOverlap() {
        return 0.0f;
    }

    /**
     * Lays the body out with its first row next to the camera hole ({@link #holeMid}, between 0..{@link #holeLeft}
     * and {@link #holeRight}..w, the rest below {@link #holeBottom}); returns the new height, or -1 to keep a band.
     * Must start from the body's own layout each time (it can be called again).
     */
    protected float layoutBesideHole() {
        return -1.0f;
    }

    /** Moves the buttons {@code dy} px down from where the constructor put them. */
    final void shiftButtons(float dy) {
        float by = dy - buttonShift;
        if (by != 0.0f) {
            for (Btn b : buttons) b.r.offset(0.0f, by);
        }
        buttonShift = dy;
    }

    /** Width of the lane left of the hole, from {@code inset} px in. */
    final float leftLane(float inset) {
        return Math.max(0.0f, holeLeft - inset);
    }

    /** Width of the lane right of the hole, up to {@code inset} px from the right edge. */
    final float rightLane(float inset) {
        return Math.max(0.0f, w - inset - holeRight);
    }

    /** Reserves {@code band} px above the content (replacing any earlier band) and grows {@link #h} by it. */
    public final void insetTop(float band) {
        float b = Math.max(0.0f, band);
        h += b - topInset;
        topInset = b;
    }

    /** The button under ({@code lx}, {@code ly}) in card coordinates (band included), or null. */
    public Btn hit(float lx, float ly) {
        ly -= topInset;
        float slop = owner.dp(HIT_SLOP_DP);
        for (Btn b : buttons) {
            if (lx >= b.r.left - slop && lx <= b.r.right + slop && ly >= b.r.top - slop && ly <= b.r.bottom + slop) {
                return b;
            }
        }
        return null;
    }

    /** Fade-in of the given row: row n starts n * {@code ROW_STAGGER_MS} after the body appeared. */
    float rowAlpha(long elapsed, int row) {
        return IslandView.clamp01((elapsed - (row * IslandView.ROW_STAGGER_MS)) / ROW_FADE_MS);
    }

    void drawButtons(Canvas c, float x, float y, float alpha) {
        for (Btn b : buttons) {
            owner.tmpRect.set(b.r);
            owner.tmpRect.offset(x, y);
            float press = owner.pressOf(b, item);
            int save = c.save();
            if (press != 0.0f) {
                float scale = 1.0f - (PRESS_SHRINK * press);
                c.scale(scale, scale, owner.tmpRect.centerX(), owner.tmpRect.centerY());
                if (press > 0.0f) {
                    float haloRadius = (Math.max(owner.tmpRect.width(), owner.tmpRect.height()) / 2.0f) + (b.circle ? 0.0f : owner.dp(10.0f));
                    owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, PRESS_HALO_ALPHA * alpha * Math.min(1.0f, press)));
                    if (b.circle || b.glyph != 0) {
                        c.drawCircle(owner.tmpRect.centerX(), owner.tmpRect.centerY(), haloRadius, owner.fillPaint);
                    } else {
                        c.drawRoundRect(owner.tmpRect, owner.tmpRect.height() / 2.0f, owner.tmpRect.height() / 2.0f, owner.fillPaint);
                    }
                }
            }
            drawButton(c, b, alpha);
            c.restoreToCount(save);
        }
    }

    private void drawButton(Canvas c, Btn b, float alpha) {
        if (!b.circle) {
            if (b.glyph != 0) {
                owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
                Glyphs.draw(c, b.glyph, owner.tmpRect.centerX(), owner.tmpRect.centerY(), owner.tmpRect.height() * 0.5f, owner.fillPaint);
                return;
            }
            owner.fillPaint.setColor(Colors.withAlpha(b.bg, alpha));
            c.drawRoundRect(owner.tmpRect, owner.tmpRect.height() / 2.0f, owner.tmpRect.height() / 2.0f, owner.fillPaint);
            if (b.label != null) {
                drawLabel(c, b, owner.tmpRect, alpha);
            }
            return;
        }
        owner.fillPaint.setColor(Colors.withAlpha(b.bg, alpha));
        c.drawOval(owner.tmpRect, owner.fillPaint);
        // Bare media custom actions use the same half-button icon size as previous/next. Their
        // touch bounds stay unchanged; the old 60% scale made favorites look oversized (#2).
        float contentSize = owner.tmpRect.height() * (b.bg == Colors.TRANSPARENT_MARK ? 0.5f : 0.44f);
        if (b.glyph != 0) {
            // a white fill gets a black glyph, anything else a white one
            owner.fillPaint.setColor(Colors.withAlpha(b.bg == Colors.WHITE ? Colors.BLACK : Colors.WHITE, alpha));
            if ((b.glyph == Glyphs.PLAY || b.glyph == Glyphs.PAUSE) && b.bg != Colors.TRANSPARENT_MARK) {
                contentSize = owner.tmpRect.height() * 0.4f;
            }
            Glyphs.draw(c, b.glyph, owner.tmpRect.centerX(), owner.tmpRect.centerY(), contentSize, owner.fillPaint);
            return;
        }
        if (b.icon != null) {
            contentSize *= b.iconScale;
            owner.drawDrawable(c, b.icon, false, Colors.WHITE, owner.tmpRect.centerX() - (contentSize / 2.0f), owner.tmpRect.centerY() - (contentSize / 2.0f), contentSize, alpha, 0.0f);
        } else if (b.label != null) {
            drawLabel(c, b, owner.tmpRect, alpha);
        }
    }

    private void drawLabel(Canvas c, Btn b, RectF bounds, float alpha) {
        // a light, solid fill (white or a pale accent) gets black text so the label stays readable
        boolean light = Colors.isLightFill(b.bg);
        android.text.TextPaint paint = owner.buttonPaint;
        paint.setColor(Colors.withAlpha(light ? Colors.BLACK : Colors.WHITE, alpha));
        float room = bounds.width() - owner.dp(14.0f);
        if (b.fitLabel == null || b.fitWidth != room || b.fitSize != paint.getTextSize()) {
            b.fitLabel = fit(b.label, paint, room);
            b.fitWidth = room;
            b.fitSize = paint.getTextSize();
        }
        c.drawText(b.fitLabel, bounds.centerX(), centerBaseline(paint, bounds.centerY()), paint);
    }

    // ---- text helpers shared by the bodies (main thread only) ----------------------------------------

    private static final Paint.FontMetrics FM = new Paint.FontMetrics();

    /** One remembered ellipsize result, see {@link #fitted}. */
    private static final class Fit {
        String source, shown;
        float room = -1, size = -1;
        android.graphics.Typeface face;
    }
    private Fit[] fits = new Fit[0];
    private String[] timeKeys = new String[0];

    /**
     * {@link #fit} for draw code: the result for call site {@code slot} is kept and only recomputed when the text,
     * room or font change, so a card drawn 60 times a second ellipsizes once instead of every frame.
     */
    final String fitted(int slot, CharSequence text, android.text.TextPaint paint, float room) {
        if (slot >= fits.length) fits = java.util.Arrays.copyOf(fits, slot + 4);
        Fit f = fits[slot];
        if (f == null) fits[slot] = f = new Fit();
        if (f.shown != null && f.room == room && f.size == paint.getTextSize() && f.face == paint.getTypeface()
                && TextUtils.equals(f.source, text)) {
            return f.shown;
        }
        f.source = text == null ? null : text.toString();
        f.room = room;
        f.size = paint.getTextSize();
        f.face = paint.getTypeface();
        f.shown = fit(text, paint, room);
        return f.shown;
    }

    /** {@code item.key + suffix}, built once per body and call site: rolling-digit fields are keyed by it every frame. */
    final String timeKey(int slot, String suffix) {
        if (slot >= timeKeys.length) timeKeys = java.util.Arrays.copyOf(timeKeys, slot + 4);
        String k = timeKeys[slot];
        if (k == null) timeKeys[slot] = k = item.key + suffix;
        return k;
    }

    /** {@code text} cut with an ellipsis to {@code room} px; "" for null. Call at layout, not on every frame. */
    static String fit(CharSequence text, android.text.TextPaint paint, float room) {
        if (text == null) return "";
        return TextUtils.ellipsize(text, paint, Math.max(0.0f, room), TextUtils.TruncateAt.END).toString();
    }

    /** Baseline that centres one line of {@code paint} on {@code mid}, without allocating font metrics. */
    static float centerBaseline(Paint paint, float mid) {
        paint.getFontMetrics(FM);
        return mid - (FM.ascent + FM.descent) / 2.0f;
    }

    /** Font metrics of {@code paint} in a shared object: read them at once, the next call overwrites them. */
    static Paint.FontMetrics metrics(Paint paint) {
        paint.getFontMetrics(FM);
        return FM;
    }

    /**
     * Adds up to three equally wide pill buttons with the action titles in one row starting at {@code top},
     * leaving {@code sidePad} at both sides. Returns the row height, or 0 when there are no actions.
     */
    float layoutTextButtons(float top, float sidePad) {
        int count = Math.min(MAX_TEXT_BUTTONS, item.actions.size());
        if (count == 0) {
            return 0.0f;
        }
        float gap = owner.dp(8.0f);
        float buttonWidth = ((w - (2.0f * sidePad)) - ((count - 1) * gap)) / count;
        float rowHeight = owner.dp(40.0f);
        for (int i = 0; i < count; i++) {
            IslandItem.Action action = item.actions.get(i);
            Btn btn = new Btn();
            btn.r.set((i * (buttonWidth + gap)) + sidePad, top, (i * (buttonWidth + gap)) + sidePad + buttonWidth, top + rowHeight);
            btn.label = action.title != null ? action.title.toString() : null;
            btn.bg = action.destructive ? Colors.DESTRUCTIVE_RED : action.affirmative ? (item.accent != -1 ? item.accent : item.fillAccent()) : item.customAccent != 0
                    ? Colors.withAlpha(item.fillAccent(), android.graphics.Color.alpha(Colors.TRACK) / 255f) : Colors.TRACK;
            btn.action = action;
            buttons.add(btn);
        }
        return rowHeight;
    }
}
