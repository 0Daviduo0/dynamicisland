package dev.android.dynamicisland.island;

import android.app.ActivityManager;
import android.content.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A fresh task snapshot wins over the vendor's potentially stale base-activity cache.
 * The snapshot is a binder call to system_server; while System UI's task-change callbacks are hooked
 * ({@link #setTracking}), a snapshot is reused for up to {@link #MAX_REUSE_MS} as long as no task changed,
 * and a reuse schedules one recheck at its end, so a change the callbacks miss still shows within that time.
 */
public final class ForegroundApp {
    static final long MAX_REUSE_MS = 1000;
    private static final AtomicLong TASK_CHANGES = new AtomicLong();
    private static volatile boolean tracking;

    private final Supplier<List<Task>> taskQuery;
    private final Supplier<String> cachedPackage;
    private final LongSupplier clock;
    /** Asks for one more read after the given delay (ms); may be null. */
    private final LongConsumer recheck;
    private List<Task> lastTasks;
    private long lastChange = -1;
    private long lastAt;
    private boolean recheckPosted;
    private int reads;
    private int reused;

    ForegroundApp(Context context, Supplier<String> cachedPackage, LongConsumer recheck) {
        this(() -> queryTasks(context), cachedPackage, android.os.SystemClock::uptimeMillis, recheck);
    }

    ForegroundApp(Supplier<List<Task>> taskQuery, Supplier<String> cachedPackage) {
        this(taskQuery, cachedPackage, android.os.SystemClock::uptimeMillis, null);
    }

    ForegroundApp(Supplier<List<Task>> taskQuery, Supplier<String> cachedPackage, LongSupplier clock, LongConsumer recheck) {
        this.taskQuery = taskQuery;
        this.cachedPackage = cachedPackage;
        this.clock = clock;
        this.recheck = recheck;
    }

    /** A task moved, changed focus or visibility (System UI's task-stack callbacks). Any thread. */
    public static void taskChanged() {
        TASK_CHANGES.incrementAndGet();
    }

    /** Whether the task-change callbacks are hooked; without them every read queries the tasks. */
    public static void setTracking(boolean on) {
        tracking = on;
    }

    /** The task snapshot: reused while no task changed and it is recent, else queried. Main thread only. */
    private List<Task> tasks() {
        long now = clock.getAsLong();
        long change = TASK_CHANGES.get();
        long age = now - lastAt;
        if (++reads % 300 == 0) {
            dev.android.dynamicisland.xposed.XLog.i("foreground tasks: " + reused + " of " + reads + " reads reused");
        }
        if (tracking && lastTasks != null && change == lastChange && age >= 0 && age < MAX_REUSE_MS) {
            if (!recheckPosted && recheck != null) {
                recheckPosted = true;
                recheck.accept(MAX_REUSE_MS - age);
            }
            reused++;
            return lastTasks;
        }
        lastTasks = null;
        List<Task> tasks = taskQuery.get();
        lastTasks = tasks;
        lastChange = change;
        lastAt = now;
        recheckPosted = false;
        return tasks;
    }

    static final class State {
        final String packageName;
        final boolean confirmed;

        State(String packageName, boolean confirmed) {
            this.packageName = packageName;
            this.confirmed = confirmed;
        }
    }

    static final class Task {
        final String pkg;
        final Boolean focused;
        final boolean visible;
        final boolean pinned;

        /** Order: top-activity package, focus (null = unavailable), visibility, PiP. */
        Task(String pkg, Boolean focused, boolean visible, boolean pinned) {
            this.pkg = pkg;
            this.focused = focused;
            this.visible = visible;
            this.pinned = pinned;
        }
    }

    State read() {
        List<Task> tasks;
        try {
            tasks = tasks();
        } catch (RuntimeException | LinkageError ignored) {
            // Permission/API failures are uncertain, not a reason to disable later queries.
            tasks = null;
        }
        Set<String> pinnedPackages = new HashSet<>();
        Task firstEligible = null;
        Task focused = null;
        boolean hasFocusInformation = false;
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null) continue;
                // Even an ineligible task's focus flag proves that this API exposes focus.
                hasFocusInformation |= task.focused != null;
                if (task.pinned && task.pkg != null) pinnedPackages.add(task.pkg);
                if (task.pinned || !task.visible || task.pkg == null || task.pkg.isEmpty()) {
                    continue;
                }
                if (firstEligible == null) firstEligible = task;
                if (focused == null && Boolean.TRUE.equals(task.focused)) focused = task;
            }
        }
        Task selected = focused != null ? focused : (!hasFocusInformation ? firstEligible : null);
        if (selected != null) return new State(selected.pkg, true);

        String cached;
        try {
            cached = cachedPackage.get();
        } catch (RuntimeException | LinkageError ignored) {
            cached = null;
        }
        if (cached != null && (cached.isEmpty() || pinnedPackages.contains(cached))) cached = null;
        return new State(cached, false);
    }

    @SuppressWarnings("deprecation")
    private static List<Task> queryTasks(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        List<ActivityManager.RunningTaskInfo> running = manager.getRunningTasks(5);
        if (running == null) return null;
        List<Task> tasks = new ArrayList<>(running.size());
        for (ActivityManager.RunningTaskInfo info : running) {
            if (info == null) continue;
            String pkg = info.topActivity == null ? null : info.topActivity.getPackageName();
            Object focus = field(info, "isFocused");
            Object visible = field(info, "isVisible");
            Object mode = call(info, "getWindowingMode");
            if (!(mode instanceof Number)) {
                Object configuration = field(info, "configuration");
                mode = call(field(configuration, "windowConfiguration"), "getWindowingMode");
            }
            tasks.add(new Task(pkg, focus instanceof Boolean ? (Boolean) focus : null,
                    !Boolean.FALSE.equals(visible),
                    mode instanceof Number && ((Number) mode).intValue() == 2));
        }
        return tasks;
    }

    // Optional framework details vary by OS/OEM. Do not turn absent members into Refl misses.
    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
