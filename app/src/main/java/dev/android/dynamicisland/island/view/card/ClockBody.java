package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.island.view.TimeFormat;

/**
 * Samsung Clock timers and stopwatches, laid out like One UI's own ongoing-activity card for them.
 *
 * <pre>
 * +----------------------------------------------+
 * | (icon)  9:41                     (x)  (||)   |   icon: white glyph on the chip colour (#5F57D9)
 * |         10 min · Ends at 11:07               |
 * |  ======================--------------------  |   timer only: time left
 * |  ------------------------------------------  |
 * |  03          00:05.12             00:15.40  |   stopwatch only: the last three laps, newest on top
 * +----------------------------------------------+
 * </pre>
 *
 * The bar and the icon disc follow the island's theme accent (Material You or the custom colour from the
 * settings), the Clock app's own colours only when no theme accent is set. The time dims to 40 % while paused,
 * the bar is grey paused and red once the timer ran out; buttons are 30 % white discs with a white glyph.
 * A new lap slides in from the top and pushes the others down, the fourth fading out.
 */
public final class ClockBody extends CardBody {
    /** {@code timer_nowBar_icon_bg_color}, the chip colour the Clock app sends for its timer. */
    private static final int CHIP_PURPLE = 0xFF5F57D9;
    private static final int BUTTON_BG = 0x4DFCFCFF;
    private static final int TEXT_MAIN = 0xFFFCFCFF;
    private static final int TEXT_PAUSED = 0x66FFFFFF;
    private static final int TEXT_SUB = 0xB3FCFCFF;
    private static final int BAR_TRACK = 0x66C9C9CC;
    private static final int BAR_RUNNING = 0xFF7A7AFE;
    private static final int BAR_PAUSED = 0xFFA6A6A9;
    private static final int BAR_ALERT = dev.android.dynamicisland.island.graphics.Colors.TIMER_ENDED;
    private static final int MAX_BUTTONS = 3;
    /** Glyph id of the "stop" square (Glyphs.STOP, not public) for actions that only have a callback. */
    private static final int MAX_LAPS = 3;
    private static final float LAP_ANIM_MS = 320f;
    private static final int DIVIDER = 0x1FFFFFFF;

    private final float pad;
    private final float iconSize;
    private final float rowHeight;
    private final float textLeft;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** A timer: its symbol carries the time-left ring. */
    private final boolean ring;
    private final String line;
    private final float lapsTop;
    private final float lapRow;
    private final int shownLaps;
    private final android.text.TextPaint lapPaint;

    public static boolean supports(IslandItem it) {
        return it.clockKind != IslandItem.CLOCK_NONE;
    }

    public ClockBody(IslandView owner, IslandItem item, float w) {
        super(owner, item, w);
        pad = owner.dp(16);
        iconSize = owner.dp(44);
        rowHeight = owner.dp(80);
        textLeft = pad + iconSize + owner.dp(14);
        line = secondLine(item, owner.trailingString(item));
        // time left runs as a ring round the timer symbol, not as a bar under the row
        ring = item.clockKind == IslandItem.CLOCK_TIMER;
        h = rowHeight;
        lapRow = owner.dp(28);
        lapsTop = h - owner.dp(4);
        shownLaps = Math.min(MAX_LAPS, item.laps.size());
        if (shownLaps > 0) {
            h = lapsTop + owner.dp(6) + shownLaps * lapRow + owner.dp(10);
        }
        lapPaint = new android.text.TextPaint(owner.smallPaint);
        lapPaint.setFontFeatureSettings("tnum");

        float size = owner.dp(40);
        float gap = owner.dp(12);
        float right = w - pad;
        int count = Math.min(item.actions.size(), MAX_BUTTONS);
        // laid out right to left, so the first action ends up leftmost, as in the Clock app (cancel, pause)
        for (int i = count - 1; i >= 0; i--) {
            IslandItem.Action action = item.actions.get(i);
            Btn b = new Btn();
            b.action = action;
            b.circle = true;
            b.glyph = action.glyph;
            b.icon = action.glyph != 0 ? null : action.icon;
            b.label = action.title == null ? null : action.title.toString();
            b.bg = BUTTON_BG;
            if (action.runnable != null && b.icon == null && b.glyph == 0) {
                b.glyph = dev.android.dynamicisland.island.graphics.Glyphs.STOP;
            }
            b.r.set(right - size, (rowHeight - size) / 2, right, (rowHeight + size) / 2);
            right -= size + gap;
            buttons.add(b);
        }
    }

    /** The Clock app's secondary info ("10 min · Ends at 11:07", "Paused"), else the title when it is not the time. */
    private static String secondLine(IslandItem item, String time) {
        CharSequence[] candidates = {item.subtitle, item.title, item.appName};
        for (CharSequence c : candidates) {
            if (c != null && c.toString().trim().length() > 0 && !c.toString().equals(time)) {
                return c.toString().trim();
            }
        }
        return null;
    }

    /** Theme accent when the island is themed, else {@code fallback} (the Clock app's own colour). */
    private int accentOr(int fallback) {
        return item.customAccent != 0 ? item.customAccent : fallback;
    }

    /** Bar speed from which it is drawn every frame rather than once a second. */
    private static final float SMOOTH_BAR_PX_PER_S = 2f;

    private float lapProgress(long now) {
        if (item.laps.isEmpty() || item.laps.get(0).seenAt == 0) {
            return 1;
        }
        return IslandView.easeInOut(IslandView.clamp01((now - item.laps.get(0).seenAt) / LAP_ANIM_MS));
    }

    @Override
    public boolean animating(long now) {
        return lapProgress(now) < 1 || smoothBar();
    }

    /**
     * A running timer bar that would jump visibly on the once-a-second redraw (short timers: a couple of pixels or
     * more per second) glides with the frame loop instead; a long timer's bar keeps the cheap per-second tick.
     */
    private boolean smoothBar() {
        if (!ring || paused() || item.timerTotalMs <= 0) return false;
        long leftMs = TimeFormat.remainingMillis(item);
        if (leftMs == Long.MIN_VALUE || leftMs <= 0) return false;
        float pxPerSecond = (float) (Math.PI * iconSize) * 1000f / item.timerTotalMs;
        return pxPerSecond >= SMOOTH_BAR_PX_PER_S;
    }

    private boolean paused() {
        return item.trailing != IslandItem.Trailing.CHRONOMETER;
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float rowAlpha = rowAlpha(elapsed, 0);
        float a = alpha * rowAlpha;
        float dy = (1 - rowAlpha) * owner.dp(6);
        float midY = y + dy + rowHeight / 2;
        drawIcon(c, x + pad, midY, a);

        float textRight = x + (buttons.isEmpty() ? w - pad : buttons.get(buttons.size() - 1).r.left - owner.dp(10));
        // beside the camera hole the big time stops short of it; the line under it runs below the hole
        drawTexts(c, a, x + textLeft, textRight, holeRow ? Math.min(textRight, x + holeLeft) : textRight, midY);
        if (shownLaps > 0) {
            drawLaps(c, x, y + dy, a * rowAlpha(elapsed, 1));
        }
        drawButtons(c, x, y + dy, a);
    }

    /** The newest laps under a hairline; the list scrolls down by one row whenever a lap is added. */
    private void drawLaps(Canvas c, float x, float y, float a) {
        float top = y + lapsTop;
        owner.fillPaint.setColor(Colors.withAlpha(DIVIDER, a));
        c.drawRect(x + pad, top, x + w - pad, top + Math.max(1, owner.dp(0.5f)), owner.fillPaint);
        float listTop = top + owner.dp(6);
        float p = lapProgress(IslandView.now());
        int save = c.save();
        c.clipRect(x, listTop, x + w, listTop + shownLaps * lapRow);
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(lapPaint);
        float splitX = x + w * 0.5f;
        for (int i = 0; i < item.laps.size(); i++) {
            float slot = i - (1 - p);
            if (slot >= shownLaps || slot <= -1) {
                continue;
            }
            float rowA = i == 0 ? p : i >= shownLaps ? 1 - p : 1;
            IslandItem.Lap lap = item.laps.get(i);
            float baseline = listTop + slot * lapRow + lapRow / 2 - (fm.ascent + fm.descent) / 2;
            lapPaint.setTextAlign(Paint.Align.LEFT);
            lapPaint.setColor(Colors.withAlpha(TEXT_SUB, a * rowA));
            c.drawText(lap.number < 10 ? "0" + lap.number : String.valueOf(lap.number), x + pad + owner.dp(4), baseline, lapPaint);
            lapPaint.setTextAlign(Paint.Align.CENTER);
            lapPaint.setColor(Colors.withAlpha(TEXT_MAIN, a * rowA));
            c.drawText(lap.splitMs >= 0 ? TimeFormat.withHundredths(lap.splitMs) : "\u2014", splitX, baseline, lapPaint);
            lapPaint.setTextAlign(Paint.Align.RIGHT);
            lapPaint.setColor(Colors.withAlpha(TEXT_SUB, a * rowA));
            c.drawText(lap.totalMs >= 0 ? TimeFormat.withHundredths(lap.totalMs) : "\u2014", x + w - pad - owner.dp(4), baseline, lapPaint);
        }
        lapPaint.setTextAlign(Paint.Align.LEFT);
        c.restoreToCount(save);
    }

    private void drawIcon(Canvas c, float left, float midY, float a) {
        int bg = item.customAccent != 0 ? item.primaryButtonFill(IslandView.PAUSED_SPEED)
                : item.chipBg != 0 ? item.chipBg | 0xFF000000 : CHIP_PURPLE;
        float cx = left + iconSize / 2;
        float stroke = owner.dp(3.5f);
        float chip = ring ? iconSize / 2 - stroke - owner.dp(2.5f) : iconSize / 2;
        if (ring) drawRing(c, cx, midY, iconSize / 2 - stroke / 2, stroke, a);
        owner.fillPaint.setColor(Colors.withAlpha(bg, a));
        c.drawCircle(cx, midY, chip, owner.fillPaint);
        if (item.icon == null) {
            return;
        }
        float glyph = chip * 2 - owner.dp(ring ? 7 : 9) * 2;
        owner.drawDrawable(c, item.icon, false, Colors.WHITE, left + (iconSize - glyph) / 2, midY - glyph / 2, glyph, a, 0);
    }

    /**
     * Around the camera hole the row keeps its place at the top of the card, as an iOS timer activity flanks the
     * camera: the icon and the time left of the hole, the buttons right of it. No band, nothing shown twice.
     */
    @Override
    protected float layoutBesideHole() {
        boolean buttonsClear = buttons.isEmpty() || buttons.get(buttons.size() - 1).r.left >= holeRight;
        boolean timeFits = holeLeft - textLeft >= owner.dp(56);
        // the line under the time must start below the hole, or it would run into it
        boolean lineBelow = rowHeight / 2 >= holeBottom - owner.dp(2);
        if (!buttonsClear || !timeFits || !lineBelow) return -1.0f;
        // no artwork to hide: the black melts into the card over its whole height instead of ending in a stripe
        shadeFade = Math.max(owner.dp(16), h - holeBottom);
        return h;
    }

    private void drawTexts(Canvas c, float a, float left, float right, float timeRight, float midY) {
        String time = owner.trailingString(item);
        long left_ms = item.clockKind == IslandItem.CLOCK_TIMER ? TimeFormat.remainingMillis(item) : Long.MIN_VALUE;
        boolean over = left_ms != Long.MIN_VALUE && left_ms < 0;
        if (over && item.trailing == IslandItem.Trailing.CHRONOMETER) {
            // a timer that ran out counts up again in the Clock app, behind a minus sign
            time = "-" + TimeFormat.clock(-left_ms);
        }
        Paint.FontMetrics tm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bigTimePaint);
        float timeH = tm.descent - tm.ascent;
        float lineH = line != null ? owner.smallPaint.getTextSize() + owner.dp(3) : 0;
        float top = midY - (timeH + lineH) / 2;
        if (time != null) {
            int color = over ? BAR_ALERT : paused() ? TEXT_PAUSED : TEXT_MAIN;
            owner.bigTimePaint.setColor(Colors.withAlpha(color, a));
            float natural = Math.max(1, owner.bigTimePaint.measureText(time));
            float scale = Math.min(1, Math.max(1, timeRight - left) / natural);
            float baseline = top - tm.ascent;
            int save = c.save();
            c.scale(scale, scale, left, baseline);
            owner.drawTime(c, timeKey(0, "#card"), time, owner.bigTimePaint, left, baseline, item.chronoCountDown);
            c.restoreToCount(save);
        }
        if (line != null) {
            owner.smallPaint.setColor(Colors.withAlpha(TEXT_SUB, a));
            String shown = fitted(20, line, owner.smallPaint, Math.max(0, right - left));
            Paint.FontMetrics sm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.smallPaint);
            float baseline = time != null ? top + timeH + owner.dp(1) - sm.ascent : midY - (sm.ascent + sm.descent) / 2;
            c.drawText(shown, left, baseline, owner.smallPaint);
        }
    }

    /** Time left as a ring round the symbol: full at the start, empty when it rings, all red once it ran out. */
    private void drawRing(Canvas c, float cx, float cy, float radius, float stroke, float a) {
        long leftMs = TimeFormat.remainingMillis(item);
        boolean over = leftMs != Long.MIN_VALUE && leftMs < 0;
        float frac = over ? 1 : leftMs == Long.MIN_VALUE || item.timerTotalMs <= 0 ? 0
                : IslandView.clamp01(leftMs / (float) item.timerTotalMs);
        Paint p = ringPaint;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        owner.tmpRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        p.setColor(Colors.withAlpha(BAR_TRACK, a));
        c.drawArc(owner.tmpRect, 0, 360, false, p);
        if (frac <= 0) return;
        int color = over ? BAR_ALERT : paused() ? BAR_PAUSED : accentOr(BAR_RUNNING);
        p.setColor(Colors.withAlpha(color, a));
        c.drawArc(owner.tmpRect, -90, 360 * frac, false, p);
    }
}
