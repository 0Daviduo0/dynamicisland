package dev.android.dynamicisland.island.model;

import android.graphics.drawable.Drawable;
import java.util.Collections;
import java.util.List;

/**
 * What a live activity shows, as content placed in fixed slots rather than as the item's raw fields.
 *
 * <p>The slots follow ActivityKit's presentations (and islandcn's contract): {@code compact} has content on
 * either side of the camera hole, {@code minimal} is the one glyph left when two activities share the
 * island, and {@code expanded} has a top row ({@code leading}, {@code center}, {@code trailing}) with a
 * full-width {@code bottom} under it. A renderer only has to draw each {@link Slot} kind once; which field
 * of which source ends up where is decided in {@link LiveSlotsBuilder}. Empty slots are null.
 */
public final class LiveSlots {
    /**
     * How close the bottom row may come to the expanded island's rounded bottom corners.
     * NONE: a centred row, nothing reaches the corners. INSET: a bar with marks at both ends, pulled in until
     * its ends clear the corner arcs. ACTION: a row of buttons, inset so its ends are concentric with the corners.
     */
    public enum Reach { NONE, INSET, ACTION }

    public final Slot compactLeading;
    public final Slot compactTrailing;
    public final Slot minimal;
    public final Slot expandedLeading;
    public final Slot expandedTrailing;
    public final Slot expandedCenter;
    /** Rows of the bottom region, top to bottom; empty when there is none. */
    public final List<Slot> expandedBottom;
    public final Reach bottomReach;

    LiveSlots(Slot compactLeading, Slot compactTrailing, Slot minimal, Slot expandedLeading, Slot expandedTrailing,
            Slot expandedCenter, List<Slot> expandedBottom, Reach bottomReach) {
        this.compactLeading = compactLeading;
        this.compactTrailing = compactTrailing;
        this.minimal = minimal;
        this.expandedLeading = expandedLeading;
        this.expandedTrailing = expandedTrailing;
        this.expandedCenter = expandedCenter;
        this.expandedBottom = Collections.unmodifiableList(expandedBottom);
        this.bottomReach = bottomReach;
    }

    /** One piece of content. Each subclass is drawn the same way wherever it sits; the slot only sets its box. */
    public abstract static class Slot {
        Slot() {
        }
    }

    /** An app icon or glyph. {@code image} keeps its own colours; otherwise it is tinted with {@code tint}. */
    public static final class Icon extends Slot {
        public final Drawable drawable;
        public final boolean image;
        public final int tint;

        Icon(Drawable drawable, boolean image, int tint) {
            this.drawable = drawable;
            this.image = image;
            this.tint = tint;
        }
    }

    /** Artwork or a picture (album cover, map, contact photo). {@code round} clips it to a circle. */
    public static final class Image extends Slot {
        public final Drawable drawable;
        public final boolean round;

        Image(Drawable drawable, boolean round) {
            this.drawable = drawable;
            this.round = round;
        }
    }

    /** A short value (ETA, score, status word). {@code color} is 0 for the default white. */
    public static final class Text extends Slot {
        public final CharSequence text;
        public final int color;

        Text(CharSequence text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    /** A title with an optional second line, as the expanded centre shows it. */
    public static final class Lines extends Slot {
        public final CharSequence title;
        public final CharSequence subtitle;

        Lines(CharSequence title, CharSequence subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** A live clock: counts up from {@code base}, or down to it. {@code millis} adds hundredths (stopwatch). */
    public static final class Chrono extends Slot {
        public final long base;
        public final boolean countDown;
        public final boolean millis;
        public final int color;

        Chrono(long base, boolean countDown, boolean millis, int color) {
            this.base = base;
            this.countDown = countDown;
            this.millis = millis;
            this.color = color;
        }
    }

    /** A progress ring; {@code value} is 0..1, or -1 when indeterminate. */
    public static final class Ring extends Slot {
        public final float value;
        public final int color;

        Ring(float value, int color) {
            this.value = value;
            this.color = color;
        }
    }

    /**
     * A progress bar; {@code value} is 0..1, or -1 when indeterminate. Segmented when {@code segmentColors}
     * is set (segment i starts at {@code segmentStarts[i]}), with an optional marker at the current position.
     */
    public static final class Bar extends Slot {
        public final float value;
        public final int color;
        public final int[] segmentColors;
        public final float[] segmentStarts;
        public final Drawable thumb;

        Bar(float value, int color, int[] segmentColors, float[] segmentStarts, Drawable thumb) {
            this.value = value;
            this.color = color;
            this.segmentColors = segmentColors;
            this.segmentStarts = segmentStarts;
            this.thumb = thumb;
        }

        public boolean segmented() {
            return segmentColors != null;
        }
    }

    /** A row of buttons. */
    public static final class Actions extends Slot {
        public final List<IslandItem.Action> actions;

        Actions(List<IslandItem.Action> actions) {
            this.actions = Collections.unmodifiableList(actions);
        }
    }
}
