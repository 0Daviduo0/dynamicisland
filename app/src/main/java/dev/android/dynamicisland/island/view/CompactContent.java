package dev.android.dynamicisland.island.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import java.util.regex.Matcher;

/**
 * Content of the compact pill: a leading icon at the left and, when the item has one, trailing text,
 * chronometer or indicator at the right. With a camera hole the two sides are placed left and right of
 * the hole. For sports items ({@code teams}) the leading icon is the home team's logo and the trailing
 * side is the away team's logo, with the score in between (or, around a hole, one score on each side).
 */
public final class CompactContent extends Content {
    /** Reused per draw; a new FontMetrics each frame is garbage at 120 Hz. */
    private final android.graphics.Paint.FontMetrics fm = new android.graphics.Paint.FontMetrics();
    /** Smallest width the trailing part may be squeezed to. */
    private static final float MIN_TRAILING_DP = 20.0f;
    /** Widest a leading badge (a bus line number) may get. */
    private static final float BADGE_MAX_DP = 44.0f;
    /** Gap between the badge and the icon, in inner gaps: a little tighter than the label after it. */
    private static final float BADGE_GAP = 0.6f;
    /** Home score, shown beside the home logo (only when the score text splits around a hole). */
    String scoreA;
    /** Away score, shown beside the away logo. */
    String scoreB;
    float scoreW;
    final boolean teams;
    String trailText;
    float trailW;
    String leadingLabel;
    float leadingLabelW;
    /** Badge drawn before the icon (a bus line number) and its width; null / 0 when none. */
    String badge;
    float badgeW;

    /** Rolling-digit field keys, built once instead of concatenated on every frame. */
    private final String keyLead, keyScoreA, keyScoreB, keyScore;

    CompactContent(IslandView owner, IslandItem item) {
        super(owner, item);
        keyLead = item.key + "#lead";
        keyScoreA = item.key + "#scoreA";
        keyScoreB = item.key + "#scoreB";
        keyScore = item.key + "#score";
        teams = item.hasTeams();
        remeasure();
    }

    /** Recomputes the trailing text and width after the item changed; returns whether the width changed. */
    boolean remeasure() {
        float width;
        String text = owner.trailingString(item);
        if (item.eventLayout == IslandItem.EventLayout.BATTERY && item.batteryPercent >= 0) {
            text = java.text.NumberFormat.getPercentInstance(owner.getResources().getConfiguration().getLocales().get(0))
                    .format(item.batteryPercent / 100d);
        }
        if (teams && !IslandItem.hasContent(text)) {
            text = null; // a match that has not started: the logos alone, no lone dash between them
        }
        leadingLabel = null;
        leadingLabelW = 0;
        float oldBadgeW = badgeW;
        badge = null;
        badgeW = 0;
        if (item.leadingBadge != null && !teams) {
            badge = TextUtils.ellipsize(item.leadingBadge, owner.trailPaint, owner.dp(BADGE_MAX_DP), TextUtils.TruncateAt.END).toString();
            badgeW = owner.trailPaint.measureText(badge);
        }
        if (owner.hasCallWaveform(item) && !teams) {
            // call pill: voice bars at the trailing side, no clock (the setting "call time" shows it instead)
            float waveW = owner.callWaveWidth();
            boolean changed = Math.abs(waveW - trailW) > 0.5f || Math.abs(oldBadgeW - badgeW) > 0.5f;
            trailText = null;
            trailW = waveW;
            return changed;
        }
        if (item.compactLabel != null && item.trailing == IslandItem.Trailing.TEXT
                && !item.compactLabel.toString().equals(text)) {
            float room = owner.holeD > 0 ? (owner.maxPillW - owner.holeD) / 2 - owner.holeGap - owner.padding
                    - Math.min(owner.holeTextPadding, owner.dp(16)) - owner.leadSize - owner.innerGap - badgeSpace()
                    : owner.maxPillW - owner.padding * 2 - owner.leadSize - owner.innerGap * 2 - badgeSpace()
                    - Math.max(owner.dp(MIN_TRAILING_DP), text == null ? 0 : owner.trailPaint.measureText(text));
            if (room >= owner.dp(16)) {
                leadingLabel = TextUtils.ellipsize(item.compactLabel, owner.trailPaint,
                        Math.min(owner.dp(96), room), TextUtils.TruncateAt.END).toString();
                leadingLabelW = DigitClock.width(owner.trailPaint, leadingLabel);
            }
        }
        float maxTrail = maxTrailingWidth();
        if (teams && owner.holeD > 0.0f && splitScore(text)) {
            float maxHalf = Math.max(0.0f, (maxTrail - owner.leadSize) - owner.innerGap);
            scoreA = TextUtils.ellipsize(scoreA, owner.trailPaint, maxHalf, TextUtils.TruncateAt.END).toString();
            scoreB = TextUtils.ellipsize(scoreB, owner.trailPaint, maxHalf, TextUtils.TruncateAt.END).toString();
            scoreW = Math.max(DigitClock.width(owner.trailPaint, scoreA), DigitClock.width(owner.trailPaint, scoreB));
            width = owner.leadSize + owner.innerGap + scoreW;
        } else if (teams) {
            scoreA = null;
            scoreB = null;
            float maxScore = Math.max(0.0f, (maxTrail - owner.leadSize) - owner.innerGap);
            if (text != null && item.trailing == IslandItem.Trailing.TEXT) {
                text = TextUtils.ellipsize(text, owner.trailPaint, maxScore, TextUtils.TruncateAt.END).toString();
            } else {
                text = null;
            }
            scoreW = text == null ? 0.0f : DigitClock.width(owner.trailPaint, text);
            width = owner.leadSize + scoreW + (text != null ? owner.innerGap : 0.0f);
        } else {
            if (text != null && item.trailing == IslandItem.Trailing.TEXT) {
                text = TextUtils.ellipsize(text, owner.trailPaint, maxTrail, TextUtils.TruncateAt.END).toString();
            }
            width = Math.min(owner.trailingWidth(item, text), maxTrail);
        }
        boolean changed = Math.abs(width - trailW) > 0.5f || Math.abs(oldBadgeW - badgeW) > 0.5f;
        trailText = text;
        trailW = width;
        return changed;
    }

    /** Splits a "2 - 1" style score into {@link #scoreA} and {@link #scoreB}; false if it does not match. */
    private boolean splitScore(String text) {
        scoreA = null;
        scoreB = null;
        if (text == null || item.trailing != IslandItem.Trailing.TEXT) {
            return false;
        }
        Matcher m = IslandView.SCORE.matcher(text.trim());
        if (!m.matches()) {
            return false;
        }
        scoreA = m.group(1);
        scoreB = m.group(2);
        return true;
    }

    private float maxTrailingWidth() {
        float minWidth = owner.dp(MIN_TRAILING_DP);
        if (owner.holeD > 0.0f) {
            return Math.max(minWidth, (((owner.maxPillW - owner.holeD) / 2.0f) - owner.holeGap) - owner.padding - holeClearance());
        }
        return Math.max(minWidth, ((owner.maxPillW - (owner.padding * 2.0f)) - leadingWidth()) - owner.innerGap);
    }

    private float holeClearance() {
        float spare = (owner.maxPillW - owner.holeD) / 2f - owner.holeGap - owner.padding
                - Math.max(leadingWidth(), owner.dp(MIN_TRAILING_DP));
        return Math.max(0, Math.min(owner.holeTextPadding, spare));
    }

    @Override
    float desiredWidth() {
        float width;
        if (owner.holeD > 0.0f) {
            // both sides get the width of the wider one, so the pill stays symmetric around the hole
            float side = Math.max(leadingWidth(), trailW);
            float extra = trailW > 0 ? holeClearance() : 0;
            width = owner.holeD + ((owner.holeGap + extra + side + owner.padding) * 2.0f);
        } else {
            width = (trailW > 0.0f ? owner.innerGap + trailW : 0.0f) + (owner.padding * 2.0f) + leadingWidth();
        }
        return Math.max(owner.minPillW, Math.min(width, owner.maxPillW));
    }

    private float leadingWidth() {
        // a voice recording: red symbol here, its live waveform on the trailing side (like a call)
        return badgeSpace() + owner.leadSize + (leadingLabel == null ? 0 : owner.innerGap + leadingLabelW);
    }

    /** Room the badge takes before the icon, its gap included. */
    private float badgeSpace() {
        return badge == null ? 0 : badgeW + owner.innerGap * BADGE_GAP;
    }


    @Override
    void draw(Canvas c, RectF shape, float alpha, long elapsedIn) {
        boolean callWave = owner.hasCallWaveform(item) && !teams;
        if (callWave) {
            leadingLabel = null;
        } else if (item.trailing == IslandItem.Trailing.CHRONOMETER) {
            trailText = owner.trailingString(item);
        }
        float midY = shape.centerY();
        float lead = Math.min(owner.leadSize, shape.height() * 0.8f);
        if (teams) {
            drawTeams(c, shape, midY, lead, alpha);
            return;
        }
        {
            float iconLeft = shape.left + owner.padding + badgeSpace();
            if (badge != null) {
                owner.trailPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
                owner.trailPaint.getFontMetrics(fm);
                c.drawText(badge, shape.left + owner.padding, midY - (fm.ascent + fm.descent) / 2, owner.trailPaint);
            }
            owner.drawLeadingMono(c, item, iconLeft, midY, lead, alpha);
            if (leadingLabel != null) {
                float left = iconLeft + owner.leadSize + owner.innerGap;
                float limit = owner.holeD > 0 ? shape.centerX() - owner.holeD / 2 - owner.holeGap - holeClearance()
                        : shape.right - owner.padding - trailW - owner.innerGap;
                int save = c.save();
                c.clipRect(left, shape.top, Math.max(left, limit), shape.bottom);
                owner.trailPaint.setColor(Colors.withAlpha(callWave ? item.accent : Colors.WHITE, alpha));
                owner.trailPaint.getFontMetrics(fm);
                owner.drawTime(c, keyLead, leadingLabel, owner.trailPaint, left, midY - (fm.ascent + fm.descent) / 2, false);
                c.restoreToCount(save);
            }
        }
        if (callWave) {
            owner.drawCallWaveform(c, item, shape.right - owner.padding - trailW, midY, alpha);
        } else if (trailW > 0.0f) {
            owner.drawTrailing(c, item, trailText, shape.right - owner.padding, midY, trailW, alpha);
        }
    }


    private void drawTeams(Canvas c, RectF shape, float midY, float lead, float alpha) {
        float corner = lead * 0.14f;
        float logoTop = midY - (lead / 2.0f);
        float leftLogoX = shape.left + owner.padding;
        float rightLogoX = (shape.right - owner.padding) - lead;
        owner.drawDrawable(c, item.leftImage, true, 0, leftLogoX, logoTop, lead, alpha, corner);
        owner.drawDrawable(c, item.rightImage, true, 0, rightLogoX, logoTop, lead, alpha, corner);
        if (scoreA != null) {
            // split score: each half sits next to its team's logo, on either side of the hole
            owner.trailPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
            owner.trailPaint.getFontMetrics(fm);
            float baseline = midY - ((fm.ascent + fm.descent) / 2.0f);
            owner.drawTime(c, keyScoreA, scoreA, owner.trailPaint, leftLogoX + lead + owner.innerGap, baseline, false);
            owner.drawTime(c, keyScoreB, scoreB, owner.trailPaint, (rightLogoX - owner.innerGap) - DigitClock.width(owner.trailPaint, scoreB), baseline, false);
            return;
        }
        if (trailText != null) {
            owner.trailPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
            owner.trailPaint.getFontMetrics(fm);
            owner.drawTime(c, keyScore, trailText, owner.trailPaint, (rightLogoX - owner.innerGap) - scoreW, midY - ((fm.ascent + fm.descent) / 2.0f), false);
        }
    }
}
