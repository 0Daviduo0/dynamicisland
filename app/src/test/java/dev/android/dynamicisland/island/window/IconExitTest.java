package dev.android.dynamicisland.island.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class IconExitTest {
    @Test
    public void shrinksFirstThenBlursPastTheThresholdThenFades() {
        assertEquals(1.0f, IconExit.scale(0.0f), 0.0f);
        assertEquals(IconExit.MIN_SCALE, IconExit.scale(1.0f), 1e-6f);

        // early on it only shrinks: sharp and fully bright
        assertTrue(IconExit.scale(0.2f) < 1.0f);
        assertEquals(0.0f, IconExit.blur(0.2f), 0.0f);
        assertEquals(1.0f, IconExit.alpha(0.2f), 0.0f);

        // the blur joins at about scale 0.91, before the fade
        assertTrue(IconExit.scale(IconExit.BLUR_FROM) > 0.9f);
        assertTrue(IconExit.blur(0.4f) > 0.0f);
        assertEquals(1.0f, IconExit.alpha(0.4f), 0.0f);

        // and it ends fully blurred and gone
        assertEquals(1.0f, IconExit.blur(1.0f), 1e-6f);
        assertEquals(0.0f, IconExit.alpha(1.0f), 1e-6f);
    }

    @Test
    public void blurGrowsFasterAndFasterOnceStarted() {
        float a = IconExit.blur(0.5f) - IconExit.blur(0.4f);
        float b = IconExit.blur(1.0f) - IconExit.blur(0.9f);
        assertTrue(b > a);
    }

    @Test
    public void pushOnlyMovesTheViewAndLeavesItsAlphaToSystemUi() {
        View v = new View(RuntimeEnvironment.getApplication());
        v.setTransitionAlpha(0.5f); // System UI fading it with the shade
        IconExit exit = new IconExit(v, true, true, 2.0f);

        exit.setPush(-8.0f);
        ShadowLooper.idleMainLooper(2000, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertEquals(-8.0f, v.getTranslationX(), 0.0f);
        assertEquals(1.0f, v.getScaleX(), 0.0f); // settled: the squeeze is gone
        assertEquals(0.5f, v.getTransitionAlpha(), 0.0f);
    }

    @Test
    public void pushRidesABouncySpringPastItsTarget() {
        float[] furthest = {0.0f};
        float[] hardest = {0.0f};
        float[] last = {0.0f, 0.0f};
        IconNudge nudge = new IconNudge(2.0f, (offset, squeeze) -> {
            furthest[0] = Math.min(furthest[0], offset);
            hardest[0] = Math.max(hardest[0], squeeze);
            last[0] = offset;
            last[1] = squeeze;
        });

        nudge.setTarget(-40.0f);
        long t = 1_000_000_000L;
        for (int i = 0; i < 120; i++) {
            nudge.doFrame(t);
            t += 16_666_667L;
        }

        assertTrue("overshoots: " + furthest[0], furthest[0] < -40.5f);
        assertTrue("squeezed while moving: " + hardest[0], hardest[0] > 0.1f);
        assertEquals(-40.0f, last[0], 0.0f);
        assertEquals(0.0f, last[1], 0.0f);
    }

    @Test
    public void lightTouchOnlyShrinksAHardHitAlsoBlurs() {
        assertEquals(1.0f, IconExit.squeezeScale(0.0f), 0.0f);
        assertEquals(1.0f - IconExit.SQUEEZE_SCALE, IconExit.squeezeScale(1.0f), 1e-6f);
        assertEquals(0.0f, IconExit.squeezeBlur(0.2f), 0.0f);
        assertTrue(IconExit.squeezeBlur(0.8f) > 0.0f);
    }

    @Test
    public void leavingThenRestoringPutsTheViewBackUntouched() {
        View v = new View(RuntimeEnvironment.getApplication());
        v.setTranslationX(3.0f);
        IconExit exit = new IconExit(v, true, true, 2.0f);

        exit.animateTo(true, -1);
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertTrue(exit.isHiding());
        assertEquals(0.0f, v.getTransitionAlpha(), 1e-6f);
        assertEquals(IconExit.MIN_SCALE, v.getScaleX(), 1e-6f);
        assertTrue(v.getTranslationX() < 3.0f); // slid away from an island on its right

        exit.restore();

        assertFalse(exit.isHiding());
        assertEquals(1.0f, v.getTransitionAlpha(), 0.0f);
        assertEquals(1.0f, v.getScaleX(), 0.0f);
        assertEquals(3.0f, v.getTranslationX(), 0.0f);
    }

    @Test
    public void iconsSystemUiPositionsShrinkButNeverMove() {
        View v = new View(RuntimeEnvironment.getApplication());
        IconExit exit = new IconExit(v, true, false, 2.0f);

        exit.setPush(-8.0f);
        exit.animateTo(true, -1);
        ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertEquals(0.0f, v.getTranslationX(), 0.0f);
        assertEquals(IconExit.MIN_SCALE, v.getScaleX(), 1e-6f);
        assertEquals(0.0f, v.getTransitionAlpha(), 1e-6f);

        exit.restore();
        assertEquals(1.0f, v.getScaleX(), 0.0f);
    }
}
