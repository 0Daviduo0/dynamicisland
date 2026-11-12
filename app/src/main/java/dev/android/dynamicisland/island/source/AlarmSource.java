package dev.android.dynamicisland.island.source;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A ringing alarm as an island that opens by itself, like a ringing call: the time, the alarm's name, and Snooze /
 * Dismiss, which are the clock app's own notification actions. While the phone is in use the stock banner is
 * replaced; on the lock screen the clock app keeps its full-screen alarm.
 *
 * <p>Only the alert of an alarm that is ringing counts (category alarm with a full-screen intent): the "upcoming
 * alarm" and "snoozed" notifications have none, and timers have their own island.
 */
public final class AlarmSource {
    private static final Set<String> CLOCKS = new HashSet<>(Arrays.asList(
            "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"));
    private static final Pattern SNOOZE = Pattern.compile(
            "snooze|posticip|rimanda|ripeti|schlummer|répét|repetir|aplazar|adiar|отлож|再通知|スヌーズ|다시 울림|稍后|ertele",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Context ctx;
    private final Runnable onChange;
    private final AppLook apps;
    private volatile IslandItem current;

    public AlarmSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
        this.apps = new AppLook(ctx);
    }

    /** True for the alert of a ringing alarm. */
    public static boolean isRinging(StatusBarNotification sbn) {
        Notification n = sbn == null ? null : sbn.getNotification();
        if (n == null || !CLOCKS.contains(sbn.getPackageName()) || n.fullScreenIntent == null) return false;
        String channel = n.getChannelId() == null ? "" : n.getChannelId().toLowerCase(Locale.ROOT);
        if (channel.contains("timer") || channel.contains("stopwatch")) return false;
        return Notification.CATEGORY_ALARM.equals(n.category) || channel.contains("alarm") || channel.contains("alert");
    }

    /** True when the island shows this alarm, so the stock banner must not drop down too. */
    public boolean claims(StatusBarNotification sbn) {
        return ModuleSettings.alarmCard() && isRinging(sbn);
    }

    /** Called on System UI's main thread every time the notification list was rebuilt. */
    public void update(List<StatusBarNotification> snapshot) {
        IslandItem fresh = null;
        if (ModuleSettings.alarmCard()) {
            for (StatusBarNotification sbn : snapshot) {
                if (isRinging(sbn)) {
                    fresh = build(sbn);
                    break;
                }
            }
        }
        IslandItem before = current;
        boolean changed = before == null ? fresh != null : fresh == null || !before.key.equals(fresh.key)
                || !String.valueOf(before.title).equals(String.valueOf(fresh.title))
                || before.actions.size() != fresh.actions.size();
        if (!changed) return;
        current = fresh;
        onChange.run();
    }

    private IslandItem build(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        IslandItem it = new IslandItem("alarm:" + sbn.getKey());
        it.kind = IslandItem.Kind.ALARM;
        it.eventLayout = IslandItem.EventLayout.NOW;
        it.sourceKey = sbn.getKey();
        it.packageName = sbn.getPackageName();
        it.postTime = sbn.getPostTime();
        it.callRinging = true;
        it.icon = GlyphIcon.of(GlyphIcon.Kind.ALARM);
        it.nowHeaderGlyph = GlyphIcon.of(GlyphIcon.Kind.ALARM);
        it.appIcon = apps.icon(sbn.getPackageName());
        it.appName = apps.label(sbn.getPackageName());
        it.nowHeader = it.appName != null ? it.appName : IslandText.get(ctx, "island_alarm");
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        // the clock app writes the time in one line and the alarm's name (or "Alarm") in the other
        String time = timeOf(n.when > 0 && Math.abs(n.when - System.currentTimeMillis()) < 3600_000L ? n.when : System.currentTimeMillis());
        it.title = pick(title, time) != null ? pick(title, time) : IslandText.get(ctx, "island_alarm");
        CharSequence second = pick(text, time);
        it.subtitle = second != null && !second.toString().contentEquals(it.title) ? second : null;
        it.nowValue = time;
        it.accent = -1;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = time;
        it.compactLabel = it.title;
        it.contentIntent = n.fullScreenIntent != null ? n.fullScreenIntent : n.contentIntent;
        IslandItem.Action snooze = null, dismiss = null;
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || a.title == null) continue;
                boolean isSnooze = SNOOZE.matcher(a.title).find();
                IslandItem.Action action = new IslandItem.Action(a.title, null, a.actionIntent, null, false);
                if (isSnooze && snooze == null) snooze = action;
                else if (dismiss == null) dismiss = action;
            }
        }
        if (snooze != null) it.actions.add(snooze);
        if (dismiss != null) {
            dismiss.affirmative = true;
            it.actions.add(dismiss);
        }
        return it;
    }

    /** The text when it is not just the time already shown big. */
    private static CharSequence pick(CharSequence text, String time) {
        if (text == null || text.length() == 0) return null;
        String plain = text.toString().replace(' ', ' ').trim();
        return plain.equalsIgnoreCase(time.replace(' ', ' ').trim()) ? null : text;
    }

    private String timeOf(long wall) {
        return android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(wall));
    }

    public void collect(List<IslandItem> out) {
        IslandItem it = current;
        if (it != null && ModuleSettings.alarmCard()) out.add(it);
    }
}
