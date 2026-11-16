package dev.android.dynamicisland.island.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Status events with nothing more than their pill do not open a card; everything else does. */
public class OpensCardTest {
    @Test public void plainChargingEventOnlyBounces() {
        IslandItem it = new IslandItem("event:charging");
        it.eventLayout = IslandItem.EventLayout.BATTERY;
        assertFalse(it.opensCard());
    }

    @Test public void ringerStatusOnlyBounces() {
        IslandItem it = new IslandItem("event:ringer");
        it.eventLayout = IslandItem.EventLayout.STATUS;
        assertFalse(it.opensCard());
    }

    @Test public void chargingStatisticsOpen() {
        IslandItem it = new IslandItem("charging:info");
        it.eventLayout = IslandItem.EventLayout.BATTERY;
        it.description = "Schermo acceso: 12 min";
        assertTrue(it.opensCard());
    }

    @Test public void eventWithButtonsOpens() {
        IslandItem it = new IslandItem("event:x");
        it.eventLayout = IslandItem.EventLayout.STATUS;
        it.actions.add(new IslandItem.Action("Ok", null, null, () -> { }, false));
        assertTrue(it.opensCard());
    }

    @Test public void notificationsAndMediaOpen() {
        assertTrue(new IslandItem("n:1").opensCard());
    }
}
