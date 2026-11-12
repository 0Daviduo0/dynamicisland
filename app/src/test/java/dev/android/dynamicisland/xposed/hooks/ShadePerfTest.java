package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

public class ShadePerfTest {
    @Test public void resourceIdsAreKeptPerImplAndOnlyWhenFound() {
        Object impl = new Object(), other = new Object();
        Object[] clock = {"clock_top_margin", "dimen", "com.samsung.android.app.aodservice"};
        assertEquals(0, ShadePerf.ResourceIds.find(impl, clock));
        ShadePerf.ResourceIds.keep(impl, clock, 0x7f070001);
        assertEquals(0x7f070001, ShadePerf.ResourceIds.find(impl, clock.clone()));
        assertEquals("another Resources keeps its own ids", 0, ShadePerf.ResourceIds.find(other, clock));
        assertEquals("another type is another id", 0,
                ShadePerf.ResourceIds.find(impl, new Object[] {"clock_top_margin", "integer", "com.samsung.android.app.aodservice"}));
        Object[] missing = {"nope", "dimen", "android"};
        ShadePerf.ResourceIds.keep(impl, missing, 0);
        assertEquals("a name not found is asked again", 0, ShadePerf.ResourceIds.find(impl, missing));
        Object[] fullName = {"android:dimen/status_bar_height", null, null};
        ShadePerf.ResourceIds.keep(impl, fullName, 0x01050000);
        assertEquals(0x01050000, ShadePerf.ResourceIds.find(impl, fullName));
        assertNull("other signatures are not cached", ShadePerf.ResourceIds.key(new Object[] {"a", 1, "b"}));
        assertNull(ShadePerf.ResourceIds.key(new Object[] {"a", "b"}));
        assertEquals(0, ShadePerf.ResourceIds.find(null, clock));
    }

    @Test public void lockScreenNoiseIsDroppedButKeyguardCoreLogsStay() {
        assertTrue(ShadePerf.isNoisy("SemWallpaperColorsArea"));
        assertTrue(ShadePerf.isNoisy("DigitalHorizontalClockView_LOCK_SCREEN_2@244"));
        assertTrue(ShadePerf.isNoisy("[AOD]AppWidgetHost:applyAppWidgetColorFilter"));
        assertTrue(ShadePerf.isNoisy("ClockBlurManager@389_parent@244"));
        assertFalse(ShadePerf.isNoisy("KeyguardViewMediator"));
        assertFalse(ShadePerf.isNoisy("KeyguardEditModeController"));
        assertFalse(ShadePerf.isNoisy("DynamicIsland"));
    }
}
