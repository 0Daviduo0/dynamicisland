package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.StaticLayout;
import android.text.TextPaint;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;

/**
 * Generic live-notification card, laid out like the stock System UI ongoing activity card:
 * header (app + time), primary line (or chronometer), secondary line with optional icon
 * (or chronometer / description), progress (plain, segmented with marker, or indeterminate)
 * and either round icon buttons or text buttons.
 *
 * <pre>
 * +-----------------------------------------+
 * | App name . 12:30                        |   header (optional, small, secondary colour)
 * | Title (up to 2 lines)             [icon]|   app icon 22dp at the top right
 * | Secondary text / description            |   a small icon may indent this line
 * | Detail line                             |
 * | ======------------------                |   progress bar
 * |  (o)    (o)    (o)    (o)               |   round icon buttons, or equal width text buttons
 * +-----------------------------------------+
 * </pre>
 *
 * Sports items ({@code teams}) replace the icon and indent with a row of two team logos, the score between
 * them, and the text lines below. Rows (content, progress, buttons) fade in one after the other.
 * Fields ending in {@code Y} / {@code Top} are offsets from the top of the body, fixed at construction.
 */
public final class LiveBody extends CardBody {
    private static final int MAX_ICON_BUTTONS = 4;
    /** Corner radius of the app icon, relative to its size. */
    private static final float ICON_CORNER_RATIO = 0.26f;
    /** Corner radius of team logos, relative to their size. */
    private static final float LOGO_CORNER_RATIO = 0.14f;
    /** Width of the indeterminate sweep relative to the bar, and its period. */
    private static final float SWEEP_RATIO = 0.38f;
    private static final long SWEEP_PERIOD_MS = 1500;
    /** Slide-up distance of a row while it fades in. */
    private static final float ROW_SLIDE_DP = 8.0f;

    /** Top of the button row, or -1 when there are no actions. */
    float actionsTop;
    final StaticLayout description;
    final StaticLayout detail;
    final String header;
    final float headerY;
    final float iconSize;
    final float logoSize;
    final float pad;
    /** Top of the progress box, or -1 when the item has no progress. */
    float progressTop;
    float progressBoxH;
    /** Score text of a sports item, drawn between the team logos. */
    String score;
    /** Indent of the secondary line when it has a leading icon. */
    final float secIndent;
    final float subY;
    final float subH;
    final StaticLayout subtitle;
    /** The secondary line is a live chronometer. */
    final boolean subChrono;
    final boolean teams;
    final StaticLayout title;
    /** The primary line is a live chronometer. */
    final boolean titleChrono;
    final float titleY;
    final float textW;

    public LiveBody(IslandView owner, IslandItem item, float w) {
        super(owner, item, w);
        pad = owner.dp(18.0f);
        boolean event = item.kind == IslandItem.Kind.EVENT;
        boolean hasIcon = item.icon != null;
        // no app icon in the top right corner of a card: only an event's own symbol takes that place
        iconSize = !event || !hasIcon ? 0 : owner.dp(!ModuleSettings.compactEventIcons() ? 40 : 22);
        logoSize = owner.dp(52.0f);
        progressTop = -1.0f;
        actionsTop = -1.0f;
        teams = item.hasTeams();
        boolean blankScore = false;
        if (teams && item.trailing == IslandItem.Trailing.TEXT && item.trailingText != null) {
            // a lone dash (a match that has not started) is not a score
            blankScore = !IslandItem.hasContent(item.trailingText);
            if (!blankScore) {
                score = item.trailingText.toString();
            }
        }
        textW = Math.max(1, Math.round((w - (pad * 2.0f)) - (teams || iconSize == 0 ? 0.0f : iconSize + owner.dp(12.0f))));
        int textWidth = Math.round(textW);
        float y = pad;
        if (teams) {
            y += logoSize + owner.dp(10.0f);
        }

        // header: "App · 12:30"
        CharSequence app = item.appName;
        String headerText = null;
        if (ModuleSettings.cardHeader() && app != null && item.title != null && app.length() > 0) {
            headerText = app.toString();
            if (item.whenMillis > 0) {
                headerText += " \u00b7 " + android.text.format.DateFormat.getTimeFormat(owner.getContext()).format(new java.util.Date(item.whenMillis));
            }
        }
        this.header = headerText;
        headerY = y;
        if (header != null) {
            y += lineHeight(owner.smallPaint) + owner.dp(4.0f);
        }

        // primary line
        titleChrono = item.hasChronometer() && item.chronoPosition == 1;
        CharSequence primary = item.title != null ? item.title : item.appName;
        title = (titleChrono || primary == null) ? null : IslandView.layout(primary, owner.titlePaint, textWidth, 2);
        titleY = y;
        if (titleChrono) {
            y += lineHeight(owner.titlePaint);
        } else if (title != null) {
            y += title.getHeight();
        }

        // secondary line: description wins, then chronometer, then text; icon indents the text
        subChrono = item.description == null && item.hasChronometer() && item.chronoPosition == 2;
        secIndent = (item.secondaryIcon != null && item.description == null) ? owner.dp(22.0f) : 0.0f;
        CharSequence secondary = item.subtitle;
        if (item.trailing == IslandItem.Trailing.TEXT && item.trailingText != null && secondary == null && score == null && !blankScore) {
            secondary = item.trailingText;
        }
        this.description = item.description == null ? null : IslandView.layout(item.description, owner.bodyPaint, textWidth,
                Math.max(1, Math.min(8, item.descriptionMaxLines)));
        subtitle = (description != null || subChrono || secondary == null) ? null
                : IslandView.layout(secondary, owner.bodyPaint, Math.max(1, textWidth - Math.round(secIndent)), 2);
        subY = y + ((title != null || titleChrono) ? owner.dp(3.0f) : 0.0f);
        float secondaryHeight = 0.0f;
        if (description != null) {
            secondaryHeight = description.getHeight();
        } else if (subChrono) {
            secondaryHeight = lineHeight(owner.bodyPaint);
        } else if (subtitle != null) {
            secondaryHeight = subtitle.getHeight();
        }
        subH = secondaryHeight;
        if (secondaryHeight > 0.0f) {
            y = subY + secondaryHeight + owner.dp(2.0f);
        }
        detail = item.detail != null ? IslandView.layout(item.detail, owner.bodyPaint, textWidth, 1) : null;
        if (detail != null) {
            y += detail.getHeight() + owner.dp(2.0f);
        }
        y = Math.max(y, pad + (teams ? 0.0f : iconSize));

        if (item.progress >= 0.0f || item.progressIndeterminate) {
            progressBoxH = item.progressThumb != null && item.hasSegments() ? owner.dp(18.0f) : owner.dp(8.0f);
            progressTop = y + owner.dp(14.0f);
            y = progressTop + progressBoxH;
        }
        if (!item.actions.isEmpty()) {
            actionsTop = owner.dp(14.0f) + y;
            y = actionsTop + (useIconButtons() ? layoutIconButtons(actionsTop) : layoutTextButtons(actionsTop, pad));
        }
        h = pad + y;
    }

    private float lineHeight(TextPaint paint) {
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(paint);
        return fm.descent - fm.ascent;
    }

    /** actionType 0 = round icon buttons, as long as every action actually has an icon. */
    private boolean useIconButtons() {
        if (item.actionType != 0) {
            return false;
        }
        int count = Math.min(MAX_ICON_BUTTONS, item.actions.size());
        for (int i = 0; i < count; i++) {
            if (item.actions.get(i).icon == null) {
                return false;
            }
        }
        return count > 0;
    }

    /** Adds up to four round buttons, each centred in its own equal-width column; returns the row height. */
    private float layoutIconButtons(float top) {
        int count = Math.min(MAX_ICON_BUTTONS, item.actions.size());
        float size = owner.dp(44.0f);
        float column = (w - (pad * 2.0f)) / count;
        for (int i = 0; i < count; i++) {
            IslandItem.Action action = item.actions.get(i);
            Btn b = new Btn();
            float centerX = pad + (column * i) + (column / 2.0f);
            b.r.set(centerX - (size / 2.0f), top, centerX + (size / 2.0f), top + size);
            b.circle = true;
            b.icon = action.icon;
            b.action = action;
            int customColor = (item.actionBgColors != null && i < item.actionBgColors.length) ? item.actionBgColors[i] : 0;
            if (action.destructive) {
                b.bg = Colors.DESTRUCTIVE_RED;
            } else if (customColor != 0) {
                b.bg = customColor | Colors.BLACK;
            } else if (i == item.primaryActionIndex) {
                b.bg = item.primaryButtonFill(IslandView.PAUSED_SPEED);
            } else {
                b.bg = item.customAccent != 0 ? Colors.withAlpha(item.fillAccent(), android.graphics.Color.alpha(Colors.BUTTON_ACTION_FILL) / 255f) : Colors.BUTTON_ACTION_FILL;
            }
            buttons.add(b);
        }
        return size;
    }

    // ---- around the camera: the iOS expanded layout --------------------------------------------------

    /** Leading hero symbol size; 0 when the card opened around the camera without one (or not at all). */
    private float heroSize;
    /** Content top (px from the body top) and the text column, in hero mode. */
    private float heroTop, heroTextX, heroTextW, heroBlockH;
    private StaticLayout heroTitle, heroSecondary, heroDetail;
    private float heroTitleY, heroSecondaryY, heroDetailY, heroProgressTop = -1.0f;
    /** The live value beside the title (a time, a count), only when the text does not already say it. */
    private String heroValue;
    private float heroValueW;

    /**
     * Around the camera hole the card follows Apple's Live Activity layout: nothing sits beside the camera
     * (no app name, no repeated value in the corners), the content starts right under it with the activity's
     * symbol large on the leading side, the text next to it, and a live value at the end of the title row only
     * when the text does not say it already. Sports cards keep their team row and band.
     */
    @Override
    protected float layoutBesideHole() {
        if (teams) return -1.0f;
        float gap = owner.dp(12.0f);
        heroSize = item.icon != null ? owner.dp(40.0f) : 0.0f;
        heroTop = holeBottom + owner.dp(6.0f);
        heroTextX = pad + (heroSize > 0 ? heroSize + gap : 0.0f);
        heroValue = liveValue();
        heroValueW = heroValue == null ? 0.0f : IslandView.timeWidth(owner.titlePaint, heroValue) + owner.dp(10.0f);
        heroTextW = Math.max(owner.dp(40.0f), w - pad - heroTextX);
        int titleW = Math.round(Math.max(owner.dp(40.0f), heroTextW - heroValueW));
        int textW = Math.round(heroTextW);
        float y = 0.0f;
        CharSequence primary = item.title != null ? item.title : item.appName;
        heroTitle = titleChrono || primary == null ? null : IslandView.layout(primary, owner.titlePaint, titleW, 2);
        heroTitleY = y;
        if (titleChrono) y += lineHeight(owner.titlePaint);
        else if (heroTitle != null) y += heroTitle.getHeight();
        heroSecondaryY = y + (y > 0 ? owner.dp(2.0f) : 0.0f);
        heroSecondary = null;
        if (description != null) {
            heroSecondary = IslandView.layout(item.description, owner.bodyPaint, textW, Math.max(1, Math.min(8, item.descriptionMaxLines)));
        } else if (!subChrono && subtitle != null) {
            heroSecondary = IslandView.layout(subtitle.getText(), owner.bodyPaint, Math.max(1, textW - Math.round(secIndent)), 2);
        }
        if (heroSecondary != null) y = heroSecondaryY + heroSecondary.getHeight();
        else if (subChrono) y = heroSecondaryY + lineHeight(owner.bodyPaint);
        heroDetail = item.detail != null ? IslandView.layout(item.detail, owner.bodyPaint, textW, 1) : null;
        heroDetailY = y + owner.dp(2.0f);
        if (heroDetail != null) y = heroDetailY + heroDetail.getHeight();
        heroBlockH = Math.max(heroSize, y);
        float bottom = heroTop + heroBlockH;
        heroProgressTop = -1.0f;
        if (progressTop >= 0.0f) {
            heroProgressTop = bottom + owner.dp(14.0f);
            bottom = heroProgressTop + progressBoxH;
        }
        float height = bottom + pad;
        if (actionsTop >= 0.0f) {
            float row = h - pad - actionsTop;
            float top = bottom + owner.dp(14.0f);
            shiftButtons(top - actionsTop);
            height = top + row + pad;
        }
        return height;
    }

    @Override
    protected void onClearHole() {
        heroSize = 0.0f;
        heroTitle = heroSecondary = heroDetail = null;
        heroValue = null;
    }

    /** What the pill shows at its trailing edge, when the card's own text does not already carry it. */
    private String liveValue() {
        if (titleChrono || subChrono) return null;
        String value = owner.trailingString(item);
        if (value == null || value.trim().isEmpty()) return null;
        if (contains(item.title, value) || contains(item.subtitle, value) || contains(item.description, value)
                || contains(item.detail, value)) {
            return null;
        }
        // a value as long as a sentence is not a value: the pill's compact rule
        return value.length() > 12 ? null : value;
    }

    private static boolean contains(CharSequence text, String value) {
        return text != null && text.toString().contains(value.trim());
    }

    private void drawHero(Canvas c, float x, float y, float a0, float dy0) {
        float top = y + heroTop + dy0;
        if (heroSize > 0) {
            owner.drawLeading(c, item, x + pad, top + heroBlockH / 2.0f, heroSize, a0);
        }
        float left = x + heroTextX;
        if (titleChrono) {
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            String text = fitted(22, owner.chronoText(item), owner.titlePaint, heroTextW);
            owner.drawTime(c, timeKey(2, "#live1"), text, owner.titlePaint, left, top + heroTitleY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.titlePaint).ascent, item.chronoCountDown);
        } else if (heroTitle != null) {
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            IslandView.drawLayout(c, heroTitle, left, top + heroTitleY);
        }
        if (heroValue != null) {
            String value = item.trailing == IslandItem.Trailing.CHRONOMETER ? owner.chronoText(item) : heroValue;
            owner.titlePaint.setColor(Colors.withAlpha(item.kind == IslandItem.Kind.CALL ? item.accent : Colors.WHITE, a0));
            float base = top + heroTitleY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.titlePaint).ascent;
            owner.drawTime(c, timeKey(0, "#lane"), value, owner.titlePaint,
                    x + w - pad - IslandView.timeWidth(owner.titlePaint, value), base, item.chronoCountDown);
        }
        if (secIndent > 0.0f && item.secondaryIcon != null && description == null) {
            float smallIcon = owner.dp(16.0f);
            float lineH = subChrono ? lineHeight(owner.bodyPaint) : owner.bodyPaint.getTextSize() * 1.3f;
            owner.drawDrawable(c, item.secondaryIcon, false, Colors.TEXT_BODY, left, top + heroSecondaryY + Math.max(0.0f, (lineH - smallIcon) / 2.0f), smallIcon, a0, 0.0f);
        }
        owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a0));
        if (heroSecondary != null) {
            IslandView.drawLayout(c, heroSecondary, left + (description == null ? secIndent : 0.0f), top + heroSecondaryY);
        } else if (subChrono) {
            String text = fitted(23, owner.chronoText(item), owner.bodyPaint, heroTextW - secIndent);
            owner.drawTime(c, timeKey(3, "#live2"), text, owner.bodyPaint, left + secIndent, top + heroSecondaryY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bodyPaint).ascent, item.chronoCountDown);
        }
        if (heroDetail != null) {
            IslandView.drawLayout(c, heroDetail, left, top + heroDetailY);
        }
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float a0 = alpha * rowAlpha(elapsed, 0);
        float dy0 = (1.0f - rowAlpha(elapsed, 0)) * owner.dp(ROW_SLIDE_DP);
        float by = y; // the buttons were moved with the content already
        if (holeRow) {
            drawHero(c, x, y, a0, dy0);
            if (heroProgressTop >= 0.0f) {
                float progressAlpha = rowAlpha(elapsed, 1);
                drawProgress(c, x, y + heroProgressTop + ((1.0f - progressAlpha) * owner.dp(ROW_SLIDE_DP)), alpha * progressAlpha);
            }
            if (actionsTop >= 0.0f) {
                float buttonsAlpha = rowAlpha(elapsed, 2);
                drawButtons(c, x, by + ((1.0f - buttonsAlpha) * owner.dp(ROW_SLIDE_DP)), alpha * buttonsAlpha);
            }
            return;
        }
        float top = y + dy0;
        if (teams) {
            float ty = top + pad;
            float midY = ty + (logoSize / 2.0f);
            owner.drawDrawable(c, item.leftImage, true, 0, x + pad, ty, logoSize, a0, logoSize * LOGO_CORNER_RATIO);
            owner.drawDrawable(c, item.rightImage, true, 0, ((w + x) - pad) - logoSize, ty, logoSize, a0, logoSize * LOGO_CORNER_RATIO);
            if (score != null) {
                owner.bigTimePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
                float room = ((w - (pad * 2.0f)) - (logoSize * 2.0f)) - owner.dp(16.0f);
                String text = fitted(20, score, owner.bigTimePaint, room);
                Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bigTimePaint);
                float tw = IslandView.timeWidth(owner.bigTimePaint, text);
                owner.drawTime(c, timeKey(1, "#score"), text, owner.bigTimePaint, ((w / 2.0f) + x) - (tw / 2.0f), midY - ((fm.ascent + fm.descent) / 2.0f), false);
            }
        }
        float left = pad + x;
        if (header != null && !holeRow) {
            owner.smallPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a0));
            String text = fitted(21, header, owner.smallPaint, textW);
            c.drawText(text, left, top + headerY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.smallPaint).ascent, owner.smallPaint);
        }
        if (titleChrono) {
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            String text = fitted(22, owner.chronoText(item), owner.titlePaint, textW);
            owner.drawTime(c, timeKey(2, "#live1"), text, owner.titlePaint, left, top + titleY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.titlePaint).ascent, item.chronoCountDown);
        } else if (title != null) {
            owner.titlePaint.setColor(Colors.withAlpha(Colors.WHITE, a0));
            IslandView.drawLayout(c, title, left, top + titleY);
        }
        if (secIndent > 0.0f && item.secondaryIcon != null) {
            float smallIcon = owner.dp(16.0f);
            float lineH = subChrono ? lineHeight(owner.bodyPaint) : owner.bodyPaint.getTextSize() * 1.3f;
            owner.drawDrawable(c, item.secondaryIcon, false, Colors.TEXT_BODY, left, top + subY + Math.max(0.0f, (lineH - smallIcon) / 2.0f), smallIcon, a0, 0.0f);
        }
        owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_BODY, a0));
        if (description != null) {
            IslandView.drawLayout(c, description, left, top + subY);
        } else if (subChrono) {
            String text = fitted(23, owner.chronoText(item), owner.bodyPaint, textW - secIndent);
            owner.drawTime(c, timeKey(3, "#live2"), text, owner.bodyPaint, left + secIndent, top + subY - dev.android.dynamicisland.island.graphics.Fonts.metrics(owner.bodyPaint).ascent, item.chronoCountDown);
        } else if (subtitle != null) {
            IslandView.drawLayout(c, subtitle, left + secIndent, top + subY);
        }
        if (detail != null) {
            owner.bodyPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, a0));
            IslandView.drawLayout(c, detail, left, top + subY + subH + owner.dp(2.0f));
        }
        if (!teams && iconSize > 0) {
            float iconX = ((w + x) - pad) - iconSize;
            float iconY = y + pad + dy0;
            if (item.icon != null) {
                owner.drawDrawable(c, item.icon, item.iconIsImage, item.symbolTint(), iconX, iconY, iconSize, a0, iconSize * ICON_CORNER_RATIO);
            }
            if (item.profileBadge != null) {
                // work-profile badge overlapping the icon's bottom right corner
                float badgeSize = owner.dp(12.0f);
                owner.drawDrawable(c, item.profileBadge, true, 0, iconX + iconSize - (badgeSize * 0.7f), iconY + iconSize - (badgeSize * 0.7f), badgeSize, a0, badgeSize / 2.0f);
            }
        }
        if (progressTop >= 0.0f) {
            float progressAlpha = rowAlpha(elapsed, 1);
            drawProgress(c, x, y + progressTop + ((1.0f - progressAlpha) * owner.dp(ROW_SLIDE_DP)), alpha * progressAlpha);
        }
        if (actionsTop >= 0.0f) {
            float buttonsAlpha = rowAlpha(elapsed, 2);
            drawButtons(c, x, by + ((1.0f - buttonsAlpha) * owner.dp(ROW_SLIDE_DP)), alpha * buttonsAlpha);
        }
    }

    private void drawProgress(Canvas c, float x, float top, float alpha) {
        float left = pad + x;
        float width = w - (pad * 2.0f);
        boolean segmented = item.hasSegments();
        float barHeight = owner.dp(segmented ? 8.0f : 7.0f);
        float barTop = top + ((progressBoxH - barHeight) / 2.0f);
        owner.tmpRect.set(left, barTop, left + width, barTop + barHeight);
        int save = c.save();
        owner.barClip.rewind();
        owner.barClip.addRoundRect(owner.tmpRect, barHeight / 2.0f, barHeight / 2.0f, Path.Direction.CW);
        c.clipPath(owner.barClip);
        if (segmented) {
            int segments = item.progressSegmentColors.length;
            float gap = owner.dp(2.0f);
            for (int i = 0; i < segments; i++) {
                float segStart = item.progressSegmentStarts[i];
                float segEnd = i + 1 < segments ? item.progressSegmentStarts[i + 1] : 1.0f;
                owner.fillPaint.setColor(Colors.withAlpha(item.progressSegmentColors[i] | Colors.BLACK, alpha));
                float l = left + (width * segStart) + (i == 0 ? 0.0f : gap / 2.0f);
                owner.drawBarSpan(c, l, (left + (width * segEnd)) - (i + 1 == segments ? 0.0f : gap / 2.0f), l, barTop, barHeight, item.segmentDashed(i), owner.fillPaint);
            }
        } else {
            owner.fillPaint.setColor(Colors.withAlpha(Colors.TRACK, alpha));
            c.drawRect(left, barTop, left + width, barTop + barHeight, owner.fillPaint);
        }
        float fillEnd = left;
        if (item.progressIndeterminate && item.progress < 0.0f) {
            float sweep = width * SWEEP_RATIO;
            float phase = (IslandView.now() % SWEEP_PERIOD_MS) / (float) SWEEP_PERIOD_MS;
            float eased = IslandView.easeInOut(phase);
            float start = left - sweep + ((width + sweep) * eased);
            owner.fillPaint.setColor(Colors.withAlpha(item.fillAccent(), alpha));
            c.drawRect(Math.max(left, start), barTop, Math.min(left + width, start + sweep), barTop + barHeight, owner.fillPaint);
        } else {
            fillEnd = left + Math.max(barHeight, width * IslandView.clamp01(item.progress));
            int fillColor = segmented ? (item.progressTrackColor != 0 ? (item.progressTrackColor | Colors.BLACK) : Colors.SEGMENT_UNREACHED) : item.fillAccent();
            owner.fillPaint.setColor(Colors.withAlpha(fillColor, alpha));
            c.drawRect(left, barTop, fillEnd, barTop + barHeight, owner.fillPaint);
        }
        c.restoreToCount(save);
        if (segmented && item.progressThumb != null && !(item.progressIndeterminate && item.progress < 0.0f)) {
            float thumbSize = owner.dp(18.0f);
            float thumbX = Math.max(left, Math.min(left + width - thumbSize, fillEnd - (thumbSize / 2.0f)));
            owner.drawDrawable(c, item.progressThumb, false, Colors.WHITE, thumbX, top + ((progressBoxH - thumbSize) / 2.0f), thumbSize, alpha, 0.0f);
        }
    }
}
