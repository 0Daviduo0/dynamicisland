package dev.android.dynamicisland.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ForegroundAppTest {
    private final AtomicLong clock = new AtomicLong(10_000);
    private final AtomicInteger queries = new AtomicInteger();
    private final List<Long> rechecks = new ArrayList<>();
    private String top = "com.app.one";

    private ForegroundApp app() {
        return new ForegroundApp(() -> {
            queries.incrementAndGet();
            return Collections.singletonList(new ForegroundApp.Task(top, true, true, false));
        }, () -> "vendor.cached", clock::get, rechecks::add);
    }

    @Before
    public void setUp() {
        ForegroundApp.setTracking(true);
    }

    @After
    public void tearDown() {
        ForegroundApp.setTracking(false);
    }

    @Test
    public void reusesSnapshotWhileNoTaskChangedAndSchedulesOneRecheck() {
        ForegroundApp app = app();
        assertEquals("com.app.one", app.read().packageName);
        clock.addAndGet(300);
        top = "com.app.two"; // not reported: the snapshot is reused until it expires
        assertEquals("com.app.one", app.read().packageName);
        clock.addAndGet(100);
        app.read();
        assertEquals(1, queries.get());
        assertEquals(Collections.singletonList(ForegroundApp.MAX_REUSE_MS - 300), rechecks);
        clock.addAndGet(ForegroundApp.MAX_REUSE_MS);
        assertEquals("com.app.two", app.read().packageName);
        assertEquals(2, queries.get());
    }

    @Test
    public void taskChangeForcesFreshQuery() {
        ForegroundApp app = app();
        app.read();
        top = "com.app.two";
        ForegroundApp.taskChanged();
        assertEquals("com.app.two", app.read().packageName);
        assertEquals(2, queries.get());
    }

    @Test
    public void withoutTrackingEveryReadQueries() {
        ForegroundApp.setTracking(false);
        ForegroundApp app = app();
        app.read();
        app.read();
        app.read();
        assertEquals(3, queries.get());
        assertEquals(0, rechecks.size());
    }

    @Test
    public void failedQueryIsNotReused() {
        AtomicInteger calls = new AtomicInteger();
        ForegroundApp app = new ForegroundApp(() -> {
            if (calls.incrementAndGet() == 1) throw new SecurityException("denied");
            return Collections.singletonList(new ForegroundApp.Task("com.app.one", true, true, false));
        }, () -> null, clock::get, rechecks::add);
        ForegroundApp.State first = app.read();
        assertNull(first.packageName);
        assertFalse(first.confirmed);
        assertEquals("com.app.one", app.read().packageName);
        assertEquals(2, calls.get());
    }
}
