package dev.android.dynamicisland.island.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LiveSlotsBuilderTest {
    private static final Drawable ICON = new ColorDrawable(0xFFFFFFFF);
    private static final Drawable ART = new ColorDrawable(0xFF1E88E5);

    private static IslandItem live() {
        IslandItem it = new IslandItem("live:test");
        it.icon = ICON;
        it.title = "Driver arriving";
        it.subtitle = "Toyota Camry";
        it.accent = 0xFF34C759;
        return it;
    }

    @Test
    public void shortCriticalTextGoesTrailingAndStaysTopRightWhenExpanded() {
        IslandItem it = live();
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = "3 min";

        LiveSlots s = LiveSlotsBuilder.build(it);

        assertSame(ICON, ((LiveSlots.Icon) s.compactLeading).drawable);
        assertEquals("3 min", ((LiveSlots.Text) s.compactTrailing).text);
        assertSame(s.compactTrailing, s.expandedTrailing);
        LiveSlots.Lines center = (LiveSlots.Lines) s.expandedCenter;
        assertEquals("Driver arriving", center.title);
        assertEquals("Toyota Camry", center.subtitle);
        assertTrue(s.expandedBottom.isEmpty());
        assertEquals(LiveSlots.Reach.NONE, s.bottomReach);
        // no progress: two activities fall back to the icon
        assertSame(s.compactLeading, s.minimal);
    }

    @Test
    public void progressIsRingWhenCompactAndBarWhenExpandedNeverBoth() {
        IslandItem it = live();
        it.trailing = IslandItem.Trailing.PROGRESS_RING;
        it.progress = 0.4f;

        LiveSlots s = LiveSlotsBuilder.build(it);

        assertEquals(0.4f, ((LiveSlots.Ring) s.compactTrailing).value, 0.0f);
        assertTrue(s.minimal instanceof LiveSlots.Ring);
        assertNull(s.expandedTrailing);
        LiveSlots.Bar bar = (LiveSlots.Bar) s.expandedBottom.get(0);
        assertEquals(0.4f, bar.value, 0.0f);
        assertEquals(0xFF34C759, bar.color);
        assertEquals(LiveSlots.Reach.INSET, s.bottomReach);
    }

    @Test
    public void chronometerKeepsItsClockAndTheBarStillShowsProgress() {
        IslandItem it = live();
        it.trailing = IslandItem.Trailing.CHRONOMETER;
        it.chronoBase = 123_456L;
        it.chronoCountDown = true;
        it.progress = 0.5f;

        LiveSlots s = LiveSlotsBuilder.build(it);

        LiveSlots.Chrono chrono = (LiveSlots.Chrono) s.compactTrailing;
        assertEquals(123_456L, chrono.base);
        assertTrue(chrono.countDown);
        assertSame(chrono, s.expandedTrailing);
        assertTrue(s.expandedBottom.get(0) instanceof LiveSlots.Bar);
    }

    @Test
    public void segmentedProgressStyleBarWithActionsReachesTheCorners() {
        IslandItem it = live();
        it.progress = 0.55f;
        it.progressSegmentColors = new int[]{0xFF34C759, 0xFFFFCC00, 0xFFFF3B30};
        it.progressSegmentStarts = new float[]{0.0f, 0.4f, 0.8f};
        it.progressThumb = ICON;
        it.actions.add(new IslandItem.Action("Cancel", null, null, null, true));

        LiveSlots s = LiveSlotsBuilder.build(it);

        LiveSlots.Bar bar = (LiveSlots.Bar) s.expandedBottom.get(0);
        assertTrue(bar.segmented());
        assertSame(ICON, bar.thumb);
        assertEquals(1, ((LiveSlots.Actions) s.expandedBottom.get(1)).actions.size());
        assertEquals(LiveSlots.Reach.ACTION, s.bottomReach);
    }

    @Test
    public void indeterminateProgressIsMinusOne() {
        IslandItem it = live();
        it.progressIndeterminate = true;

        LiveSlots s = LiveSlotsBuilder.build(it);

        assertEquals(-1.0f, ((LiveSlots.Ring) s.compactTrailing).value, 0.0f);
        assertEquals(-1.0f, ((LiveSlots.Bar) s.expandedBottom.get(0)).value, 0.0f);
    }

    @Test
    public void cardArtworkLeadsTheExpandedView() {
        IslandItem it = live();
        it.cardImage = ART;
        it.cardImageIsImage = true;
        it.cardImageRound = true;

        LiveSlots s = LiveSlotsBuilder.build(it);

        LiveSlots.Image image = (LiveSlots.Image) s.expandedLeading;
        assertSame(ART, image.drawable);
        assertTrue(image.round);
        assertTrue(s.compactLeading instanceof LiveSlots.Icon);
    }

    @Test
    public void matchPutsOneTeamBadgeOnEachSide() {
        IslandItem it = live();
        it.leftImage = ICON;
        it.rightImage = ART;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = "2 - 1";

        LiveSlots s = LiveSlotsBuilder.build(it);

        assertSame(ICON, ((LiveSlots.Image) s.compactLeading).drawable);
        assertSame(ART, ((LiveSlots.Image) s.compactTrailing).drawable);
    }

    @Test
    public void emptySeparatorTextIsNotShown() {
        IslandItem it = live();
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = "-";

        assertNull(LiveSlotsBuilder.build(it).compactTrailing);
    }

    @Test
    public void subtitleAloneBecomesTheTitle() {
        IslandItem it = live();
        it.title = null;

        LiveSlots.Lines center = (LiveSlots.Lines) LiveSlotsBuilder.build(it).expandedCenter;

        assertEquals("Toyota Camry", center.title);
        assertNull(center.subtitle);
    }

    @Test
    public void noAccentMeansDefaultColour() {
        IslandItem it = live();
        it.accent = -1;
        it.progress = 0.2f;

        assertEquals(0, ((LiveSlots.Ring) LiveSlotsBuilder.build(it).compactTrailing).color);
    }
}
