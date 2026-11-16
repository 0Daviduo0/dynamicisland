package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.StaticLayout;
import dev.android.dynamicisland.island.graphics.BatteryGauge;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;

/** Small module-owned status/device card; unknown battery levels simply have no gauge. */
public final class EventBody extends CardBody {
    private final BatteryGauge gauge = new BatteryGauge();
    private final StaticLayout title, subtitle;
    private final float pad, icon, gaugeSize, rowHeight;

    public EventBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        pad = owner.dp(18);
        icon = item.icon == null ? 0 : owner.dp(ModuleSettings.compactEventIcons() ? 24 : 36);
        gaugeSize = item.trailing == IslandItem.Trailing.ICON && item.trailingIcon != null ? icon
                : item.eventLayout == IslandItem.EventLayout.DEVICE && item.batteryPercent >= 0 ? owner.dp(42) : 0;
        float room = width - pad * 2 - (icon == 0 ? 0 : icon + owner.dp(14)) - (gaugeSize == 0 ? 0 : gaugeSize + owner.dp(12));
        int textWidth = Math.max(1, Math.round(room));
        title = IslandView.layout(item.title, owner.titlePaint, textWidth, 2);
        subtitle = item.subtitle == null || item.subtitle.toString().equals(String.valueOf(item.title)) ? null
                : IslandView.layout(item.subtitle, owner.bodyPaint, textWidth, 2);
        float block = title.getHeight() + (subtitle == null ? 0 : owner.dp(3) + subtitle.getHeight());
        rowHeight = Math.max(Math.max(icon, gaugeSize), block);
        h = Math.max(owner.dp(76), rowHeight + pad * 2);
    }

    /** Title cut to the lane right of the camera hole, when the event is shown as a wide pill. */
    private String laneTitle;
    private float pillIcon, pillGauge;

    /**
     * Around the camera hole a system event (ringer, silent, Do Not Disturb, a device) is a wide pill, as on iOS:
     * its symbol left of the hole, its name (and gauge) right of it, any detail on one line below.
     */
    @Override
    protected float layoutBesideHole() {
        float inset = owner.dp(16);
        pillIcon = icon == 0 ? 0 : holePillH - owner.dp(10);
        pillGauge = gaugeSize == 0 ? 0 : holePillH - owner.dp(8);
        float room = rightLane(inset) - (pillGauge == 0 ? 0 : pillGauge + owner.dp(8));
        if (room < owner.dp(56) || item.title == null) return -1;
        laneTitle = TextUtils.ellipsize(item.title, owner.trailPaint, room, TextUtils.TruncateAt.END).toString();
        if (subtitle == null) return holePillH;
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bodyPaint);
        return Math.max(holePillH, holeBottom) + owner.dp(2) + (fm.descent - fm.ascent) + owner.dp(12);
    }

    private void drawPill(Canvas canvas, float x, float y, float a) {
        float midY = y + holeMid, inset = owner.dp(16);
        if (pillIcon > 0) owner.drawLeading(canvas, item, x + inset, midY, pillIcon, a);
        float right = x + w - inset;
        if (pillGauge > 0) {
            if (item.trailing == IslandItem.Trailing.ICON && item.trailingIcon != null) {
                owner.drawTrailingIcon(canvas, item, right - pillGauge / 2, midY, pillGauge, a);
            } else {
                gauge.draw(canvas, right - pillGauge / 2, midY, pillGauge, item.batteryPercent, item.fillAccent(), a, true, false);
            }
            right -= pillGauge + owner.dp(8);
        }
        TextPaint p = owner.trailPaint;
        p.setColor(Colors.withAlpha(item.accent != -1 && item.accent != 0 ? item.accent : Colors.WHITE, a));
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(p);
        canvas.drawText(laneTitle, right - p.measureText(laneTitle), midY - (fm.ascent + fm.descent) / 2, p);
        if (subtitle != null) {
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
            String line = fitted(20, subtitle.getText(), owner.bodyPaint, w - inset * 2);
            Paint.FontMetrics bm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bodyPaint);
            float top = y + Math.max(holePillH, holeBottom) + owner.dp(2);
            canvas.drawText(line, x + (w - owner.bodyPaint.measureText(line)) / 2, top - bm.ascent, owner.bodyPaint);
        }
    }

    @Override public void draw(Canvas canvas, float x, float y, float alpha, long elapsed) {
        if (holeRow) {
            drawPill(canvas, x, y, alpha * rowAlpha(elapsed, 0));
            return;
        }
        float a = alpha * rowAlpha(elapsed, 0), midY = y + (h - topInset) / 2;
        if (icon > 0) owner.drawLeading(canvas, item, x + pad, midY, icon, a);
        float left = x + pad + (icon == 0 ? 0 : icon + owner.dp(14));
        float block = title.getHeight() + (subtitle == null ? 0 : owner.dp(3) + subtitle.getHeight());
        float top = midY - block / 2;
        owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a));
        IslandView.drawLayout(canvas, title, left, top);
        owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a));
        if (subtitle != null) IslandView.drawLayout(canvas, subtitle, left, top + title.getHeight() + owner.dp(3));
        if (gaugeSize > 0 && item.trailing == IslandItem.Trailing.ICON && item.trailingIcon != null) {
            owner.drawTrailingIcon(canvas, item, x + w - pad - gaugeSize / 2, midY, gaugeSize, a);
        } else if (gaugeSize > 0) gauge.draw(canvas, x + w - pad - gaugeSize / 2, midY,
                gaugeSize, item.batteryPercent, item.fillAccent(), a, true, false);
    }
}
