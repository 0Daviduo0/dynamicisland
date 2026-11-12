package dev.android.dynamicisland.island.view.card;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.BatteryGauge;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * Galaxy Buds card: the official product image in the earbuds' colour on the left, the earbuds' name, noise mode
 * and per-side battery in the middle, and a circular battery gauge on the right. When the earbuds' plugin takes
 * commands, the noise controls follow as in Galaxy Wearable: the four modes as round buttons, and for noise
 * canceling and ambient sound a slider with the mode's level.
 *
 * <pre>
 * +----------------------------------------------+
 * |  [ buds ]   Galaxy Buds3 Pro         ( 85% )  |
 * |  [image ]   L 84%  R 86%  Case 52%            |
 * |                                              |
 * |    ( )        ( )        ( )        (#)      |
 * |    Off      Ambient   Adaptive   Noise canc. |
 * |                                              |
 * |  [(o)=============o-----------------------]  |
 * +----------------------------------------------+
 * </pre>
 */
public final class BudsBody extends SliderBody {
    private static final float IMAGE_DP = 72f;
    private static final float HEADER_PAD_DP = 16f;
    private static final float GAUGE_DP = 54f;
    private static final float MODE_ICON_DP = 16f;
    private static final float MODE_BUTTON_DP = 50f;
    private static final float MODE_LABEL_GAP_DP = 6f;
    private static final float MODE_LABEL_DP = 16f;
    private static final float SLIDER_GAP_DP = 16f;
    private static final float TRACK_DP = 44f;
    private static final float BOTTOM_DP = 18f;
    /** Samsung's selected-control blue. */
    private static final int SELECTED = 0xFF3E91FF;

    private final BatteryGauge gauge = new BatteryGauge();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final float side, imageH, gaugeSize, headerH;
    /** First line (bold), second line (with the mode glyph when it is the mode), battery line; any may be null. */
    private final String first, second, batteries;
    private final boolean secondIsMode;
    private final boolean controls;
    private final String[] modeLabels;
    private final Btn[] modeButtons;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    /** One turn of the loading ring around a mode the earbuds have not taken yet. */
    private static final float SPIN_MS = 900f;

    public BudsBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width, sliderTop(owner, item), owner.dp(TRACK_DP));
        side = owner.dp(20);
        imageH = owner.dp(IMAGE_DP);
        headerH = headerHeight(owner);
        gaugeSize = item.batteryPercent >= 0 ? owner.dp(GAUGE_DP) : 0;
        controls = hasControls(item);
        String name = item.budsName != null && !item.budsName.isBlank() ? item.budsName : null;
        String mode = item.budsMode == null ? null : item.budsMode.toString();
        if (controls) {
            // the mode shows on its button
            first = name != null ? name : item.title == null ? null : item.title.toString();
            second = null;
            secondIsMode = false;
        } else if (item.budsModeFirst && mode != null) {
            first = mode;
            second = name;
            secondIsMode = false;
        } else {
            first = name != null ? name : item.title == null ? null : item.title.toString();
            second = mode;
            secondIsMode = mode != null;
        }
        batteries = batteryLine(owner, item);
        int n = IslandItem.BUDS_MODES.length;
        modeLabels = new String[n];
        modeButtons = new Btn[n];
        if (controls) layoutModes(n);
        if (hasSlider(item)) {
            h = trackTop + trackH + owner.dp(BOTTOM_DP);
        } else if (controls) {
            h = headerH + modesHeight(owner) + owner.dp(BOTTOM_DP);
        } else {
            h = headerH;
        }
    }

    public static boolean supports(IslandItem it) {
        return it.eventLayout == IslandItem.EventLayout.BUDS && it.budsImage != null;
    }

    private static boolean hasControls(IslandItem it) {
        return it.budsSetNoise != null && it.budsNoise != null && it.budsModeIcons != null
                && it.budsModeIcons.length == IslandItem.BUDS_MODES.length;
    }

    private static boolean hasSlider(IslandItem it) {
        return hasControls(it) && it.sliderChange != null;
    }

    private static float headerHeight(IslandView owner) {
        return owner.dp(IMAGE_DP) + owner.dp(HEADER_PAD_DP) * 2;
    }

    private static float modesHeight(IslandView owner) {
        return owner.dp(MODE_BUTTON_DP) + owner.dp(MODE_LABEL_GAP_DP) + owner.dp(MODE_LABEL_DP);
    }

    private static float sliderTop(IslandView owner, IslandItem item) {
        return headerHeight(owner) + modesHeight(owner) + owner.dp(SLIDER_GAP_DP);
    }

    /** Four equal columns, each with a round button over its name. */
    private void layoutModes(int n) {
        float column = (w - side * 2) / n;
        float size = owner.dp(MODE_BUTTON_DP);
        TextPaint paint = (TextPaint) owner.smallPaint;
        for (int i = 0; i < n; i++) {
            String mode = IslandItem.BUDS_MODES[i];
            float cx = side + column * (i + 0.5f);
            Btn b = new Btn();
            b.r.set(cx - size / 2f, headerH, cx + size / 2f, headerH + size);
            b.circle = true;
            boolean selected = mode.equals(item.budsNoise);
            // while a mode loads it is a paler blue, and no other mode can be picked until the earbuds answer
            b.bg = selected ? (item.budsBusy ? Colors.withAlpha(SELECTED, 0.45f) : SELECTED) : Colors.TRACK;
            b.icon = item.budsModeIcons[i];
            if (b.icon == null) b.label = String.valueOf(i);
            b.run = selected || item.budsBusy ? () -> { } : () -> item.budsSetNoise.accept(mode);
            buttons.add(b);
            modeButtons[i] = b;
            CharSequence label = item.budsModeLabels != null && i < item.budsModeLabels.length ? item.budsModeLabels[i] : null;
            modeLabels[i] = label == null ? null
                    : TextUtils.ellipsize(label, paint, column - owner.dp(4), TextUtils.TruncateAt.END).toString();
        }
    }

    private static String batteryLine(IslandView owner, IslandItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.budsLeft > 0) sb.append("L ").append(item.budsLeft).append('%');
        if (item.budsRight > 0) sb.append(sb.length() > 0 ? "  " : "").append("R ").append(item.budsRight).append('%');
        if (item.budsCase > 0) {
            sb.append(sb.length() > 0 ? "  " : "")
                    .append(IslandText.get(owner.getContext(), "island_buds_case")).append(' ').append(item.budsCase).append('%');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public boolean animating(long now) {
        return controls && item.budsBusy;
    }

    @Override
    Drawable sliderIcon() {
        return item.budsModeIcon;
    }

    @Override
    String valueText(float step) {
        return null;
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        drawHeader(c, x, y, alpha * rowAlpha(elapsed, 0));
        if (!controls) return;
        float modes = alpha * rowAlpha(elapsed, 1);
        drawButtons(c, x, y, modes);
        if (item.budsBusy) drawLoading(c, x, y, modes);
        drawModeLabels(c, x, y, modes);
        if (hasSlider(item)) drawSlider(c, x, y, alpha * rowAlpha(elapsed, 2));
    }

    private void drawHeader(Canvas c, float x, float y, float a) {
        float midY = y + headerH / 2f;
        float textLeft = x + side;
        Bitmap image = item.budsImage;
        if (image != null && !image.isRecycled()) {
            float aspect = image.getWidth() / (float) Math.max(1, image.getHeight());
            float iw = imageH * aspect;
            dst.set(x + side, midY - imageH / 2f, x + side + iw, midY + imageH / 2f);
            bitmapPaint.setAlpha(Math.round(255 * a));
            c.drawBitmap(image, null, dst, bitmapPaint);
            textLeft = dst.right + owner.dp(14);
        }
        float textRight = x + w - side - (gaugeSize > 0 ? gaugeSize + owner.dp(12) : 0);
        if (gaugeSize > 0) {
            int percent = item.batteryPercent;
            int colour = percent <= 15 ? Colors.DESTRUCTIVE_RED : 0xFF3DB653;
            gauge.draw(c, x + w - side - gaugeSize / 2f, midY, gaugeSize, percent, colour, a, true, false);
        }
        drawText(c, textLeft, Math.max(0, textRight - textLeft), midY, a);
    }

    /** A short arc running round the mode being switched to. */
    private void drawLoading(Canvas c, float x, float y, float a) {
        for (int i = 0; i < modeButtons.length; i++) {
            if (modeButtons[i] == null || !IslandItem.BUDS_MODES[i].equals(item.budsNoise)) continue;
            float stroke = owner.dp(2.5f);
            arc.set(modeButtons[i].r);
            arc.offset(x, y);
            arc.inset(-stroke * 1.6f, -stroke * 1.6f);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setStrokeWidth(stroke);
            ring.setColor(Colors.withAlpha(SELECTED, a * 0.25f));
            c.drawOval(arc, ring);
            float turn = (IslandView.now() % (long) SPIN_MS) / SPIN_MS;
            ring.setColor(Colors.withAlpha(SELECTED, a));
            c.drawArc(arc, turn * 360f - 90f, 100f, false, ring);
        }
    }

    private void drawModeLabels(Canvas c, float x, float y, float a) {
        Paint paint = owner.smallPaint;
        Paint.Align align = paint.getTextAlign();
        paint.setTextAlign(Paint.Align.CENTER);
        paint.getFontMetrics(fm);
        float baseline = y + headerH + owner.dp(MODE_BUTTON_DP) + owner.dp(MODE_LABEL_GAP_DP) - fm.ascent;
        for (int i = 0; i < modeButtons.length; i++) {
            if (modeLabels[i] == null || modeButtons[i] == null) continue;
            boolean selected = IslandItem.BUDS_MODES[i].equals(item.budsNoise);
            paint.setColor(Colors.withAlpha(selected ? Colors.WHITE : Colors.TEXT_SECONDARY, a));
            c.drawText(modeLabels[i], x + modeButtons[i].r.centerX(), baseline, paint);
        }
        paint.setTextAlign(align);
    }

    private void drawText(Canvas c, float left, float room, float midY, float a) {
        if (room < owner.dp(40)) return;
        float gap = owner.dp(4);
        float h1 = first == null ? 0 : budsLineHeight(owner.titlePaint);
        float h2 = second == null ? 0 : budsLineHeight(owner.bodyPaint);
        float h3 = batteries == null ? 0 : budsLineHeight(owner.smallPaint);
        int lines = (first != null ? 1 : 0) + (second != null ? 1 : 0) + (batteries != null ? 1 : 0);
        float top = midY - (h1 + h2 + h3 + gap * Math.max(0, lines - 1)) / 2f;
        if (first != null) {
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a));
            top = line(c, first, owner.titlePaint, left, room, top) + gap;
        }
        if (second != null) {
            float l = left;
            float r = room;
            if (secondIsMode && item.budsModeIcon != null) {
                float icon = owner.dp(MODE_ICON_DP);
                owner.drawDrawable(c, item.budsModeIcon, false, item.symbolTint(), l, top + (h2 - icon) / 2f, icon, a, 0f);
                l += icon + owner.dp(6);
                r -= icon + owner.dp(6);
            }
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_BODY, a));
            top = line(c, second, owner.bodyPaint, l, r, top) + gap;
        }
        if (batteries != null) {
            owner.smallPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            owner.smallPaint.getFontMetrics(fm);
            owner.drawTime(c, item.key + "#buds", batteries, owner.smallPaint, left, top - fm.ascent, false);
        }
    }

    /** Draws one ellipsized line with its top at {@code top}; returns its bottom. */
    private float line(Canvas c, String text, Paint paint, float left, float room, float top) {
        if (room <= 0) return top;
        paint.getFontMetrics(fm);
        String shown = TextUtils.ellipsize(text, (TextPaint) paint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(shown, left, top - fm.ascent, paint);
        return top + (fm.descent - fm.ascent);
    }

    private float budsLineHeight(Paint paint) {
        paint.getFontMetrics(fm);
        return fm.descent - fm.ascent;
    }
}
