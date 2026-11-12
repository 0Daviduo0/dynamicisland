package dev.android.dynamicisland.island.source;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Samsung Health workout in progress: the exercise, its running time, and up to three live figures (heart rate,
 * distance, calories, pace, speed or steps) read from the texts of its ongoing notification, plus the workout's own
 * buttons (pause, stop). It replaces the plain card the same notification would otherwise make.
 */
public final class WorkoutSource {
    public static final String HEALTH_PKG = "com.sec.android.app.shealth";
    private static final Pattern CLOCK = Pattern.compile("\\b(?:\\d{1,2}:)?\\d{1,2}:\\d{2}\\b");
    private static final Pattern HEART = Pattern.compile("(\\d{2,3})\\s*(?:bpm|BPM)");
    private static final Pattern DISTANCE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|mi)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALORIES = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kcal|cal)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACE = Pattern.compile("(\\d{1,2}['′]\\s?\\d{2}[\"″]?)(?:\\s*/\\s*(?:km|mi))?");
    private static final Pattern SPEED = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km/h|mph)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEPS = Pattern.compile("(\\d{1,3}(?:[.,\\u00a0 ]\\d{3})*|\\d+)\\s*(passi|steps|schritte|pas|pasos)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PAUSED = Pattern.compile("paus", Pattern.CASE_INSENSITIVE);

    private final Context ctx;
    private final Runnable onChange;
    private final AppLook apps;
    private volatile IslandItem current;

    public WorkoutSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
        this.apps = new AppLook(ctx);
    }

    /** True for the ongoing notification of a Samsung Health workout. */
    static boolean isWorkout(StatusBarNotification sbn) {
        Notification n = sbn == null ? null : sbn.getNotification();
        if (n == null || !HEALTH_PKG.equals(sbn.getPackageName()) || (n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        String channel = n.getChannelId() == null ? "" : n.getChannelId().toLowerCase(Locale.ROOT);
        return Notification.CATEGORY_WORKOUT.equals(n.category) || channel.contains("exercise") || channel.contains("workout")
                || n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
    }

    /** Called on System UI's main thread every time the notification list was rebuilt. */
    public void update(List<StatusBarNotification> snapshot) {
        IslandItem fresh = null;
        if (ModuleSettings.workoutCard()) {
            for (StatusBarNotification sbn : snapshot) {
                if (isWorkout(sbn)) {
                    fresh = build(sbn);
                    break;
                }
            }
        }
        IslandItem before = current;
        boolean changed = before == null ? fresh != null : fresh == null || !before.key.equals(fresh.key)
                || !String.valueOf(before.title).equals(String.valueOf(fresh.title))
                || !String.valueOf(before.subtitle).equals(String.valueOf(fresh.subtitle))
                || !String.valueOf(before.nowValue).equals(String.valueOf(fresh.nowValue))
                || before.chronoBase != fresh.chronoBase || !sameStats(before, fresh);
        if (!changed) return;
        current = fresh;
        onChange.run();
    }

    private IslandItem build(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        IslandItem it = new IslandItem("workout:" + sbn.getKey());
        it.kind = IslandItem.Kind.LIVE;
        it.eventLayout = IslandItem.EventLayout.NOW;
        it.sourceKey = sbn.getKey();
        it.packageName = HEALTH_PKG;
        it.postTime = sbn.getPostTime();
        it.icon = GlyphIcon.of(GlyphIcon.Kind.WORKOUT);
        it.nowHeaderGlyph = GlyphIcon.of(GlyphIcon.Kind.WORKOUT);
        it.appIcon = apps.icon(HEALTH_PKG);
        it.appName = apps.label(HEALTH_PKG);
        it.nowHeader = it.appName != null ? it.appName : IslandText.get(ctx, "island_workout");
        it.accent = -1;
        it.contentIntent = n.contentIntent;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        it.title = title != null && title.length() > 0 ? title : IslandText.get(ctx, "island_workout");
        String all = texts(x);
        boolean paused = PAUSED.matcher(all).find();
        if (n.when > 0 && x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && !paused) {
            it.chronoBase = n.when;
            it.chronoCountDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false);
            it.trailing = IslandItem.Trailing.CHRONOMETER;
        } else {
            Matcher clock = CLOCK.matcher(all);
            it.nowValue = clock.find() ? clock.group() : null;
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = it.nowValue != null ? it.nowValue : it.title;
        }
        it.nowValueLabel = IslandText.get(ctx, paused ? "island_workout_paused" : "island_workout_time");
        stat(it, HEART, all, "island_workout_heart", "bpm");
        stat(it, DISTANCE, all, "island_workout_distance", null);
        stat(it, PACE, all, "island_workout_pace", null);
        stat(it, SPEED, all, "island_workout_speed", null);
        stat(it, CALORIES, all, "island_workout_calories", null);
        stat(it, STEPS, all, "island_workout_steps", null);
        // with no figures to show, the notification's own line stays as the subtitle
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (it.nowStats.isEmpty() && text != null) it.subtitle = text;
        it.compactLabel = it.title;
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || a.title == null || it.actions.size() >= 3) continue;
                it.actions.add(new IslandItem.Action(a.title, null, a.actionIntent, null, false));
            }
        }
        return it;
    }

    /** Adds one figure (value with its unit, label) unless three are there already or it is not in the text. */
    private void stat(IslandItem it, Pattern pattern, String all, String labelKey, String unit) {
        if (it.nowStats.size() >= 3) return;
        Matcher m = pattern.matcher(all);
        if (!m.find()) return;
        String value = m.groupCount() >= 2 && m.group(2) != null && unit == null ? m.group(1) + " " + m.group(2)
                : unit != null ? m.group(1) + " " + unit : m.group(1);
        it.nowStats.add(new String[] {value.trim(), IslandText.get(ctx, labelKey)});
    }

    /** Every text line of the notification, one per line. */
    private static String texts(Bundle x) {
        List<CharSequence> parts = new ArrayList<>();
        for (String key : new String[] {Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT}) {
            CharSequence v = x.getCharSequence(key);
            if (v != null) parts.add(v);
        }
        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence l : lines) if (l != null) parts.add(l);
        StringBuilder sb = new StringBuilder();
        for (CharSequence p : parts) sb.append(p).append('\n');
        return sb.toString();
    }

    private static boolean sameStats(IslandItem a, IslandItem b) {
        if (a.nowStats.size() != b.nowStats.size()) return false;
        for (int i = 0; i < a.nowStats.size(); i++) if (!a.nowStats.get(i)[0].equals(b.nowStats.get(i)[0])) return false;
        return true;
    }

    /** The notification key the workout card was made from, or null. */
    public String shownKey() {
        IslandItem it = current;
        return it != null && ModuleSettings.workoutCard() ? it.sourceKey : null;
    }

    public void collect(List<IslandItem> out) {
        IslandItem it = current;
        if (it != null && ModuleSettings.workoutCard()) out.add(it);
    }
}
