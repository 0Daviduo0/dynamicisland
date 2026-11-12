package dev.android.dynamicisland.island.source;

import dev.android.dynamicisland.island.graphics.GlyphIcon;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Galaxy Buds state straight from the Galaxy Wearable earbud plugins (Buds3 Pro: {@code com.samsung.accessory.paranmgr}).
 *
 * <p>Each plugin exports {@code BudsControllerMessengerService} (action {@code ACTION_MESSENGER}), guarded by a
 * permission System UI already holds. A client binds it, sends {@code what=1000} with its own {@link Messenger}, and
 * then receives {@code what=2000} messages whose bundle string is JSON such as
 * {@code {"msg_id":"changed_device","extra":{"address":..,"battery_l":..,"battery_r":..,"battery_c":..,
 * "color":"silver","noise_controls":"anc"|"ambient"|"adaptive"|"off",..}}}.
 *
 * <p>Two things come out of it: the connect card ({@link IslandItem.EventLayout#BUDS}, product image in the earbuds'
 * real colour plus a battery ring), and a short status card whenever the noise control mode changes.
 */
public final class BudsSource {
    private static final String ACTION_MESSENGER = "com.samsung.accessory.hearablemgr.ACTION_MESSENGER";
    private static final int MSG_REGISTER = 1000;
    private static final int MSG_JSON = 2000;
    private static final long CONNECT_MS = 5000;
    private static final long NOISE_MS = 2200;
    /** A noise mode reported this soon after the first state of a device is its starting mode, not a change. */
    private static final long SETTLE_MS = 2500;
    private static final int ACCENT = 0xFF3E91FF;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onChange.run();
        }
    };
    private final Messenger inbox;
    private final Map<String, ServiceConnection> bound = new HashMap<>();
    private final Map<String, Device> devices = new HashMap<>();
    private final Map<String, IslandItem> events = new HashMap<>();
    /** When we registered with a plugin: the connection state it replays right then is not a new connection. */
    private long registeredAt;
    /** A real connection happened: the next device state is announced with the connect card until this time. */
    private long connectPendingUntil;
    /**
     * Earbuds that just connected, by address, until when their Buds card is awaited. Meanwhile the generic
     * Bluetooth card (and its battery refresh) stays hidden, so connecting shows one island, not three in a row.
     */
    private final Map<String, Long> awaited = new HashMap<>();
    private final Runnable awaitOver = new Runnable() {
        @Override public void run() { onChange.run(); }
    };
    /** Longest wait for a plugin's first state before the generic Bluetooth card is let through. */
    private static final long AWAIT_MS = 3000;

    /** Last known state of one pair of earbuds. */
    private static final class Device {
        String pkg, color, noise, name;
        int left = -1, right = -1, caseLevel = -1;
        long connectedAt;
        Bitmap image;
    }

    public BudsSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
        inbox = new Messenger(new Handler(main.getLooper(), this::onMessage));
    }

    /** Binds every installed earbud plugin; a plugin that is not running connects later, when it starts. */
    public void start() {
        try {
            List<ResolveInfo> services = ctx.getPackageManager().queryIntentServices(new Intent(ACTION_MESSENGER), 0);
            for (ResolveInfo info : services) {
                if (info.serviceInfo != null) bind(info.serviceInfo.packageName, info.serviceInfo.name);
            }
        } catch (Throwable t) {
            XLog.e("buds: plugin lookup failed", t);
        }
    }

    private void bind(String pkg, String cls) {
        if (bound.containsKey(pkg)) return;
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    Message hello = Message.obtain(null, MSG_REGISTER, inbox);
                    registeredAt = SystemClock.uptimeMillis();
                    new Messenger(service).send(hello);
                    XLog.i("buds: registered with " + name.getPackageName());
                } catch (Throwable t) {
                    XLog.e("buds: register failed", t);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        try {
            Intent intent = new Intent(ACTION_MESSENGER).setClassName(pkg, cls);
            // no BIND_AUTO_CREATE: never start a plugin just for the island, attach when Galaxy Wearable runs it
            if (ctx.bindService(intent, connection, 0)) bound.put(pkg, connection);
        } catch (Throwable t) {
            XLog.e("buds: bind " + pkg + " failed", t);
        }
    }

    private boolean onMessage(Message msg) {
        if (msg.what != MSG_JSON) return true;
        try {
            // the plugin sends Message.obtain(null, 2000, bundle): the bundle is the message's obj, not its data
            Bundle data = msg.obj instanceof Bundle ? (Bundle) msg.obj : msg.peekData();
            String json = data == null ? null : data.getString("string");
            if (json != null) {
                XLog.d("buds: " + json);
                onJson(new JSONObject(json));
            }
        } catch (Throwable t) {
            XLog.e("buds: bad message", t);
        }
        return true;
    }

    void onJson(JSONObject root) {
        String id = root.optString("msg_id");
        if ("changed_connection_state".equals(id)) {
            JSONObject extra = root.optJSONObject("extra");
            long now = SystemClock.uptimeMillis();
            boolean connected = extra != null && "connected".equals(extra.optString("connection_state"));
            if (connected && now - registeredAt > SETTLE_MS) connectPendingUntil = now + CONNECT_MS;
            if (!connected) connectPendingUntil = 0;
            return;
        }
        if (!"changed_device".equals(id)) return;
        JSONObject extra = root.optJSONObject("extra");
        if (extra == null) return;
        String address = extra.optString("address", null);
        if (address == null) return;
        long now = SystemClock.uptimeMillis();
        Device d = devices.get(address);
        if (d == null) {
            d = new Device();
            devices.put(address, d);
        }
        d.pkg = extra.optString("package_name", d.pkg != null ? d.pkg : "com.samsung.accessory.paranmgr");
        String color = extra.optString("color", null);
        if (color != null && !color.equals(d.color)) {
            d.color = color;
            d.image = null;
        }
        if (d.name == null) d.name = nameOf(address);
        d.left = extra.optInt("battery_l", d.left);
        d.right = extra.optInt("battery_r", d.right);
        d.caseLevel = extra.optInt("battery_c", d.caseLevel);
        String noise = extra.optString("noise_controls", null);
        String previousNoise = d.noise;
        if (noise != null && !noise.isEmpty()) d.noise = noise;

        if (!ModuleSettings.eventBluetooth()) return;
        if (now < connectPendingUntil && !events.containsKey("event:buds:" + address)) {
            connectPendingUntil = 0;
            d.connectedAt = now;
            awaited.remove(address);
            show(connectItem(address, d), CONNECT_MS);
        } else if (events.containsKey("event:buds:" + address)) {
            refresh(connectItem(address, d));
        }
        // right after connecting, the earbuds report their saved mode: that is not a change
        boolean settled = now - d.connectedAt > SETTLE_MS;
        if (settled && previousNoise != null && d.noise != null && !d.noise.equals(previousNoise)) {
            events.remove("event:buds:" + address); // the mode card replaces the connect card
            show(noiseItem(address, d), NOISE_MS);
        }
    }

    /** Average earbud level; the case is not counted. */
    private static int budsLevel(Device d) {
        if (d.left > 0 && d.right > 0) return (d.left + d.right) / 2;
        return Math.max(d.left, d.right);
    }

    private IslandItem connectItem(String address, Device d) {
        IslandItem it = base("event:buds:" + address);
        int level = budsLevel(d);
        Bitmap image = image(d);
        it.eventLayout = image != null ? IslandItem.EventLayout.BUDS : IslandItem.EventLayout.DEVICE;
        it.budsImage = image;
        it.title = IslandText.get(ctx, "island_connected");
        it.batteryPercent = level;
        it.accent = ACCENT;
        details(it, d);
        if (image != null) {
            it.icon = new BitmapDrawable(ctx.getResources(), image);
            it.iconIsImage = true;
        }
        if (level >= 0) {
            it.progress = level / 100f;
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = level + "%";
        }
        return it;
    }

    private IslandItem noiseItem(String address, Device d) {
        IslandItem it = base("event:buds-noise:" + address);
        String key;
        String icon;
        switch (d.noise) {
            case "anc": key = "island_buds_anc"; icon = "ic_buds3_anc_on"; break;
            case "ambient": key = "island_buds_ambient"; icon = "ic_buds3_ambient_sound"; break;
            case "adaptive": key = "island_buds_adaptive"; icon = "ic_buds3_adaptive"; break;
            default: key = "island_buds_off"; icon = "ic_buds3_anc_off"; break;
        }
        // headphones glyph (replaced below by the product image when there is one), mode glyph on the right
        CharSequence label = IslandText.get(ctx, key);
        it.title = "";
        it.icon = GlyphIcon.of(GlyphIcon.Kind.HEADPHONES);
        it.accent = "off".equals(d.noise) ? 0xFF8E8E93 : ACCENT;
        it.trailingIcon = pluginDrawable(d.pkg, icon);
        it.trailing = it.trailingIcon != null ? IslandItem.Trailing.ICON : IslandItem.Trailing.TEXT;
        if (it.trailingIcon == null) it.trailingText = label;
        // expanded, the mode card is the Buds card with the mode first
        Bitmap image = image(d);
        if (image != null) {
            it.eventLayout = IslandItem.EventLayout.BUDS;
            it.budsImage = image;
            // the earbuds' own product image on the left, like the connect card, not a generic headphones glyph
            it.icon = new BitmapDrawable(ctx.getResources(), image);
            it.iconIsImage = true;
        }
        it.batteryPercent = budsLevel(d);
        details(it, d);
        it.budsModeFirst = true;
        return it;
    }

    /** Name, per-side battery and noise mode for the expanded Buds card. */
    private void details(IslandItem it, Device d) {
        it.budsName = d.name;
        it.budsLeft = d.left;
        it.budsRight = d.right;
        it.budsCase = d.caseLevel;
        if (d.noise != null) {
            String key, icon;
            switch (d.noise) {
                case "anc": key = "island_buds_anc"; icon = "ic_buds3_anc_on"; break;
                case "ambient": key = "island_buds_ambient"; icon = "ic_buds3_ambient_sound"; break;
                case "adaptive": key = "island_buds_adaptive"; icon = "ic_buds3_adaptive"; break;
                default: key = "island_buds_off"; icon = "ic_buds3_anc_off"; break;
            }
            it.budsMode = IslandText.get(ctx, key);
            it.budsModeIcon = pluginDrawable(d.pkg, icon);
        }
    }

    /** The user's name for the earbuds (their Bluetooth alias), or null. */
    @android.annotation.SuppressLint("MissingPermission")
    private String nameOf(String address) {
        try {
            android.bluetooth.BluetoothManager bm = ctx.getSystemService(android.bluetooth.BluetoothManager.class);
            if (bm == null || bm.getAdapter() == null) return null;
            android.bluetooth.BluetoothDevice dev = bm.getAdapter().getRemoteDevice(address);
            String n = dev.getAlias();
            return n != null ? n : dev.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Product photo for the earbuds' colour from their plugin ({@code home_image_<color>}), cached per device. */
    private Bitmap image(Device d) {
        if (d.image != null) return d.image;
        Drawable dr = null;
        if (d.color != null) dr = pluginDrawable(d.pkg, "home_image_" + d.color.toLowerCase(java.util.Locale.ROOT));
        if (dr == null) dr = pluginDrawable(d.pkg, "home_image_default");
        if (dr == null) return null;
        if (dr instanceof BitmapDrawable && ((BitmapDrawable) dr).getBitmap() != null) {
            d.image = ((BitmapDrawable) dr).getBitmap();
        } else {
            int w = Math.max(1, dr.getIntrinsicWidth()), h = Math.max(1, dr.getIntrinsicHeight());
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            dr.setBounds(0, 0, w, h);
            dr.draw(new Canvas(b));
            d.image = b;
        }
        return d.image;
    }

    private Drawable pluginDrawable(String pkg, String name) {
        try {
            Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
            int id = res.getIdentifier(name, "drawable", pkg);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private IslandItem base(String key) {
        IslandItem it = new IslandItem(key);
        it.kind = IslandItem.Kind.EVENT;
        it.eventLayout = IslandItem.EventLayout.STATUS;
        it.iconIsImage = false;
        it.pillColor = 0;
        return it;
    }

    private void show(IslandItem item, long ttlMs) {
        item.expiresAtUptime = SystemClock.uptimeMillis() + ttlMs;
        item.postTime = SystemClock.uptimeMillis();
        events.remove(item.key);
        events.put(item.key, item);
        onChange.run();
    }

    private void refresh(IslandItem fresh) {
        IslandItem old = events.get(fresh.key);
        fresh.expiresAtUptime = old.expiresAtUptime;
        fresh.postTime = old.postTime;
        events.put(fresh.key, fresh);
        onChange.run();
    }

    /** True while a Buds card covers the plain Bluetooth "connected" event for the same earbuds. */
    public boolean showingConnect() {
        for (String key : events.keySet()) if (key.startsWith("event:buds")) return true;
        return false;
    }

    /** True while the generic Bluetooth card of {@code address} must stay hidden: its Buds card is on its way. */
    public boolean awaiting(String address) {
        Long until = awaited.get(address);
        if (until == null) return false;
        if (until > SystemClock.uptimeMillis()) return true;
        awaited.remove(address);
        return false;
    }

    /**
     * A Bluetooth audio device connected. For earbuds seen before, the Buds card shows right away with the last
     * known state (the plugin's fresh state follows within a second and refreshes it).
     */
    public void onAudioConnected(String address, String name) {
        Device d = devices.get(address);
        if (d == null) {
            // not seen yet (first connection since System UI started): its plugin state announces it shortly
            connectPendingUntil = SystemClock.uptimeMillis() + CONNECT_MS;
            if (!bound.isEmpty() && name != null && name.toLowerCase(java.util.Locale.ROOT).contains("buds")) {
                awaited.put(address, SystemClock.uptimeMillis() + AWAIT_MS);
                main.removeCallbacks(awaitOver);
                main.postDelayed(awaitOver, AWAIT_MS + 20);
            }
            return;
        }
        if (!ModuleSettings.eventBluetooth()) return;
        d.connectedAt = SystemClock.uptimeMillis();
        connectPendingUntil = 0;
        show(connectItem(address, d), CONNECT_MS);
    }

    public void collect(List<IslandItem> out) {
        if (events.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        long next = Long.MAX_VALUE;
        for (Iterator<IslandItem> it = events.values().iterator(); it.hasNext(); ) {
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
