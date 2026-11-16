package dev.android.dynamicisland.island.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Running clocks in a pop-up are told apart from its labels. */
public class AlertBridgeTest {
    @Test public void clocksAreNotLabels() {
        assertTrue(AlertBridge.isClockText("-00:00:03"));
        assertTrue(AlertBridge.isClockText("12:04"));
        assertTrue(AlertBridge.isClockText("1:02:03"));
        assertFalse(AlertBridge.isClockText("Timer (5 s)"));
        assertFalse(AlertBridge.isClockText("Interrompi"));
        assertFalse(AlertBridge.isClockText(null));
    }
}
