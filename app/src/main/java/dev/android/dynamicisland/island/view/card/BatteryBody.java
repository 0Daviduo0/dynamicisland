package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Fonts;
import dev.android.dynamicisland.island.graphics.OneUiBattery;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * One UI charging card: the state with the battery cell on the left, the level as a big green number on the right,
 * a thick level bar under them, then the charging type and the optional statistics.
 */
public final class BatteryBody extends CardBody {
    private static final float BAR_DP = 10f;
    private final OneUiBattery battery = new OneUiBattery();
    private final TextPaint percentPaint;
    private final StaticLayout subtitle, details;
    private final String percent;
    private final float pad, cell, barGap;
    private float rowH, percentW;
    /** Row centre, bar top and title text: the row beside the camera hole changes them. */
    private float rowMid, barTop;
    private String title;

    public BatteryBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        pad = owner.dp(18);
        cell = owner.dp(24);
        barGap = owner.dp(12);
        percentPaint = new TextPaint(owner.titlePaint);
        percentPaint.setTypeface(Fonts.of(700));
        percent = item.batteryPercent >= 0 ? item.batteryPercent + "%" : "—";
        percentSize(30);
        int textWidth = Math.max(1, Math.round(width - pad * 2));
        subtitle = item.subtitle == null ? null : IslandView.layout(item.subtitle, owner.bodyPaint, textWidth, 2);
        details = item.description == null ? null : IslandView.layout(item.description, owner.bodyPaint,
                textWidth, Math.max(1, Math.min(8, item.descriptionMaxLines)));
        layoutPlain();
    }

    /** Sets the level's text size (dp) and the row height that follows from it. */
    private void percentSize(float dp) {
        percentPaint.setTextSize(owner.dp(dp));
        percentW = percentPaint.measureText(percent);
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(percentPaint);
        rowH = fm.descent - fm.ascent;
    }

    private String ellipsize(CharSequence text, float room) {
        return text == null || room <= 0 ? null
                : TextUtils.ellipsize(text, owner.titlePaint, room, TextUtils.TruncateAt.END).toString();
    }

    private void layoutPlain() {
        percentSize(30);
        title = ellipsize(item.title, w - pad * 2 - cell - owner.dp(10) - percentW - owner.dp(12));
        layoutBelow(pad + rowH / 2, pad + rowH + owner.dp(8));
    }

    private void layoutBelow(float mid, float bar) {
        rowMid = mid;
        barTop = bar;
        float y = barTop + owner.dp(BAR_DP);
        if (subtitle != null) y += barGap + subtitle.getHeight();
        if (details != null) y += owner.dp(12) + details.getHeight();
        h = y + pad;
    }

    @Override protected void onClearHole() {
        layoutPlain();
    }

    /** State left of the hole, level right of it, the bar right under it: nothing is shown twice. */
    @Override protected float layoutBesideHole() {
        percentSize(22); // fits the pill's height beside the hole
        float left = leftLane(pad) - cell - owner.dp(10);
        float right = rightLane(pad);
        if (left < owner.dp(40) || right < percentW) return -1;
        title = ellipsize(item.title, left);
        layoutBelow(holeMid, holeBottom + owner.dp(12));
        shadeFade = owner.dp(16);
        return h;
    }

    @Override protected float bandOverlap() {
        return pad - owner.dp(4);
    }

    @Override public void draw(Canvas canvas, float x, float y, float alpha, long elapsed) {
        float a = alpha * rowAlpha(elapsed, 0);
        int accent = item.fillAccent();
        float mid = y + rowMid;
        battery.drawCell(canvas, x + pad + cell / 2, mid, cell, item.batteryPercent, accent, item.batteryCharging, a);
        if (title != null) {
            Paint.FontMetrics tm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.titlePaint);
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a));
            canvas.drawText(title, x + pad + cell + owner.dp(10), mid - (tm.ascent + tm.descent) / 2, owner.titlePaint);
        }
        Paint.FontMetrics pm = dev.android.dynamicisland.island.graphics.Fonts.metrics(percentPaint);
        percentPaint.setColor(Colors.withAlpha(accent, a));
        canvas.drawText(percent, x + w - pad - percentW, mid - (pm.ascent + pm.descent) / 2, percentPaint);
        float bar = y + barTop;
        battery.drawBar(canvas, x + pad, bar, x + w - pad, owner.dp(BAR_DP), item.batteryPercent, accent, a);
        float next = bar + owner.dp(BAR_DP);
        if (subtitle != null) {
            next += barGap;
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            IslandView.drawLayout(canvas, subtitle, x + pad, next);
            next += subtitle.getHeight();
        }
        if (details != null) {
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_BODY, a));
            IslandView.drawLayout(canvas, details, x + pad, next + owner.dp(12));
        }
    }
}
