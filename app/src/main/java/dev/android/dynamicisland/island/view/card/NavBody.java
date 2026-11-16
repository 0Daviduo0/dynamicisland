package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Fonts;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.model.NavFacts;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * Turn-by-turn card, laid out like iOS Maps in the Dynamic Island: a big manoeuvre arrow on the leading side,
 * then the distance (bold), the instruction with its road in bold, and the arrival time; a thin route bar under
 * them. No app name and nothing in the top corners: the arrow says what this is, and the content starts right
 * under the camera when the card opens around it. Leaving the route is a small round button, not a full row.
 *
 * <pre>
 * +---------------------------------------------+
 * |  ___   300 m                                |
 * | |  ^|  Turn right onto Via Roma         (x) |
 * | |___|  Arrival 13:01 . 12 min               |
 * |  ==========o------------------------------  |
 * +---------------------------------------------+
 * </pre>
 */
public final class NavBody extends CardBody {
    private static final float ROW_SLIDE_DP = 8.0f;

    private final float pad, arrow, gap, close;
    private final float textX, textW;
    /** Offsets from the body top of each row; -1 when the row is absent. */
    private final float distanceY, instructionY, arrivalY, blockTop, blockH;
    private float barY = -1.0f;
    private final String distance;
    private final StaticLayout instruction;
    private final TextPaint instructionPaint, distancePaint;
    private final String arrival;
    /** Content moves by this (px) when the card opens around the camera. */
    private float shift;

    public static boolean supports(IslandItem it) {
        return it.kind == IslandItem.Kind.NAVIGATION && it.nav != null;
    }

    public NavBody(IslandView owner, IslandItem item, float w) {
        super(owner, item, w);
        NavFacts nav = item.nav;
        pad = owner.dp(18.0f);
        arrow = owner.dp(50.0f);
        gap = owner.dp(14.0f);
        close = item.actions.isEmpty() ? 0.0f : owner.dp(38.0f);
        textX = pad + arrow + gap;
        textW = Math.max(1.0f, w - textX - pad - (close > 0 ? close + owner.dp(10.0f) : 0.0f));

        distancePaint = new TextPaint(owner.titlePaint);
        distancePaint.setTypeface(Fonts.of(700));
        distancePaint.setTextSize(sp(15.0f));
        instructionPaint = new TextPaint(owner.titlePaint);
        instructionPaint.setTypeface(Fonts.of(400));
        instructionPaint.setTextSize(sp(19.0f));

        distance = nav.distance == null ? null : nav.distance.toString();
        CharSequence words = nav.instruction;
        if (nav.roadStart >= 0 && nav.roadEnd <= words.length()) {
            SpannableString bold = new SpannableString(words);
            bold.setSpan(new TypefaceSpan(Fonts.of(700)), nav.roadStart, nav.roadEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            words = bold;
        }
        instruction = words.length() == 0 ? null : IslandView.layout(words, instructionPaint, Math.round(textW), 3);
        String eta = nav.minutesLeft(System.currentTimeMillis());
        String arrivalText = nav.arrival == null ? null : nav.arrival.toString();
        if (eta != null && !"0 min".equals(eta)) arrivalText = arrivalText == null ? eta : arrivalText + " · " + eta;
        arrival = arrivalText;

        float y = 0.0f;
        distanceY = distance == null ? -1.0f : y;
        if (distance != null) y += lineHeight(distancePaint) + owner.dp(1.0f);
        instructionY = instruction == null ? -1.0f : y;
        if (instruction != null) y += instruction.getHeight();
        arrivalY = arrival == null ? -1.0f : y + owner.dp(4.0f);
        if (arrival != null) y = arrivalY + lineHeight(owner.bodyPaint);
        float text = y;
        blockH = Math.max(arrow, text);
        blockTop = pad;
        float bottom = blockTop + blockH;
        if (item.progress >= 0.0f) {
            barY = bottom + owner.dp(14.0f);
            bottom = barY + owner.dp(6.0f);
        }
        if (close > 0) {
            Btn b = new Btn();
            float cx = w - pad - close / 2.0f;
            float cy = blockTop + blockH / 2.0f;
            b.r.set(cx - close / 2.0f, cy - close / 2.0f, cx + close / 2.0f, cy + close / 2.0f);
            b.circle = true;
            b.glyph = Glyphs.CLOSE;
            b.bg = Colors.BUTTON_ACTION_FILL;
            b.action = item.actions.get(0);
            buttons.add(b);
        }
        h = bottom + pad;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, owner.getResources().getDisplayMetrics());
    }

    private static float lineHeight(Paint paint) {
        Paint.FontMetrics fm = Fonts.metrics(paint);
        return fm.descent - fm.ascent;
    }

    /** Around the camera nothing sits beside it: the content starts right under it, with no header row. */
    @Override
    protected float layoutBesideHole() {
        shift = Math.max(0.0f, holeBottom + owner.dp(4.0f) - blockTop);
        shiftButtons(shift);
        return h + shift;
    }

    @Override
    protected void onClearHole() {
        shift = 0.0f;
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float a0 = alpha * rowAlpha(elapsed, 0);
        float dy0 = (1.0f - rowAlpha(elapsed, 0)) * owner.dp(ROW_SLIDE_DP);
        float by = y;
        y += shift;
        float top = y + blockTop + dy0;
        if (item.icon != null) {
            owner.drawDrawable(c, item.icon, item.iconIsImage, Colors.WHITE, x + pad, top + (blockH - arrow) / 2.0f, arrow, a0, 0.0f);
        }
        float textTop = top + (blockH - textHeight()) / 2.0f;
        float left = x + textX;
        if (distance != null) {
            distancePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            String shown = fitted(20, distance, distancePaint, textW);
            c.drawText(shown, left, textTop + distanceY - Fonts.metrics(distancePaint).ascent, distancePaint);
        }
        if (instruction != null) {
            instructionPaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            IslandView.drawLayout(c, instruction, left, textTop + instructionY);
        }
        if (arrival != null) {
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a0));
            String shown = fitted(21, arrival, owner.bodyPaint, textW);
            c.drawText(shown, left, textTop + arrivalY - Fonts.metrics(owner.bodyPaint).ascent, owner.bodyPaint);
        }
        if (barY >= 0.0f) {
            float a1 = rowAlpha(elapsed, 1);
            drawRoute(c, x, y + barY + (1.0f - a1) * owner.dp(ROW_SLIDE_DP), alpha * a1);
        }
        if (!buttons.isEmpty()) {
            drawButtons(c, x, by + dy0, a0);
        }
    }

    private float textHeight() {
        float bottom = 0.0f;
        if (arrival != null) bottom = arrivalY + lineHeight(owner.bodyPaint);
        else if (instruction != null) bottom = instructionY + instruction.getHeight();
        else if (distance != null) bottom = distanceY + lineHeight(distancePaint);
        return bottom;
    }

    /** Thin route line: travelled part dim, the rest in the route colour, a dot where the car is. */
    private void drawRoute(Canvas c, float x, float top, float alpha) {
        float left = x + pad;
        float width = w - pad * 2.0f;
        float barH = owner.dp(4.0f);
        float at = left + width * IslandView.clamp01(item.progress);
        if (item.hasSegments()) {
            drawLegs(c, left, width, top, at, alpha);
            return;
        }
        int route = item.progressSegmentColors != null && item.progressSegmentColors.length > 0
                ? item.progressSegmentColors[item.progressSegmentColors.length - 1] | Colors.BLACK : item.fillAccent();
        owner.fillPaint.setColor(Colors.withAlpha(Colors.TRACK, alpha));
        owner.tmpRect.set(left, top, at, top + barH);
        c.drawRoundRect(owner.tmpRect, barH / 2.0f, barH / 2.0f, owner.fillPaint);
        owner.fillPaint.setColor(Colors.withAlpha(Colors.readableOnBlack(route), alpha));
        owner.tmpRect.set(at, top, left + width, top + barH);
        c.drawRoundRect(owner.tmpRect, barH / 2.0f, barH / 2.0f, owner.fillPaint);
        float dot = owner.dp(5.0f);
        owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
        c.drawCircle(at, top + barH / 2.0f, dot, owner.fillPaint);
    }

    /**
     * A trip in legs (ProgressStyle segments): each leg in its own colour (a bus line), dashed when
     * walked; the travelled part dim. The app's tracker, or a white dot, marks where the user is.
     */
    private void drawLegs(Canvas c, float left, float width, float top, float at, float alpha) {
        float barH = owner.dp(LEG_BAR_DP);
        float gap = owner.dp(2.0f);
        int n = item.progressSegmentColors.length;
        float right = left + width;
        int save = c.save();
        owner.tmpRect.set(left, top, right, top + barH);
        owner.barClip.rewind();
        owner.barClip.addRoundRect(owner.tmpRect, barH / 2.0f, barH / 2.0f, Path.Direction.CW);
        c.clipPath(owner.barClip);
        for (int i = 0; i < n; i++) {
            float start = left + width * item.progressSegmentStarts[i] + (i == 0 ? 0.0f : gap / 2.0f);
            float end = i + 1 < n ? left + width * item.progressSegmentStarts[i + 1] - gap / 2.0f : right;
            if (end <= start) continue;
            boolean dashed = item.segmentDashed(i);
            float split = Math.min(end, Math.max(start, at));
            owner.fillPaint.setColor(Colors.withAlpha(Colors.TRACK, alpha));
            owner.drawBarSpan(c, start, split, start, top, barH, dashed, owner.fillPaint);
            owner.fillPaint.setColor(Colors.withAlpha(Colors.readableOnBlack(item.progressSegmentColors[i] | Colors.BLACK), alpha));
            owner.drawBarSpan(c, split, end, start, top, barH, dashed, owner.fillPaint);
        }
        c.restoreToCount(save);
        if (item.progressThumb != null) {
            float thumb = owner.dp(LEG_THUMB_DP);
            float tx = Math.max(left, Math.min(right - thumb, at - thumb / 2.0f));
            owner.drawDrawable(c, item.progressThumb, true, 0, tx, top + barH / 2.0f - thumb / 2.0f, thumb, alpha, thumb / 2.0f);
        } else {
            owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
            c.drawCircle(at, top + barH / 2.0f, owner.dp(5.0f), owner.fillPaint);
        }
    }

    private static final float LEG_BAR_DP = 5.0f;
    private static final float LEG_THUMB_DP = 18.0f;
}
