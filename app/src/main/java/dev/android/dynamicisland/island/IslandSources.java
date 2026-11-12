package dev.android.dynamicisland.island;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.source.CallSource;
import dev.android.dynamicisland.island.source.WhatsAppCallClock;
import dev.android.dynamicisland.island.source.CastSource;
import dev.android.dynamicisland.island.source.EventSource;
import dev.android.dynamicisland.island.source.ChargingSource;
import dev.android.dynamicisland.island.source.HeadsetSource;
import dev.android.dynamicisland.island.source.HotspotSource;
import dev.android.dynamicisland.island.source.UnlockSource;
import dev.android.dynamicisland.island.source.MediaSource;
import dev.android.dynamicisland.island.source.OaSource;
import dev.android.dynamicisland.island.source.PeekSource;
import dev.android.dynamicisland.island.source.PrivacySource;
import dev.android.dynamicisland.island.source.PromotedSource;
import dev.android.dynamicisland.island.source.ProgressSource;
import dev.android.dynamicisland.island.source.RecorderNotificationSource;
import dev.android.dynamicisland.island.source.RuleSource;
import dev.android.dynamicisland.island.source.TorchSource;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import dev.android.dynamicisland.xposed.hooks.HideDuplicated;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of data sources behind the island and the step that gathers their items into one list.
 * Sources are isolated from each other: one that throws only loses its own islands.
 */
final class IslandSources {
    /** What one gathering pass produced. */
    static final class Gathered {
        final List<IslandItem> items = new ArrayList<>();
        IslandItem glowSession;
        /** Keys of items a source left out because their app is in the foreground. */
        final Set<String> hiddenByForeground = new HashSet<>();
        /** Packages whose chips the launcher should animate into the island. */
        final Set<String> packages = new LinkedHashSet<>();
    }

    private static final long PREVIEW_RECHECK_MS = 2500;
    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    final OaSource oa;
    private final ForegroundApp foregroundApp;
    final MediaSource media;
    final CastSource cast;
    final EventSource events;
    final dev.android.dynamicisland.island.source.BudsSource buds;
    final ChargingSource charging;
    final HeadsetSource headset;
    final HotspotSource hotspot;
    final UnlockSource unlock;
    final TorchSource torch;
    final PrivacySource privacy;
    final ProgressSource progress;
    final PromotedSource promoted;
    final RecorderNotificationSource recorder;
    final RuleSource rules;
    final CallSource calls;
    final PeekSource peeks;
    final dev.android.dynamicisland.island.source.QuickShareSource quickShare;
    final dev.android.dynamicisland.island.source.AlarmSource alarm;
    final dev.android.dynamicisland.island.source.CalendarSource calendar;
    final dev.android.dynamicisland.island.source.WatchSource watch;
    final dev.android.dynamicisland.island.source.CarSource car;
    final dev.android.dynamicisland.island.source.WorkoutSource workout;

    IslandSources(Context ctx, ClassLoader classLoader, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
        WhatsAppCallClock callClock = new WhatsAppCallClock();
        oa = new OaSource(ctx, classLoader, callClock);
        foregroundApp = new ForegroundApp(ctx, oa::foregroundPackage);
        media = new MediaSource(ctx, main, onChange);
        media.setRecordingFilter(oa::isVoiceRecording);
        cast = new CastSource(ctx);
        events = new EventSource(ctx, main, onChange);
        buds = new dev.android.dynamicisland.island.source.BudsSource(ctx, main, onChange);
        events.onAudioConnected = buds::onAudioConnected;
        charging = new ChargingSource(ctx, main, onChange);
        headset = new HeadsetSource(ctx, main, onChange);
        hotspot = new HotspotSource(ctx, main, onChange);
        unlock = new UnlockSource(ctx, main, onChange);
        torch = new TorchSource(ctx, main, onChange);
        privacy = new PrivacySource(ctx, main, onChange);
        progress = new ProgressSource(ctx, main, onChange);
        promoted = new PromotedSource(ctx, main, onChange);
        recorder = new RecorderNotificationSource(ctx, onChange);
        recorder.setLifecycleSession(cast::recorderLifecycle);
        recorder.setOnShownChanged(() -> main.post(HideDuplicated::rebuild));
        promoted.setOnShownChanged(() -> main.post(HideDuplicated::rebuild));
        rules = new RuleSource(ctx, main, onChange);
        progress.setOnShownChanged(() -> main.post(HideDuplicated::rebuild));
        rules.setOnShownChanged(() -> main.post(HideDuplicated::rebuild));
        cast.onRecordersChanged = () -> main.post(HideDuplicated::rebuild);
        torch.onShownChanged = () -> main.post(HideDuplicated::rebuild);
        calls = new CallSource(ctx, main, onChange, callClock);
        peeks = new PeekSource(ctx, main, onChange);
        quickShare = new dev.android.dynamicisland.island.source.QuickShareSource(ctx, main, onChange);
        alarm = new dev.android.dynamicisland.island.source.AlarmSource(ctx, main, onChange);
        calendar = new dev.android.dynamicisland.island.source.CalendarSource(ctx, main, onChange);
        watch = new dev.android.dynamicisland.island.source.WatchSource(ctx, main, onChange);
        car = new dev.android.dynamicisland.island.source.CarSource(ctx, main, onChange);
        workout = new dev.android.dynamicisland.island.source.WorkoutSource(ctx, main, onChange);
    }

    /** Starts the sources that listen on their own; the event and privacy islands may fail without harm. */
    void start(Runnable onChange) {
        media.start();
        syncOptionalSources();
        try {
            events.start();
            buds.start();
            torch.start();
            privacy.start();
            quickShare.start();
        } catch (Throwable t) {
            XLog.e("event islands failed to start", t);
        }
        guarded("watch start", watch::start);
        guarded("car start", car::start);
        guarded("calendar start", calendar::start);
        oa.onChanged = onChange;
    }

    void syncOptionalSources() {
        guarded("charging settings", charging::syncSettings);
        guarded("headset settings", headset::syncSettings);
        guarded("hotspot settings", hotspot::syncSettings);
        guarded("calendar settings", calendar::syncSettings);
    }

    /** Cheap vendor-cache query for ordinary banner scans; gather resolves the actual task once per pass. */
    String foregroundPackage() {
        return oa.foregroundPackage();
    }

    Gathered gather() {
        Gathered out = new Gathered();
        List<IslandItem> items = out.items;
        ForegroundApp.State state = foregroundApp.read();
        final String foreground = state.packageName;
        try {
            oa.collect(items, out.hiddenByForeground, out.packages, foreground, state.confirmed);
        } catch (Throwable t) {
            XLog.once("collect live notifications", t);
        }
        if (!ModuleSettings.progressNotifications()) {
            items.removeIf(item -> item.transfer);
        }
        guarded("media", () -> media.collect(items, foreground, state.confirmed, out.hiddenByForeground, out.packages));
        if (ModuleSettings.ambientGlow()) guarded("music glow", () -> out.glowSession = media.glowSession());
        guarded("cast", () -> cast.collect(items));
        guarded("recorder notification", () -> recorder.collect(items));
        guarded("events", () -> events.collect(items));
        guarded("buds", () -> {
            buds.collect(items);
            // the Buds card replaces the generic Bluetooth "connected" card (classic and LE links alike)
            // ...and while it is on its way, so connecting shows one island rather than several in a row
            if (buds.showingConnect()) items.removeIf(item -> item.key.startsWith("event:bt:"));
            else items.removeIf(item -> item.key.startsWith("event:bt:") && buds.awaiting(item.key.substring("event:bt:".length())));
        });
        guarded("watch", () -> watch.collect(items));
        guarded("car", () -> {
            car.collect(items);
            // the car card replaces the plain Bluetooth "connected" card of the car kit
            items.removeIf(item -> item.key.startsWith("event:bt:") && car.covers(item.key.substring("event:bt:".length())));
        });
        guarded("charging info", () -> charging.collect(items));
        guarded("headset", () -> headset.collect(items));
        guarded("hotspot", () -> hotspot.collect(items));
        guarded("unlock notice", () -> unlock.collect(items));
        if (charging.isOffering()) items.removeIf(item -> "event:charging".equals(item.key));
        guarded("torch", () -> torch.collect(items));
        if (ModuleSettings.placementPreviewActive()) {
            items.add(placementSample());
            main.removeCallbacks(onChange);
            main.postDelayed(onChange, PREVIEW_RECHECK_MS); // so the sample goes away when the page stops asking for it
        }
        guarded("privacy", () -> privacy.collect(items, foreground));
        guarded("live notifications", () -> promoted.collect(items, foreground));
        guarded("progress", () -> progress.collect(items, foreground));
        guarded("rules", () -> rules.collect(items, foreground));
        guarded("calls", () -> calls.collect(items, foreground));
        guarded("quick share", () -> quickShare.collect(items));
        guarded("alarm", () -> {
            alarm.collect(items);
        });
        guarded("calendar", () -> calendar.collect(items));
        guarded("workout", () -> {
            String shown = workout.shownKey();
            // the workout card replaces whatever plain card the same Samsung Health notification made
            if (shown != null) items.removeIf(item -> shown.equals(item.sourceKey)
                    || dev.android.dynamicisland.island.source.WorkoutSource.HEALTH_PKG.equals(item.packageName));
            workout.collect(items);
        });
        guarded("peeks", () -> peeks.collect(items));
        if (torch.replacesStockNotification()) {
            // System UI's own "flashlight in use" notification would show next to the brightness card
            items.removeIf(TorchSource::isStockItem);
        }
        if (ModuleSettings.accentSystem()) ModuleSettings.refreshSystemAccent(ctx);
        int accent = ModuleSettings.accentOverride();
        for (IslandItem item : items) {
            item.customAccent = accent != 0 && !item.isMedia
                    ? dev.android.dynamicisland.island.graphics.Colors.readableOnBlack(accent) : 0;
        }
        SwitchGate.apply(items);
        ItemDedupe.apply(items, HideDuplicated::copyKeysOf);
        return out;
    }

    /** What the island shows while the position page of the settings app is open, so there is something to move. */
    private IslandItem placementSample() {
        IslandItem it = new IslandItem("preview:position");
        it.kind = IslandItem.Kind.EVENT;
        it.iconIsImage = false;
        it.title = IslandText.get(ctx, "island_island_position");
        it.subtitle = IslandText.get(ctx, "island_move_it_with_the_sliders");
        it.icon = GlyphIcon.of(GlyphIcon.Kind.HOTSPOT);
        it.accent = 0xFF3E91FF;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = ModuleSettings.previewSampleLong()
                ? IslandText.get(ctx, "island_preview_a_long_text_stretches_the_pill_this_far")
                : IslandText.get(ctx, "island_preview");
        it.postTime = SystemClock.uptimeMillis();
        return it;
    }

    private static void guarded(String source, Runnable collect) {
        try {
            collect.run();
        } catch (Throwable t) {
            XLog.once("collect " + source, t);
        }
    }
}
