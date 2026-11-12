package dev.android.dynamicisland.island.source;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The car: a card when the phone connects to a car kit over Bluetooth (class car audio or hands-free), and when it
 * disconnects the time and place it was parked, with a button that opens the spot on the map.
 *
 * <p>The place is the last known location at the moment of disconnecting (System UI holds the location permission);
 * the street is looked up off the main thread and fills the card in when it arrives.
 */
public final class CarSource {
    private static final long CONNECT_MS = 4500, PARKED_MS = 9000;
    private static final int MAJOR_AUDIO_VIDEO = 0x0400;
    private static final int CAR_AUDIO = 0x0420, HANDSFREE = 0x0408;
    private static final String MAPS_PKG = "com.google.android.apps.maps";

    private final Context ctx;
    private final Handler main;
    private final TimedItems items;
    private final AppLook apps;
    /** Cars connected right now: their plain Bluetooth card is replaced by the car card. */
    private final Set<String> connected = new HashSet<>();

    public CarSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.items = new TimedItems(main, onChange);
        this.apps = new AppLook(ctx);
    }

    public void start() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            // sent by the Bluetooth app: only senders holding the permission, so apps cannot fake a car
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        onAcl(intent);
                    } catch (Throwable t) {
                        XLog.once("car bluetooth", t);
                    }
                }
            }, filter, "android.permission.BLUETOOTH_CONNECT", null, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("car receiver not registered", t);
        }
    }

    /** True for a car kit: Bluetooth class car audio, or hands-free (what car head units announce). */
    @SuppressLint("MissingPermission")
    static boolean isCar(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls == null || cls.getMajorDeviceClass() != MAJOR_AUDIO_VIDEO) return false;
            int dc = cls.getDeviceClass();
            return dc == CAR_AUDIO || dc == HANDSFREE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while the generic Bluetooth card of this address must give way to the car card. */
    public boolean covers(String address) {
        return ModuleSettings.carCard() && connected.contains(address);
    }

    private void onAcl(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (device == null || !isCar(device)) return;
        String address = device.getAddress();
        String name = nameOf(device);
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
            connected.add(address);
            items.remove(parkedKey());
            if (ModuleSettings.carCard()) items.show(connectCard(address, name), CONNECT_MS);
        } else {
            boolean was = connected.remove(address);
            items.remove("event:car:" + address);
            if (was && ModuleSettings.carCard()) parked(name);
        }
    }

    private IslandItem base(String key, String name) {
        IslandItem it = new IslandItem(key);
        it.kind = IslandItem.Kind.EVENT;
        it.eventLayout = IslandItem.EventLayout.NOW;
        it.icon = GlyphIcon.of(GlyphIcon.Kind.CAR);
        it.nowHeaderGlyph = GlyphIcon.of(GlyphIcon.Kind.CAR);
        it.nowHeader = name != null ? name : IslandText.get(ctx, "island_car");
        it.accent = -1;
        return it;
    }

    private IslandItem connectCard(String address, String name) {
        IslandItem it = base("event:car:" + address, name);
        it.title = IslandText.get(ctx, "island_car_connected");
        it.subtitle = name;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = name != null ? name : it.title;
        PendingIntent navigate = activity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=")).setPackage(MAPS_PKG), 1);
        if (navigate == null) navigate = activity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), 1);
        if (navigate != null) {
            IslandItem.Action action = new IslandItem.Action(IslandText.get(ctx, "island_car_navigate"), null, navigate, null, false);
            action.affirmative = true;
            it.actions.add(action);
            it.appIcon = apps.icon(MAPS_PKG);
        }
        return it;
    }

    private String parkedKey() {
        return "event:car:parked";
    }

    private void parked(String name) {
        String time = android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date());
        Location where = lastLocation();
        IslandItem it = parkedCard(name, time, where, null);
        items.show(it, PARKED_MS);
        if (where == null || !Geocoder.isPresent()) return;
        new Thread(() -> {
            String street = streetOf(where);
            if (street == null) return;
            main.post(() -> items.refresh(parkedCard(name, time, where, street)));
        }, "island-car-geocode").start();
    }

    private IslandItem parkedCard(String name, String time, Location where, String street) {
        IslandItem it = base(parkedKey(), name);
        it.title = IslandText.get(ctx, "island_car_parked", time);
        it.subtitle = street != null ? street : name;
        it.nowValue = time;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = time;
        if (where != null) {
            String ll = String.format(Locale.ROOT, "%.6f,%.6f", where.getLatitude(), where.getLongitude());
            PendingIntent map = activity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll)), 2);
            if (map != null) {
                IslandItem.Action action = new IslandItem.Action(IslandText.get(ctx, "island_car_map"), null, map, null, false);
                action.affirmative = true;
                it.actions.add(action);
                it.contentIntent = map;
                it.appIcon = apps.icon(MAPS_PKG);
            }
        }
        return it;
    }

    @SuppressLint("MissingPermission")
    private Location lastLocation() {
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) return null;
            Location best = null;
            for (String provider : new String[] {LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l;
                try {
                    l = lm.getLastKnownLocation(provider);
                } catch (Throwable t) {
                    continue;
                }
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            // a fix older than ten minutes is not where the car is
            return best != null && System.currentTimeMillis() - best.getTime() < 10 * 60_000L ? best : null;
        } catch (Throwable t) {
            XLog.once("car location", t);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String streetOf(Location where) {
        try {
            List<Address> found = new Geocoder(ctx).getFromLocation(where.getLatitude(), where.getLongitude(), 1);
            if (found == null || found.isEmpty()) return null;
            Address a = found.get(0);
            String street = a.getThoroughfare();
            if (street == null) return a.getAddressLine(0);
            return a.getSubThoroughfare() != null ? street + " " + a.getSubThoroughfare() : street;
        } catch (Throwable t) {
            return null;
        }
    }

    private PendingIntent activity(Intent intent, int code) {
        try {
            if (intent.resolveActivity(ctx.getPackageManager()) == null) return null;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0x6CA0 + code, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static String nameOf(BluetoothDevice device) {
        try {
            String alias = device.getAlias();
            return alias != null ? alias : device.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    public void collect(List<IslandItem> out) {
        items.collect(out);
    }
}
