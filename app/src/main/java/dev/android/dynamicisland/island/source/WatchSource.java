package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Parcelable;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Galaxy Watch (and Galaxy Ring / Fit) cards from One UI's companion battery list, the same data as the stock battery
 * widget. Galaxy Wearable reports each connected wearable to the battery service, which then broadcasts
 * {@code com.samsung.battery.ACTION_BATTERY_INFO_ADDED / CHANGED / REMOVED} with a
 * {@code SemCompanionDeviceBatteryInfo} (address, name, type, level, status).
 *
 * <p>Added = connected, removed = disconnected, a status turning to charging or full = a charging card.
 */
public final class WatchSource {
    private static final String ACTION_ADDED = "com.samsung.battery.ACTION_BATTERY_INFO_ADDED";
    private static final String ACTION_CHANGED = "com.samsung.battery.ACTION_BATTERY_INFO_CHANGED";
    private static final String ACTION_REMOVED = "com.samsung.battery.ACTION_BATTERY_INFO_REMOVED";
    private static final String EXTRA_INFO = "com.samsung.battery.EXTRA_BATTERY_INFO";
    private static final int TYPE_WATCH = 4, TYPE_FIT = 6, TYPE_RING = 7;
    private static final long CONNECT_MS = 4500, DISCONNECT_MS = 2500, CHARGE_MS = 3500;
    private static final String WEARABLE_PKG = "com.samsung.android.app.watchmanager";

    private final Context ctx;
    private final TimedItems items;
    private final AppLook apps;
    /** Last state per address: {level, status}. */
    private final Map<String, int[]> known = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    public WatchSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.items = new TimedItems(main, onChange);
        this.apps = new AppLook(ctx);
    }

    public void start() {
        baseline();
        IntentFilter filter = new IntentFilter(ACTION_ADDED);
        filter.addAction(ACTION_CHANGED);
        filter.addAction(ACTION_REMOVED);
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        onInfo(intent);
                    } catch (Throwable t) {
                        XLog.once("watch battery info", t);
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("watch receiver not registered", t);
        }
    }

    /** The wearables already connected when System UI starts are not new connections. */
    private void baseline() {
        try {
            Object infos = Refl.call(ctx.getSystemService(BatteryManager.class), "semGetDeviceBatteryInfos");
            if (!(infos instanceof Object[])) return;
            for (Object info : (Object[]) infos) {
                if (info == null || !wearable(intOf(info, "getDeviceType"))) continue;
                String address = String.valueOf(Refl.call(info, "getAddress"));
                known.put(address, new int[] {intOf(info, "getBatteryLevel"), intOf(info, "getBatteryStatus")});
            }
        } catch (Throwable t) {
            XLog.once("watch battery baseline", t);
        }
    }

    private void onInfo(Intent intent) {
        Parcelable info = intent.getParcelableExtra(EXTRA_INFO);
        if (info == null || !wearable(intOf(info, "getDeviceType"))) return;
        Object addressObj = Refl.call(info, "getAddress");
        if (addressObj == null) return;
        String address = addressObj.toString();
        Object name = Refl.call(info, "getDeviceName");
        if (name != null && !name.toString().isEmpty()) names.put(address, name.toString());
        int level = intOf(info, "getBatteryLevel");
        int status = intOf(info, "getBatteryStatus");
        int type = intOf(info, "getDeviceType");
        String action = intent.getAction();
        int[] before = known.get(address);
        if (ACTION_REMOVED.equals(action)) {
            known.remove(address);
            items.remove(key(address, "connect"));
            if (ModuleSettings.watchCard() && before != null) items.show(card(address, type, before[0], 0, "disconnect"), DISCONNECT_MS);
            return;
        }
        known.put(address, new int[] {level, status});
        if (!ModuleSettings.watchCard()) return;
        if (before == null) {
            items.show(card(address, type, level, status, "connect"), CONNECT_MS);
            return;
        }
        boolean chargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean chargingBefore = before[1] == BatteryManager.BATTERY_STATUS_CHARGING || before[1] == BatteryManager.BATTERY_STATUS_FULL;
        if (chargingNow && (!chargingBefore || status == BatteryManager.BATTERY_STATUS_FULL && before[1] != status)) {
            items.remove(key(address, "connect"));
            items.show(card(address, type, level, status, "charge"), CHARGE_MS);
            return;
        }
        // a card already on screen follows the new level
        if (!items.refresh(card(address, type, level, status, "connect"))) {
            items.refresh(card(address, type, level, status, "charge"));
        }
    }

    private IslandItem card(String address, int type, int level, int status, String what) {
        IslandItem it = new IslandItem(key(address, what));
        it.kind = IslandItem.Kind.EVENT;
        it.eventLayout = IslandItem.EventLayout.NOW;
        it.packageName = WEARABLE_PKG;
        String name = names.get(address);
        String product = type == TYPE_RING ? "Galaxy Ring" : type == TYPE_FIT ? "Galaxy Fit" : "Galaxy Watch";
        GlyphIcon.Kind glyph = GlyphIcon.Kind.WATCH;
        it.icon = GlyphIcon.of(glyph);
        it.nowHeaderGlyph = GlyphIcon.of(glyph);
        it.nowHeader = product;
        it.appIcon = apps.icon(WEARABLE_PKG);
        it.accent = -1;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        switch (what) {
            case "disconnect":
                it.subtitle = IslandText.get(ctx, "island_watch_disconnected");
                break;
            case "charge":
                it.subtitle = IslandText.get(ctx, full ? "island_charge_full" : "island_charging");
                break;
            default:
                it.subtitle = IslandText.get(ctx, "island_connected");
                break;
        }
        it.title = name != null ? name : product;
        if (level >= 0) {
            it.batteryPercent = level;
            it.batteryCharging = charging && !"disconnect".equals(what);
            it.nowRing = true;
            it.progress = level / 100f;
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = level + "%";
        } else {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = it.subtitle;
        }
        it.compactLabel = name != null ? name : product;
        return it;
    }

    private static String key(String address, String what) {
        return "event:watch:" + what + ":" + address;
    }

    private static boolean wearable(int type) {
        return type == TYPE_WATCH || type == TYPE_FIT || type == TYPE_RING;
    }

    private static int intOf(Object info, String getter) {
        Object v = Refl.call(info, getter);
        return v instanceof Integer ? (Integer) v : -1;
    }

    public void collect(List<IslandItem> out) {
        items.collect(out);
    }
}
