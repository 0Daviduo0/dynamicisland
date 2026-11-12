package dev.android.dynamicisland.island.view.card;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.BatteryGauge;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * The Samsung Now Bar style card of the module's own everyday islands: ringing alarm, next calendar event, Galaxy
 * Watch, car and workout. Same language as the media card: a quiet header row with the source and the app icon, the
 * content in the middle, pill buttons at the bottom.
 *
 * <pre>
 * +----------------------------------------------+
 * |  (g) Galaxy Watch5                            |   header: glyph + text
 * |  ( O )  Title                        12:45   |   symbol in a translucent circle (or a product image),
 * |         Subtitle                     label   |   big rolling value at the right (or a battery ring)
 * |  ==================------------------         |   optional progress
 * |  32:10        142         4,21               |   optional stats: value over label
 * |  Time         Heart rate  Distance           |
 * |  [   Snooze   ]  [   Dismiss   ]             |   optional pill buttons
 * +----------------------------------------------+
 * </pre>
 */
public final class NowBody extends CardBody {
    private static final float SIDE_PAD_DP = 22f;
    private static final float HEADER_DP = 40f;
    private static final float HEADER_GLYPH_DP = 14f;
    private static final float LEAD_DP = 48f;
    private static final float LEAD_SYMBOL_DP = 26f;
    private static final float IMAGE_DP = 60f;
    private static final float GAUGE_DP = 52f;
    private static final float BAR_DP = 4f;
    private static final float ROW_SLIDE_DP = 8f;
    /** The big value never takes more than this share of the card. */
    private static final float VALUE_SHARE = 0.42f;
    /** The big value is a little smaller than a timer's: the card also carries text beside it. */
    private static final float VALUE_SCALE = 0.78f;

    private final BatteryGauge gauge = new BatteryGauge();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final float pad, mainTop, mainH, barTop, statsTop;
    private final boolean hasBar, hasStats;
    private final float leadW;

    public NowBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        pad = owner.dp(SIDE_PAD_DP);
        mainTop = item.nowHeader != null ? owner.dp(HEADER_DP) : owner.dp(18);
        Bitmap image = item.nowImage;
        leadW = image != null ? owner.dp(IMAGE_DP) * image.getWidth() / (float) Math.max(1, image.getHeight())
                : item.icon != null ? owner.dp(LEAD_DP) : 0;
        float text = lineH(owner.titlePaint) + (item.subtitle != null ? lineH(owner.bodyPaint) + owner.dp(2) : 0);
        float value = 0;
        if (!item.nowRing && (item.nowValue != null || item.hasChronometer())) {
            owner.bigTimePaint.getFontMetrics(fm);
            value = (fm.descent - fm.ascent) * VALUE_SCALE + (item.nowValueLabel != null ? lineH(owner.smallPaint) : 0);
        }
        mainH = Math.max(Math.max(image != null ? owner.dp(IMAGE_DP) : owner.dp(LEAD_DP), text), value);
        float top = mainTop + mainH;
        hasBar = item.progress >= 0 && !item.nowRing;
        barTop = top + owner.dp(16);
        if (hasBar) top = barTop + owner.dp(BAR_DP);
        hasStats = !item.nowStats.isEmpty();
        statsTop = top + owner.dp(14);
        if (hasStats) top = statsTop + lineH(owner.titlePaint) + lineH(owner.smallPaint);
        float buttons = layoutTextButtons(top + owner.dp(16), pad);
        h = (buttons > 0 ? top + owner.dp(16) + buttons : top) + owner.dp(20);
    }

    public static boolean supports(IslandItem it) {
        return it.eventLayout == IslandItem.EventLayout.NOW;
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float p0 = rowAlpha(elapsed, 0);
        float a0 = alpha * p0;
        float y0 = y + (1f - p0) * owner.dp(ROW_SLIDE_DP);
        drawHeader(c, x, y0, a0);
        drawMain(c, x, y0, a0);
        float p1 = rowAlpha(elapsed, 1);
        float y1 = y + (1f - p1) * owner.dp(ROW_SLIDE_DP);
        if (hasBar) drawBar(c, x, y1, alpha * p1);
        if (hasStats) drawStats(c, x, y1, alpha * p1);
        float p2 = rowAlpha(elapsed, 2);
        drawButtons(c, x, y + (1f - p2) * owner.dp(ROW_SLIDE_DP), alpha * p2);
    }

    private void drawHeader(Canvas c, float x, float y, float a) {
        float midY = y + owner.dp(HEADER_DP) / 2f + owner.dp(2);
        float left = x + pad;
        float right = x + w - pad;
        if (item.nowHeader == null) return;
        if (item.nowHeaderGlyph != null) {
            float g = owner.dp(HEADER_GLYPH_DP);
            owner.drawDrawable(c, item.nowHeaderGlyph, false, item.symbolTint(), left, midY - g / 2f, g, a, 0);
            left += g + owner.dp(7);
        }
        TextPaint p = owner.smallPaint;
        p.setColor(Colors.withAlpha(Colors.TEXT_BODY, a));
        p.getFontMetrics(fm);
        String text = ellipsize(item.nowHeader, p, right - left);
        c.drawText(text, left, midY - (fm.ascent + fm.descent) / 2f, p);
    }

    private void drawMain(Canvas c, float x, float y, float a) {
        float top = y + mainTop;
        float midY = top + mainH / 2f;
        float left = x + pad;
        Bitmap image = item.nowImage;
        if (image != null && !image.isRecycled()) {
            float ih = owner.dp(IMAGE_DP);
            dst.set(left, midY - ih / 2f, left + leadW, midY + ih / 2f);
            bitmapPaint.setAlpha(Math.round(255 * a));
            c.drawBitmap(image, null, dst, bitmapPaint);
            left = dst.right + owner.dp(14);
        } else if (item.icon != null) {
            float d = owner.dp(LEAD_DP);
            if (item.iconIsImage) {
                owner.drawDrawable(c, item.icon, true, 0, left, midY - d / 2f, d, a, item.cardImageRound ? d / 2f : owner.dp(12));
            } else {
                owner.fillPaint.setColor(Colors.withAlpha(Colors.CONTROL_FILL, a));
                c.drawCircle(left + d / 2f, midY, d / 2f, owner.fillPaint);
                float g = owner.dp(LEAD_SYMBOL_DP);
                owner.drawDrawable(c, item.icon, false, item.symbolTint(), left + (d - g) / 2f, midY - g / 2f, g, a, 0);
            }
            left += d + owner.dp(14);
        }
        float right = x + w - pad;
        if (item.nowRing && item.batteryPercent >= 0) {
            float g = owner.dp(GAUGE_DP);
            int percent = item.batteryPercent;
            int colour = percent <= 15 ? Colors.DESTRUCTIVE_RED : Colors.CALL_GREEN;
            gauge.draw(c, right - g / 2f, midY, g, percent, colour, a, true, item.batteryCharging);
            right -= g + owner.dp(12);
        } else {
            right = drawValue(c, right, top, midY, x, a);
        }
        drawTexts(c, left, right, midY, a);
    }

    /** The big value (rolling digits) at the right with its label under it; returns where the text must stop. */
    private float drawValue(Canvas c, float right, float top, float midY, float x, float a) {
        String value = item.nowValue != null ? item.nowValue : item.hasChronometer() ? owner.trailingString(item) : null;
        if (value == null || value.isEmpty()) return right;
        TextPaint big = owner.bigTimePaint;
        big.getFontMetrics(fm);
        float scale = VALUE_SCALE;
        float natural = IslandView.timeWidth(big, value);
        float maxW = w * VALUE_SHARE;
        if (natural * scale > maxW) scale = maxW / Math.max(1, natural);
        float valueH = (fm.descent - fm.ascent) * scale;
        String label = item.nowValueLabel == null ? null : item.nowValueLabel.toString();
        float labelH = label != null ? lineH(owner.smallPaint) : 0;
        float blockTop = midY - (valueH + labelH) / 2f;
        float used = natural * scale;
        // the label sits above the number, like the stock Now Bar ("Starts in" over "7:00")
        if (label != null) {
            TextPaint p = owner.smallPaint;
            p.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            p.getFontMetrics(fm);
            String shown = ellipsize(label, p, maxW);
            float lw = p.measureText(shown);
            c.drawText(shown, right - lw, blockTop - fm.ascent, p);
            used = Math.max(used, lw);
            big.getFontMetrics(fm);
        }
        float baseline = blockTop + labelH - fm.ascent * scale;
        big.setColor(Colors.withAlpha(Colors.WHITE, a));
        int save = c.save();
        c.scale(scale, scale, right, baseline);
        owner.drawTime(c, item.key + "#now", value, big, right - natural, baseline, item.chronoCountDown);
        c.restoreToCount(save);
        return right - used - owner.dp(12);
    }

    private void drawTexts(Canvas c, float left, float right, float midY, float a) {
        float room = right - left;
        if (room < owner.dp(32)) return;
        boolean sub = item.subtitle != null && item.subtitle.length() > 0;
        float h1 = lineH(owner.titlePaint);
        float h2 = sub ? lineH(owner.bodyPaint) + owner.dp(2) : 0;
        float top = midY - (h1 + h2) / 2f;
        if (item.title != null) {
            TextPaint p = owner.titlePaint;
            p.setColor(Colors.withAlpha(Colors.WHITE, a));
            p.getFontMetrics(fm);
            c.drawText(ellipsize(item.title, p, room), left, top - fm.ascent, p);
        }
        if (sub) {
            TextPaint p = owner.bodyPaint;
            p.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            p.getFontMetrics(fm);
            c.drawText(ellipsize(item.subtitle, p, room), left, top + h1 + owner.dp(2) - fm.ascent, p);
        }
    }

    private void drawBar(Canvas c, float x, float y, float a) {
        float top = y + barTop;
        float bar = owner.dp(BAR_DP);
        float left = x + pad, right = x + w - pad;
        owner.tmpRect.set(left, top, right, top + bar);
        owner.fillPaint.setColor(Colors.withAlpha(Colors.SEEK_TRACK, a));
        c.drawRoundRect(owner.tmpRect, bar / 2f, bar / 2f, owner.fillPaint);
        float p = IslandView.clamp01(item.progress);
        if (p <= 0) return;
        owner.tmpRect.set(left, top, left + Math.max(bar, (right - left) * p), top + bar);
        owner.fillPaint.setColor(Colors.withAlpha(item.customAccent != 0 ? item.customAccent : Colors.SEEK_FILL, a));
        c.drawRoundRect(owner.tmpRect, bar / 2f, bar / 2f, owner.fillPaint);
    }

    /** Up to three columns: the value in the title font (rolling digits), its label under it. */
    private void drawStats(Canvas c, float x, float y, float a) {
        int n = Math.min(3, item.nowStats.size());
        float colW = (w - pad * 2) / n;
        TextPaint vp = owner.titlePaint, lp = owner.smallPaint;
        vp.getFontMetrics(fm);
        float valueBase = y + statsTop - fm.ascent;
        float valueH = fm.descent - fm.ascent;
        lp.getFontMetrics(fm);
        float labelBase = y + statsTop + valueH - fm.ascent;
        for (int i = 0; i < n; i++) {
            String[] stat = item.nowStats.get(i);
            float left = x + pad + i * colW;
            String value = ellipsize(stat[0], vp, colW - owner.dp(8));
            vp.setColor(Colors.withAlpha(Colors.WHITE, a));
            owner.drawTime(c, item.key + "#stat" + i, value, vp, left, valueBase, false);
            if (stat.length > 1 && stat[1] != null) {
                lp.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
                c.drawText(ellipsize(stat[1], lp, colW - owner.dp(8)), left, labelBase, lp);
            }
        }
    }

    private static String ellipsize(CharSequence text, TextPaint p, float room) {
        if (text == null) return "";
        return TextUtils.ellipsize(text, p, Math.max(1, room), TextUtils.TruncateAt.END).toString();
    }

    private float lineH(Paint paint) {
        paint.getFontMetrics(fm);
        return fm.descent - fm.ascent;
    }
}
