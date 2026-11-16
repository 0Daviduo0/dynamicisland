package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

public class BriefPopupHoleTest {
    // a 900 x 130 pill whose centre is 40 px below a 44 px hole, its icon 385 px left of its centre
    private static final float W = 900, H = 130, ICON = -385, FROM_X = 0, FROM_Y = -40, HOLE = 44;

    private static BriefPopupHole.Frame at(float t) {
        return BriefPopupHole.frame(t, W, H, ICON, FROM_X, FROM_Y, HOLE, new BriefPopupHole.Frame());
    }

    /** Where the capsule's centre is on screen, relative to the hole. */
    private static float capsuleX(BriefPopupHole.Frame f) {
        return -FROM_X + f.centre + f.dx;
    }

    @Test
    public void inTheHoleItIsAHiddenCircle() {
        BriefPopupHole.Frame f = at(0);
        assertEquals("a circle", f.width, f.height, 0.01f);
        assertTrue("smaller than the hole", f.width < HOLE);
        assertEquals("on the hole", 0f, capsuleX(f), 0.01f);
        assertEquals(FROM_Y, f.dy, 0.01f);
        assertEquals(0f, f.textAlpha, 0f);
    }

    @Test
    public void itLeavesTheHoleAsTheIconAlone() {
        for (float t = 0; t <= BriefPopupHole.STRETCH_FROM; t += 0.01f) {
            BriefPopupHole.Frame f = at(t);
            assertEquals("a circle at t=" + t, f.height, f.width, 0.01f);
            assertEquals("around the icon", ICON, f.centre, 0.01f);
            assertEquals("no text", 0f, f.textAlpha, 0f);
        }
    }

    @Test
    public void itOpensWhileItIsStillComingDown() {
        BriefPopupHole.Frame f = at(0.35f);
        assertTrue("wider than tall", f.width > f.height + 1f);
        assertTrue("not yet in place", f.dy < -1f);
        assertEquals("icon visible", 1f, f.iconAlpha, 0f);
    }

    @Test
    public void inPlaceItIsThePillUntouched() {
        BriefPopupHole.Frame f = at(1);
        assertEquals(W, f.width, 0.01f);
        assertEquals(H, f.height, 0.01f);
        assertEquals(0f, f.centre, 0.01f);
        assertEquals(0f, f.dx, 0.01f);
        assertEquals(0f, f.dy, 0f);
        assertEquals(1f, f.scale, 0.001f);
        assertEquals(1f, f.textAlpha, 0f);
    }

    @Test
    public void whileOpeningItStaysCentredUnderTheHoleAndFullyRound() {
        for (float t = 0; t <= 1.0001f; t += 0.02f) {
            BriefPopupHole.Frame f = at(t);
            assertEquals("centred at t=" + t, 0f, capsuleX(f), 0.5f);
            assertTrue(f.width >= f.height - 0.01f);
            assertTrue(f.width <= W + 0.01f);
            assertTrue(f.dy >= FROM_Y - 0.01f && f.dy <= 0.01f);
        }
    }
}
