package dev.android.dynamicisland.island.view.card;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.BatteryGauge;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * Galaxy Buds card: the official product image in the earbuds' colour on the left, the earbuds' name, noise mode
 * and per-side battery in the middle, and a circular battery gauge on the right. A card opened by a noise mode
 * change leads with the mode.
 *
 * <pre>
 * +----------------------------------------------+
 * |  [ buds ]   Galaxy Buds3 Pro         ( 85% )  |
 * |  [image ]   (o) Noise canceling               |
 * |             L 84%  R 86%  Case 52%            |
 * +----------------------------------------------+
 * </pre>
 */
public final class BudsBody extends CardBody {
    private static final float IMAGE_DP = 84f;
    private static final float GAUGE_DP = 58f;
    private static final float MODE_ICON_DP = 16f;
    private final BatteryGauge gauge = new BatteryGauge();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final float pad, imageH, gaugeSize;
    /** First line (bold), second line (with the mode glyph when it is the mode), battery line; any may be null. */
    private final String first, second, batteries;
    private final boolean secondIsMode;

    public BudsBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        pad = owner.dp(20);
        imageH = owner.dp(IMAGE_DP);
        gaugeSize = item.batteryPercent >= 0 ? owner.dp(GAUGE_DP) : 0;
        h = imageH + owner.dp(16) * 2;
        String name = item.budsName != null && !item.budsName.isBlank() ? item.budsName : null;
        String mode = item.budsMode == null ? null : item.budsMode.toString();
        if (item.budsModeFirst && mode != null) {
            first = mode;
            second = name;
            secondIsMode = false;
        } else {
            first = name != null ? name : item.title == null ? null : item.title.toString();
            second = mode;
            secondIsMode = mode != null;
        }
        batteries = batteryLine(owner, item);
    }

    public static boolean supports(IslandItem it) {
        return it.eventLayout == IslandItem.EventLayout.BUDS && it.budsImage != null;
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
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float a = alpha * rowAlpha(elapsed, 0);
        float midY = y + h / 2f;
        float textLeft = x + pad;
        Bitmap image = item.budsImage;
        if (image != null && !image.isRecycled()) {
            float aspect = image.getWidth() / (float) Math.max(1, image.getHeight());
            float iw = imageH * aspect;
            dst.set(x + pad, midY - imageH / 2f, x + pad + iw, midY + imageH / 2f);
            bitmapPaint.setAlpha(Math.round(255 * a));
            c.drawBitmap(image, null, dst, bitmapPaint);
            textLeft = dst.right + owner.dp(14);
        }
        float textRight = x + w - pad - (gaugeSize > 0 ? gaugeSize + owner.dp(12) : 0);
        if (gaugeSize > 0) {
            int percent = item.batteryPercent;
            int colour = percent <= 15 ? Colors.DESTRUCTIVE_RED : 0xFF3DB653;
            gauge.draw(c, x + w - pad - gaugeSize / 2f, midY, gaugeSize, percent, colour, a, true, false);
        }
        drawText(c, textLeft, Math.max(0, textRight - textLeft), midY, a);
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
        String shown = TextUtils.ellipsize(text, (android.text.TextPaint) paint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(shown, left, top - fm.ascent, paint);
        return top + (fm.descent - fm.ascent);
    }

    private float budsLineHeight(Paint paint) {
        paint.getFontMetrics(fm);
        return fm.descent - fm.ascent;
    }
}
