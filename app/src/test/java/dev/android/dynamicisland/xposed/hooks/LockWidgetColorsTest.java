package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

public class LockWidgetColorsTest {
    private static final int WHITE = 0xffffffff, DARK = 0xff202020;
    private final Object manager = new Object();

    @After public void reset() {
        LockWidgetColors.reset();
    }

    @Test public void theFirstPostOfAnAnimationRunsAndItsRepeatsAreSkipped() {
        assertFalse("first post applies the color", LockWidgetColors.skip(manager, WHITE, -1, 1000));
        for (long t = 1016; t < 1600; t += 16) {
            assertTrue("same color, frame after frame", LockWidgetColors.skip(manager, WHITE, WHITE, t));
        }
    }

    @Test public void aColorChangeAlwaysRuns() {
        LockWidgetColors.skip(manager, WHITE, -1, 1000);
        assertFalse(LockWidgetColors.skip(manager, DARK, WHITE, 1016));
        assertTrue(LockWidgetColors.skip(manager, DARK, DARK, 1032));
        assertFalse("back to the first color", LockWidgetColors.skip(manager, WHITE, DARK, 1048));
    }

    @Test public void theNextScreenOnRunsInFull() {
        LockWidgetColors.skip(manager, WHITE, -1, 1000);
        assertTrue(LockWidgetColors.skip(manager, WHITE, WHITE, 1500));
        assertFalse("after a quiet second the same color is applied again", LockWidgetColors.skip(manager, WHITE, WHITE, 1500 + LockWidgetColors.WINDOW_MS));
    }

    @Test public void aColorSetElsewhereOrAnotherManagerIsNotSkipped() {
        LockWidgetColors.skip(manager, WHITE, -1, 1000);
        assertFalse("the manager holds another color now", LockWidgetColors.skip(manager, WHITE, DARK, 1016));
        assertFalse("each manager on its own", LockWidgetColors.skip(new Object(), WHITE, WHITE, 1032));
    }
}
