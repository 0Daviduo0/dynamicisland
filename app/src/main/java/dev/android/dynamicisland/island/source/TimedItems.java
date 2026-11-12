package dev.android.dynamicisland.island.source;

import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.model.IslandItem;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Short-lived items of one source: each lives until its deadline, then the island refreshes without it. */
final class TimedItems {
    private final Handler main;
    private final Runnable onChange;
    private final Map<String, IslandItem> items = new LinkedHashMap<>();
    private final Runnable tick;

    TimedItems(Handler main, Runnable onChange) {
        this.main = main;
        this.onChange = onChange;
        this.tick = onChange::run;
    }

    /** Shows {@code item} for {@code ttlMs} as a new arrival (it replaces an item with the same key). */
    void show(IslandItem item, long ttlMs) {
        long now = SystemClock.uptimeMillis();
        item.expiresAtUptime = now + ttlMs;
        item.postTime = now;
        items.remove(item.key);
        items.put(item.key, item);
        onChange.run();
    }

    /** Replaces a shown item's content but keeps its deadline and arrival, so the island does not announce it again. */
    boolean refresh(IslandItem fresh) {
        IslandItem old = items.get(fresh.key);
        if (old == null) return false;
        fresh.expiresAtUptime = old.expiresAtUptime;
        fresh.postTime = old.postTime;
        items.put(fresh.key, fresh);
        onChange.run();
        return true;
    }

    boolean has(String key) {
        IslandItem it = items.get(key);
        return it != null && it.expiresAtUptime > SystemClock.uptimeMillis();
    }

    void remove(String key) {
        if (items.remove(key) != null) onChange.run();
    }

    void collect(List<IslandItem> out) {
        if (items.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        long next = Long.MAX_VALUE;
        for (Iterator<IslandItem> it = items.values().iterator(); it.hasNext(); ) {
            IslandItem item = it.next();
            if (item.expiresAtUptime <= now) {
                it.remove();
                continue;
            }
            out.add(item);
            next = Math.min(next, item.expiresAtUptime);
        }
        if (next != Long.MAX_VALUE) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, Math.max(16L, next - now + 20));
        }
    }
}
