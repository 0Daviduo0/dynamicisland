package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

public class CameraLaunchSpringTest {
    private static final float W = 1440, H = 3120, KEY_Y = 1100, CORNER = 120;

    private static CameraLaunchSpring.Frame at(double t) {
        return CameraLaunchSpring.frame(t, W, H, KEY_Y, CORNER, new CameraLaunchSpring.Frame());
    }

    @Test
    public void itLeavesTheKeyAsATinyRoundedCard() {
        CameraLaunchSpring.Frame f = at(0);
        assertEquals("on the right edge", W, f.x + W * f.scale, 0.5f);
        assertEquals("level with the key", KEY_Y, f.y + H * f.scale / 2, 0.5f);
        assertTrue("small", W * f.scale < 100);
        assertTrue("rounded", f.radius > 0);
        assertEquals("hidden at first", 0f, f.alpha, 0f);
    }

    @Test
    public void itReachesTheCentreAsACardBeforeItOpens() {
        CameraLaunchSpring.Frame f = at(CameraLaunchSpring.EXPAND_DELAY);
        float cx = f.x + W * f.scale / 2, cy = f.y + H * f.scale / 2;
        assertTrue("well away from the key", cx < W * 0.75f);
        assertTrue("towards the centre", Math.abs(cy - H / 2) < Math.abs(KEY_Y - H / 2));
        assertTrue("still a card", f.scale < 0.45f);
    }

    @Test
    public void itEndsFullScreen() {
        CameraLaunchSpring.Frame f = at(CameraLaunchSpring.DURATION_MS / 1000.0);
        assertEquals(1f, f.scale, 0.02f);
        assertEquals(0f, f.x, 20f);
        assertEquals(0f, f.y, 40f);
        assertEquals(CORNER, f.radius, 5f);
        assertEquals(1f, f.alpha, 0f);
    }

    @Test
    public void theOpeningSpringOvershootsOnlyALittle() {
        float max = 0;
        for (int ms = 0; ms <= (int) CameraLaunchSpring.DURATION_MS; ms += 4) max = Math.max(max, at(ms / 1000.0).scale);
        assertTrue("overshoots", max > 1.01f);
        assertTrue("but only a little", max < 1.1f);
    }
}
