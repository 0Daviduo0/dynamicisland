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
 * <p>One island per pair of earbuds comes out of it ({@link IslandItem.EventLayout#BUDS}, product image in the
 * earbuds' real colour): it announces the connection and every noise mode change, and stays next to the player
 * while one shows. Its card switches noise mode and level by sending {@code set_device} back to the plugin.
 */
public final class BudsSource {
    private static final String ACTION_MESSENGER = "com.samsung.accessory.hearablemgr.ACTION_MESSENGER";
    private static final int MSG_REGISTER = 1000;
    private static final int MSG_JSON = 2000;
    private static final long CONNECT_MS = 5000;
    private static final long NOISE_MS = 2200;
    /** A noise mode reported this soon after the first state of a device is its starting mode, not a change. */
    private static final long SETTLE_MS = 2500;
    /** How long after the island's own command the plugin's reports of the old state are ignored. */
    private static final long ECHO_MS = 1500;
    /** Shortest gap between two commands: earbuds drop one that follows another too closely. */
    private static final long COMMAND_GAP_MS = 400;
    /** After the last command, when the island reads the earbuds' real state back. */
    private static final long VERIFY_MS = 1000;
    /** Times a command the earbuds did not take is sent again before the card shows what they really do. */
    private static final int MAX_RETRIES = 1;
    private static final int OFF_GREY = 0xFF8E8E93;
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
    /** Each plugin's service, to send it commands; by package. */
    private final Map<String, Messenger> services = new HashMap<>();
    private int nextRequest = 1;
    /** Whether a player is showing; while it is and the earbuds are connected, their island stays. */
    private java.util.function.BooleanSupplier hasPlayer = () -> false;
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
    private final class Device {
        String pkg, color, noise, name;
        int left = -1, right = -1, caseLevel = -1;
        int ancLevel = -1, ambientLevel = -1, maxAnc = 4, maxAmbient = 4;
        long connectedAt;
        boolean connected;
        Bitmap image;
        /** The island announces a connection (or a mode change, see {@link #announcedMode}) until this time. */
        long announceUntil;
        long announcedAt;
        boolean announcedMode;
        /** What the island itself asked for and when: the plugin's echoes of the old value meanwhile are ignored. */
        String requestedNoise;
        long requestedAt;
        String address;
        /** What the island last showed of this pair, see {@link #onJson}. */
        String shown;
        /** Commands not sent yet (latest wins); -1 / null = none. */
        String pendingNoise;
        int pendingAnc = -1, pendingAmbient = -1;
        /** What the island wants the earbuds to be in, until the read-back confirms it. */
        String wantNoise;
        int wantAnc = -1, wantAmbient = -1;
        long lastSentAt;
        int retries;
        final Runnable flush = () -> flushCommands(this);
        final Runnable verify = () -> verify(this);
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
                    Messenger plugin = new Messenger(service);
                    plugin.send(hello);
                    services.put(name.getPackageName(), plugin);
                    // the full state (noise levels included) of earbuds already connected
                    Device probe = new Device();
                    probe.pkg = name.getPackageName();
                    send(probe, "get_device", new JSONObject());
                    XLog.i("buds: registered with " + name.getPackageName());
                } catch (Throwable t) {
                    XLog.e("buds: register failed", t);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                services.remove(name.getPackageName());
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
            if (!connected) {
                connectPendingUntil = 0;
                for (Device d : devices.values()) {
                    d.connected = false;
                    d.announceUntil = 0;
                }
                onChange.run();
            }
            return;
        }
        if (!"changed_device".equals(id) && !"get_device_rsp".equals(id)) return;
        JSONObject extra = root.optJSONObject("extra");
        if (extra == null) return;
        String address = extra.optString("address", null);
        // a state asked for while nothing is connected has no usable address
        if (address == null || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return;
        long now = SystemClock.uptimeMillis();
        Device d = devices.get(address);
        if (d == null) {
            d = new Device();
            devices.put(address, d);
        }
        d.address = address;
        d.connected = true;
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
        // the read-back after a command is the truth; other reports meanwhile may still carry the old state
        boolean readBack = "get_device_rsp".equals(id);
        if (readBack && reconcile(d, extra)) return;
        if (!readBack && d.wantNoise != null && d.pendingNoise == null
                && d.wantNoise.equals(extra.optString("noise_controls", null))) {
            // the earbuds announce the mode asked for: confirmed, no need to wait for the read-back
            d.wantNoise = null;
            if (d.wantAnc < 0 && d.wantAmbient < 0) main.removeCallbacks(d.verify);
        }
        boolean echo = !readBack && (now - d.requestedAt < ECHO_MS || d.wantNoise != null);
        if (!echo) {
            d.ancLevel = extra.optInt("anc_level", d.ancLevel);
            d.ambientLevel = extra.optInt("ambient_sound_level", d.ambientLevel);
        }
        d.maxAnc = Math.max(1, extra.optInt("max_anc_level", d.maxAnc));
        d.maxAmbient = Math.max(1, extra.optInt("max_ambient_sound_level", d.maxAmbient));
        String noise = extra.optString("noise_controls", null);
        String previousNoise = d.noise;
        if (noise != null && !noise.isEmpty() && !(echo && !noise.equals(d.requestedNoise))) d.noise = noise;

        if (!ModuleSettings.eventBluetooth()) return;
        if (now < connectPendingUntil && !announcingConnect(d, now)) {
            connectPendingUntil = 0;
            d.connectedAt = now;
            awaited.remove(address);
            announce(d, false, CONNECT_MS);
            return;
        }
        // right after connecting, the earbuds report their saved mode: that is not a change; nor is the island's own
        boolean settled = now - d.connectedAt > SETTLE_MS;
        boolean own = d.requestedNoise != null && d.requestedNoise.equals(d.noise) && now - d.requestedAt < ECHO_MS * 2;
        if (settled && !own && previousNoise != null && d.noise != null && !d.noise.equals(previousNoise)) {
            announce(d, true, NOISE_MS);
            return;
        }
        // the plugin repeats its whole state often (battery ticks): rebuild the island only when what it shows moved
        String shown = d.left + "/" + d.right + "/" + d.caseLevel + "/" + d.noise + "/" + d.ancLevel + "/"
                + d.ambientLevel + "/" + d.color + "/" + (d.wantNoise != null);
        if (shown.equals(d.shown)) return;
        d.shown = shown;
        onChange.run();
    }

    private static boolean announcingConnect(Device d, long now) {
        return now < d.announceUntil && !d.announcedMode;
    }

    private void announce(Device d, boolean mode, long ms) {
        long now = SystemClock.uptimeMillis();
        // a mode change during the connect card keeps the connect card's time; it only swaps what leads
        if (!(mode && announcingConnect(d, now))) d.announcedMode = mode;
        d.announceUntil = Math.max(d.announceUntil, now + ms);
        d.announcedAt = now;
        onChange.run();
    }

    /** Where a player shows, the island keeps the earbuds' card (with their noise controls) next to it. */
    public void setPlayerCheck(java.util.function.BooleanSupplier hasPlayer) {
        this.hasPlayer = hasPlayer;
    }

    /** Asks the earbuds to switch noise mode; the card shows the new mode at once, and the read-back confirms it. */
    private void setNoise(String address, String mode) {
        Device d = devices.get(address);
        if (d == null || d.wantNoise != null || mode.equals(d.noise) || d.pkg == null || !services.containsKey(d.pkg)) return;
        d.noise = mode;
        d.pendingNoise = mode;
        d.wantNoise = mode;
        d.retries = 0;
        mark(d);
        schedule(d);
        onChange.run();
    }

    /** Asks for the level of the current mode, {@code fraction} 0..1 of the earbuds' range. */
    private void setLevel(String address, float fraction) {
        Device d = devices.get(address);
        if (d == null || d.noise == null) return;
        boolean anc = "anc".equals(d.noise);
        if (!anc && !"ambient".equals(d.noise)) return;
        int max = anc ? d.maxAnc : d.maxAmbient;
        int level = Math.max(0, Math.min(max, Math.round(fraction * max)));
        if (level == (anc ? d.ancLevel : d.ambientLevel)) return;
        if (anc) {
            d.ancLevel = level;
            d.pendingAnc = level;
            d.wantAnc = level;
        } else {
            d.ambientLevel = level;
            d.pendingAmbient = level;
            d.wantAmbient = level;
        }
        d.retries = 0;
        mark(d);
        schedule(d);
    }

    private static void mark(Device d) {
        d.requestedNoise = d.noise;
        d.requestedAt = SystemClock.uptimeMillis();
    }

    /** Sends the pending commands now, or as soon as the gap since the last one allows. */
    private void schedule(Device d) {
        main.removeCallbacks(d.flush);
        main.removeCallbacks(d.verify);
        long wait = d.lastSentAt + COMMAND_GAP_MS - SystemClock.uptimeMillis();
        if (wait > 0) main.postDelayed(d.flush, wait);
        else flushCommands(d);
    }

    private void flushCommands(Device d) {
        JSONObject extra = new JSONObject();
        try {
            // the mode first: a level only applies to the mode it belongs to
            if (d.pendingNoise != null) extra.put("noise_controls", d.pendingNoise);
            else if (d.pendingAnc >= 0) extra.put("anc_level", d.pendingAnc);
            else if (d.pendingAmbient >= 0) extra.put("ambient_sound_level", d.pendingAmbient);
        } catch (Throwable ignored) {
        }
        if (extra.length() > 0) {
            if (extra.has("noise_controls")) d.pendingNoise = null;
            else if (extra.has("anc_level")) d.pendingAnc = -1;
            else d.pendingAmbient = -1;
            if (!send(d, "set_device", extra)) {
                abandon(d);
                return;
            }
            d.lastSentAt = SystemClock.uptimeMillis();
            mark(d);
        }
        if (d.pendingNoise != null || d.pendingAnc >= 0 || d.pendingAmbient >= 0) {
            main.postDelayed(d.flush, COMMAND_GAP_MS);
        } else {
            main.postDelayed(d.verify, VERIFY_MS);
        }
    }

    /** Reads the earbuds' real state back; {@link #reconcile} compares it with what was asked. */
    private void verify(Device d) {
        if (!send(d, "get_device", new JSONObject())) abandon(d);
    }

    /**
     * Compares the read-back with what the island asked for: anything the earbuds did not take is sent again (once),
     * after which the card shows what they really do.
     *
     * @return true when a command was sent again, so this state is not shown
     */
    private boolean reconcile(Device d, JSONObject extra) {
        if (d.wantNoise == null && d.wantAnc < 0 && d.wantAmbient < 0) return false;
        String noise = extra.optString("noise_controls", null);
        int anc = extra.optInt("anc_level", -1), ambient = extra.optInt("ambient_sound_level", -1);
        boolean noiseOk = d.wantNoise == null || d.wantNoise.equals(noise);
        boolean ancOk = d.wantAnc < 0 || anc < 0 || d.wantAnc == anc;
        boolean ambientOk = d.wantAmbient < 0 || ambient < 0 || d.wantAmbient == ambient;
        if (!(noiseOk && ancOk && ambientOk) && d.retries < MAX_RETRIES) {
            d.retries++;
            XLog.i("buds: not taken (" + noise + "), sending again");
            if (!noiseOk) d.pendingNoise = d.wantNoise;
            if (!ancOk) d.pendingAnc = d.wantAnc;
            if (!ambientOk) d.pendingAmbient = d.wantAmbient;
            mark(d);
            schedule(d);
            return true;
        }
        // confirmed, or given up: from here the earbuds' own state is shown
        d.wantNoise = null;
        d.wantAnc = -1;
        d.wantAmbient = -1;
        d.requestedAt = 0;
        return false;
    }

    /** The plugin is gone: nothing more to send, show what was last reported. */
    private void abandon(Device d) {
        main.removeCallbacks(d.flush);
        main.removeCallbacks(d.verify);
        d.pendingNoise = null;
        d.pendingAnc = d.pendingAmbient = -1;
        d.wantNoise = null;
        d.wantAnc = d.wantAmbient = -1;
        d.requestedAt = 0;
        if (d.address != null && d.pkg != null && services.containsKey(d.pkg)) {
            Device probe = new Device();
            probe.pkg = d.pkg;
            send(probe, "get_device", new JSONObject());
        }
        onChange.run();
    }

    private boolean send(Device d, String msgId, JSONObject extra) {
        Messenger plugin = d.pkg == null ? null : services.get(d.pkg);
        if (plugin == null) return false;
        try {
            JSONObject json = new JSONObject();
            json.put("msg_id", msgId);
            json.put("req_id", nextRequest++);
            json.put("extra", extra);
            Bundle bundle = new Bundle();
            bundle.putString("string", json.toString());
            Message m = Message.obtain(null, MSG_JSON);
            m.obj = bundle;
            m.replyTo = inbox;
            plugin.send(m);
            return true;
        } catch (Throwable t) {
            XLog.e("buds: " + msgId + " failed", t);
            return false;
        }
    }

    /** Average earbud level; the case is not counted. */
    private static int budsLevel(Device d) {
        if (d.left > 0 && d.right > 0) return (d.left + d.right) / 2;
        return Math.max(d.left, d.right);
    }

    /**
     * The one island of a pair of earbuds. Compact: their product image, and on the right the battery while it
     * announces the connection, else the noise mode's glyph (so a mode change just swaps that glyph). Expanded: the
     * Buds card with the noise controls. It leads while it announces something, and otherwise sits behind the player.
     */
    private IslandItem item(String address, Device d, long now, boolean announcing) {
        IslandItem it = new IslandItem("event:buds:" + address);
        it.kind = announcing ? IslandItem.Kind.EVENT : IslandItem.Kind.CHARGING;
        it.pillColor = 0;
        it.postTime = d.announcedAt;
        it.expiresAtUptime = announcing && !persistent(d) ? d.announceUntil : 0;
        int level = budsLevel(d);
        Bitmap image = image(d);
        it.eventLayout = image != null ? IslandItem.EventLayout.BUDS : IslandItem.EventLayout.DEVICE;
        it.budsImage = image;
        it.title = IslandText.get(ctx, "island_connected");
        it.batteryPercent = level;
        it.accent = ACCENT;
        if (image != null) {
            it.icon = new BitmapDrawable(ctx.getResources(), image);
            it.iconIsImage = true;
        } else {
            it.icon = GlyphIcon.of(GlyphIcon.Kind.HEADPHONES);
            it.iconIsImage = false;
        }
        details(it, address, d);
        boolean connectCard = announcing && !d.announcedMode;
        it.budsModeFirst = announcing && d.announcedMode;
        if (!connectCard && it.budsModeIcon != null) {
            it.trailing = IslandItem.Trailing.ICON;
            it.trailingIcon = it.budsModeIcon;
            if ("off".equals(d.noise)) it.accent = OFF_GREY;
        } else if (level >= 0) {
            it.progress = level / 100f;
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = level + "%";
        }
        return it;
    }

    private static String modeKey(String mode) {
        switch (mode) {
            case "anc": return "island_buds_anc";
            case "ambient": return "island_buds_ambient";
            case "adaptive": return "island_buds_adaptive";
            default: return "island_buds_off";
        }
    }

    private static String modeIcon(String mode) {
        switch (mode) {
            case "anc": return "ic_buds3_anc_on";
            case "ambient": return "ic_buds3_ambient_sound";
            case "adaptive": return "ic_buds3_adaptive";
            default: return "ic_buds3_anc_off";
        }
    }

    /** Name, per-side battery, noise mode and, when the plugin takes commands, the noise controls. */
    private void details(IslandItem it, String address, Device d) {
        it.budsName = d.name;
        it.budsLeft = d.left;
        it.budsRight = d.right;
        it.budsCase = d.caseLevel;
        if (d.noise == null) return;
        it.budsNoise = d.noise;
        it.budsMode = IslandText.get(ctx, modeKey(d.noise));
        it.budsModeIcon = pluginDrawable(d.pkg, modeIcon(d.noise));
        if (d.pkg == null || !services.containsKey(d.pkg)) return;
        int n = IslandItem.BUDS_MODES.length;
        it.budsModeIcons = new Drawable[n];
        it.budsModeLabels = new CharSequence[n];
        for (int i = 0; i < n; i++) {
            String mode = IslandItem.BUDS_MODES[i];
            it.budsModeIcons[i] = pluginDrawable(d.pkg, modeIcon(mode));
            it.budsModeLabels[i] = IslandText.get(ctx, modeKey(mode));
        }
        it.budsSetNoise = mode -> setNoise(address, mode);
        it.budsBusy = d.wantNoise != null;
        boolean anc = "anc".equals(d.noise);
        int level = anc ? d.ancLevel : "ambient".equals(d.noise) ? d.ambientLevel : -1;
        if (level >= 0) {
            int max = anc ? d.maxAnc : d.maxAmbient;
            it.sliderSteps = max + 1;
            it.sliderValue = level / (float) max;
            it.sliderChange = v -> setLevel(address, v);
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

    /** Plugin drawables by package and name (a missing one too), so rebuilding the island reads no resources. */
    private final Map<String, Drawable> drawables = new HashMap<>();
    private static final Drawable MISSING = new android.graphics.drawable.ColorDrawable(0);

    private Drawable pluginDrawable(String pkg, String name) {
        String key = pkg + "/" + name;
        Drawable cached = drawables.get(key);
        if (cached == null) {
            cached = loadDrawable(pkg, name);
            drawables.put(key, cached == null ? MISSING : cached);
        }
        return cached == MISSING ? null : cached;
    }

    private Drawable loadDrawable(String pkg, String name) {
        try {
            Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
            int id = res.getIdentifier(name, "drawable", pkg);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the earbuds' island stays without announcing anything: connected while a player shows. */
    private boolean persistent(Device d) {
        return d.connected && linkUp(d) && hasPlayer.getAsBoolean();
    }

    private static java.lang.reflect.Method isConnected;

    /** The Bluetooth link of the earbuds, when the system says; the plugin's state otherwise. */
    @android.annotation.SuppressLint("MissingPermission")
    private boolean linkUp(Device d) {
        String address = d.address;
        if (address == null) return d.connected;
        try {
            android.bluetooth.BluetoothManager bm = ctx.getSystemService(android.bluetooth.BluetoothManager.class);
            if (bm == null || bm.getAdapter() == null) return d.connected;
            if (isConnected == null) isConnected = android.bluetooth.BluetoothDevice.class.getMethod("isConnected");
            Object up = isConnected.invoke(bm.getAdapter().getRemoteDevice(address));
            return !(up instanceof Boolean) || (Boolean) up;
        } catch (Throwable t) {
            return d.connected;
        }
    }

    /** True while a Buds card announces a connection, covering the plain Bluetooth "connected" event. */
    public boolean showingConnect() {
        long now = SystemClock.uptimeMillis();
        for (Device d : devices.values()) if (announcingConnect(d, now)) return true;
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
        d.connected = true;
        d.connectedAt = SystemClock.uptimeMillis();
        connectPendingUntil = 0;
        announce(d, false, CONNECT_MS);
    }

    public void collect(List<IslandItem> out) {
        if (devices.isEmpty() || !ModuleSettings.eventBluetooth()) return;
        long now = SystemClock.uptimeMillis();
        long next = Long.MAX_VALUE;
        for (Map.Entry<String, Device> e : devices.entrySet()) {
            Device d = e.getValue();
            boolean announcing = now < d.announceUntil;
            if (!announcing && !persistent(d)) continue;
            out.add(item(e.getKey(), d, now, announcing));
            if (announcing) next = Math.min(next, d.announceUntil);
        }
        if (next != Long.MAX_VALUE) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, Math.max(16L, next - now + 20));
        }
    }
}
