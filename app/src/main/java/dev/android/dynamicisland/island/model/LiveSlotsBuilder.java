package dev.android.dynamicisland.island.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Places an item's content into {@link LiveSlots}. Every live-activity source (Android 16 promoted
 * notifications, Samsung ongoing activities) already fills {@link IslandItem}, so building from the item
 * keeps one set of placement rules for all of them.
 *
 * <p>The rules follow ActivityKit's conventions: the compact pill pairs an identity glyph (leading) with the
 * one value that changes (trailing); the expanded view keeps that value at the top right, the app's picture
 * at the top left, the words in the centre and the progress and buttons at the bottom, so the same progress
 * is never drawn twice. Pure function, any thread.
 */
public final class LiveSlotsBuilder {
    private LiveSlotsBuilder() {
    }

    public static LiveSlots build(IslandItem it) {
        int accent = it.fillAccent() == -1 ? 0 : it.fillAccent();
        LiveSlots.Slot leading = compactLeading(it);
        LiveSlots.Slot trailing = compactTrailing(it, accent);
        LiveSlots.Bar bar = bar(it, accent);

        List<LiveSlots.Slot> bottom = new ArrayList<>(2);
        if (bar != null) {
            bottom.add(bar);
        }
        if (!it.actions.isEmpty()) {
            bottom.add(new LiveSlots.Actions(new ArrayList<>(it.actions)));
        }
        LiveSlots.Reach reach = !it.actions.isEmpty() ? LiveSlots.Reach.ACTION
                : bar != null ? LiveSlots.Reach.INSET : LiveSlots.Reach.NONE;

        // the ring and the bar would show the same number: the bar wins in the expanded view
        LiveSlots.Slot expandedTrailing = trailing instanceof LiveSlots.Ring && bar != null ? null : trailing;
        return new LiveSlots(leading, trailing, minimal(it, leading, accent), expandedLeading(it, leading),
                expandedTrailing, center(it), bottom, reach);
    }

    /** Home team's badge for a match, else the app's glyph. */
    private static LiveSlots.Slot compactLeading(IslandItem it) {
        if (it.hasTeams()) {
            return new LiveSlots.Image(it.leftImage, false);
        }
        return it.icon == null ? null : new LiveSlots.Icon(it.icon, it.iconIsImage, it.symbolTint());
    }

    /** Away team's badge for a match, else what the source chose to show after the title. */
    private static LiveSlots.Slot compactTrailing(IslandItem it, int accent) {
        if (it.hasTeams()) {
            return new LiveSlots.Image(it.rightImage, false);
        }
        switch (it.trailing) {
            case TEXT:
                return IslandItem.hasContent(it.trailingText) ? new LiveSlots.Text(it.trailingText, 0) : ring(it, accent);
            case CHRONOMETER:
                return it.hasChronometer()
                        ? new LiveSlots.Chrono(it.chronoBase, it.chronoCountDown, it.chronoMillis, 0)
                        : ring(it, accent);
            case ICON:
                return it.trailingIcon == null ? ring(it, accent) : new LiveSlots.Icon(it.trailingIcon, false, it.symbolTint());
            case WAVEFORM:
                // media draws its own waveform; a live activity never asks for it
                return null;
            case PROGRESS_RING:
            case NONE:
            default:
                return ring(it, accent);
        }
    }

    /** Two activities at once: the progress if there is one (it says more than the icon), else the icon. */
    private static LiveSlots.Slot minimal(IslandItem it, LiveSlots.Slot leading, int accent) {
        LiveSlots.Slot ring = ring(it, accent);
        return ring != null ? ring : leading;
    }

    /** The card picture when the source has one, else the compact glyph. */
    private static LiveSlots.Slot expandedLeading(IslandItem it, LiveSlots.Slot leading) {
        if (it.cardImage == null) {
            return leading;
        }
        return it.cardImageIsImage
                ? new LiveSlots.Image(it.cardImage, it.cardImageRound)
                : new LiveSlots.Icon(it.cardImage, false, it.symbolTint());
    }

    private static LiveSlots.Slot center(IslandItem it) {
        boolean title = it.title != null && it.title.length() > 0;
        boolean subtitle = it.subtitle != null && it.subtitle.length() > 0;
        if (!title && !subtitle) {
            return null;
        }
        return title ? new LiveSlots.Lines(it.title, subtitle ? it.subtitle : null) : new LiveSlots.Lines(it.subtitle, null);
    }

    private static LiveSlots.Ring ring(IslandItem it, int accent) {
        if (it.progressIndeterminate) {
            return new LiveSlots.Ring(-1.0f, accent);
        }
        return it.progress >= 0.0f ? new LiveSlots.Ring(dev.android.dynamicisland.island.graphics.Easing.clamp01(it.progress), accent) : null;
    }

    private static LiveSlots.Bar bar(IslandItem it, int accent) {
        boolean segments = it.hasSegments();
        if (!segments && it.progress < 0.0f && !it.progressIndeterminate) {
            return null;
        }
        float value = it.progressIndeterminate ? -1.0f : dev.android.dynamicisland.island.graphics.Easing.clamp01(Math.max(0.0f, it.progress));
        return new LiveSlots.Bar(value, accent, segments ? it.progressSegmentColors : null,
                segments ? it.progressSegmentStarts : null, it.progressThumb);
    }
}
