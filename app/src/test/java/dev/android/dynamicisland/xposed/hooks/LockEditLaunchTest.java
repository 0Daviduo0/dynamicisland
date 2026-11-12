package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LockEditLaunchTest {
    private final Object controller = new Object();
    private final Object starter = new Object();
    private final Context context = RuntimeEnvironment.getApplication();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> calls = new ArrayList<>();
    private final List<Runnable> background = new ArrayList<>();
    private final Executor queued = background::add;

    /** The stack seen inside getWallpaperBitmap: hook bridge frames, then the System UI caller. */
    private static StackTraceElement[] stack(String callerClass, String callerMethod) {
        return new StackTraceElement[] {
            new StackTraceElement("dev.android.dynamicisland.xposed.SafeHook", "beforeHookedMethod", null, 0),
            new StackTraceElement("de.robv.android.xposed.XposedBridge", "handleHookedMethod", null, 0),
            new StackTraceElement("LSPHooker_", "getWallpaperBitmap", null, 0),
            new StackTraceElement(callerClass, callerMethod, null, 0),
            new StackTraceElement("android.os.Handler", "dispatchMessage", null, 0),
        };
    }

    private static final StackTraceElement[] FROM_START = stack(LockEditLaunch.CONTROLLER, LockEditLaunch.START_EDIT);

    private final LockEditLaunch.Ops ops = new LockEditLaunch.Ops() {
        @Override public Object capture(LockEditLaunch.Launch l) {
            assertNotSame("the preview is fetched off the main thread", Thread.currentThread(), Looper.getMainLooper().getThread());
            calls.add("capture");
            return "bitmap";
        }
        @Override public void save(LockEditLaunch.Launch l, Object bitmap) {
            calls.add("save " + bitmap);
        }
        @Override public void start(LockEditLaunch.Launch l) {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            calls.add("start " + ((Intent) l.startArgs[0]).getAction());
        }
    };

    @After public void reset() {
        LockEditLaunch.end();
    }

    /** Runs startEditActivity(context, secure) as System UI does: preview fetch, then the editor start. */
    private LockEditLaunch.Launch startEdit(boolean secure, StackTraceElement[] captureStack) {
        LockEditLaunch.begin(controller, new Object[] {context, secure}, true);
        boolean captureSkipped = LockEditLaunch.skipCapture(controller, new Object[] {context, secure}, null, captureStack);
        boolean startDeferred = LockEditLaunch.deferStart(starter, new Object[] {new Intent("EDIT"), 0}, null);
        assertEquals(captureSkipped, startDeferred);
        return LockEditLaunch.end();
    }

    private void runBackground() throws InterruptedException {
        for (Runnable task : background) {
            Thread thread = new Thread(task);
            thread.start();
            thread.join();
        }
        background.clear();
    }

    @Test public void securedLaunchTakesThePreviewInTheBackgroundThenStartsTheEditorOnce() throws Exception {
        LockEditLaunch.Launch launch = startEdit(true, FROM_START);
        assertNotNull(launch);
        assertSame(context, launch.context);
        LockEditLaunch.finish(launch, ops, queued, main);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("nothing starts before the screenshot is taken", calls.isEmpty());
        runBackground();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("capture", "save bitmap", "start EDIT"), calls);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LockEditLaunch.START_TIMEOUT_MS * 2));
        assertEquals("the timeout does not start it again", 3, calls.size());
    }

    @Test public void aFailedPreviewStillStartsTheEditor() throws Exception {
        LockEditLaunch.Launch launch = startEdit(true, FROM_START);
        LockEditLaunch.finish(launch, new LockEditLaunch.Ops() {
            @Override public Object capture(LockEditLaunch.Launch l) { throw new IllegalStateException("no screenshot"); }
            @Override public void save(LockEditLaunch.Launch l, Object bitmap) { calls.add("save"); }
            @Override public void start(LockEditLaunch.Launch l) { calls.add("start"); }
        }, queued, main);
        runBackground();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("start"), calls);
    }

    @Test public void aStuckPreviewStartsTheEditorAfterTheTimeout() {
        LockEditLaunch.Launch launch = startEdit(true, FROM_START);
        LockEditLaunch.finish(launch, ops, queued, main); // the background task never runs
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LockEditLaunch.START_TIMEOUT_MS - 1));
        assertTrue(calls.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(List.of("start EDIT"), calls);
    }

    @Test public void unsecuredLaunchAndDisplayedPreviewsAreLeftAlone() {
        assertNull("no bouncer: System UI does not fetch a preview", startEdit(false, FROM_START));
        // the edit animation's preview (updateViews lambda) is shown, so it must keep its bitmap
        assertNull(startEdit(true, stack(LockEditLaunch.CONTROLLER + "$$ExternalSyntheticLambda0", "invoke")));
        assertNull(startEdit(true, null));
        // outside startEditActivity nothing is intercepted
        assertFalse(LockEditLaunch.skipCapture(controller, new Object[] {context, true}, null, FROM_START));
        assertFalse(LockEditLaunch.deferStart(starter, new Object[] {new Intent("EDIT"), 0}, null));
    }

    @Test public void onlyTheMainThreadCallOfThatControllerIsTracked() {
        assertNull(LockEditLaunch.begin(controller, new Object[] {context, true}, false));
        assertNull(LockEditLaunch.end());
        LockEditLaunch.begin(controller, new Object[] {context, true}, true);
        assertFalse("another controller's call", LockEditLaunch.skipCapture(new Object(), new Object[] {context, true}, null, FROM_START));
        assertFalse("no capture skipped: the start is not held", LockEditLaunch.deferStart(starter, new Object[] {new Intent("EDIT"), 0}, null));
        assertNull(LockEditLaunch.end());
    }

    @Test public void theFrameCheckSkipsBridgeFramesAndLooksAtTheFirstSystemUiCaller() {
        assertTrue(LockEditLaunch.calledFromStartEdit(FROM_START));
        assertFalse(LockEditLaunch.calledFromStartEdit(stack("com.android.systemui.shade.NotificationPanelViewController", "startEditActivity")));
        assertFalse(LockEditLaunch.calledFromStartEdit(new StackTraceElement[0]));
    }
}
