package dev.android.dynamicisland.island.source;

import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.CalendarContract;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The next calendar event, from the calendar provider (System UI holds READ_CALENDAR): from {@link #LEAD_MS} before it
 * starts the island counts down to it, then says it is starting for {@link #AFTER_MS}. An online meeting link in the
 * place or the notes (Meet, Zoom, Teams, Webex) becomes a Join button.
 *
 * <p>The provider is read on a background thread when the calendar changes and on a timer: every 30 s while an event
 * is near, otherwise once when the next one enters the window.
 */
public final class CalendarSource {
    static final long LEAD_MS = 15 * 60_000L;
    private static final long AFTER_MS = 5 * 60_000L;
    private static final long NEAR_POLL_MS = 30_000L;
    private static final long FAR_LOOKAHEAD_MS = 24 * 3600_000L;
    private static final String CALENDAR_PKG = "com.samsung.android.calendar";
    private static final Pattern MEETING = Pattern.compile(
            "https?://(?:meet\\.google\\.com|[\\w.-]*zoom\\.us|teams\\.microsoft\\.com|teams\\.live\\.com|[\\w.-]*webex\\.com)/[^\\s<>\"]+",
            Pattern.CASE_INSENSITIVE);
    private static final String[] COLUMNS = {
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
    };

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final AppLook apps;
    private Handler worker;
    private volatile IslandItem current;
    private final Runnable check = this::checkNow;

    /** One event as read from the provider. */
    private static final class Event {
        long id, begin, end;
        String title, location, description;
    }

    public CalendarSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
        this.apps = new AppLook(ctx);
    }

    public void start() {
        HandlerThread thread = new HandlerThread("island-calendar");
        thread.start();
        worker = new Handler(thread.getLooper());
        try {
            ctx.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, new ContentObserver(worker) {
                @Override
                public void onChange(boolean selfChange) {
                    reschedule(1000);
                }
            });
        } catch (Throwable t) {
            XLog.e("calendar observer not registered", t);
        }
        reschedule(0);
    }

    /** Settings changed: read again now. */
    public void syncSettings() {
        if (worker != null) reschedule(0);
    }

    private void reschedule(long delay) {
        worker.removeCallbacks(check);
        worker.postDelayed(check, delay);
    }

    private void checkNow() {
        IslandItem fresh = null;
        long nextCheck = NEAR_POLL_MS;
        try {
            if (ModuleSettings.calendarCard()) {
                long now = System.currentTimeMillis();
                Event near = first(now - AFTER_MS, now + LEAD_MS, now);
                if (near != null) {
                    fresh = card(near, now);
                } else {
                    Event later = first(now + LEAD_MS, now + FAR_LOOKAHEAD_MS, now);
                    nextCheck = later == null ? 30 * 60_000L : Math.max(NEAR_POLL_MS, later.begin - LEAD_MS - now);
                }
            }
        } catch (Throwable t) {
            XLog.once("calendar read", t);
        }
        IslandItem before = current;
        boolean changed = before == null ? fresh != null : fresh == null || !before.key.equals(fresh.key)
                || !String.valueOf(before.title).equals(String.valueOf(fresh.title))
                || !String.valueOf(before.subtitle).equals(String.valueOf(fresh.subtitle))
                || before.chronoBase != fresh.chronoBase;
        if (before != null && fresh != null && !changed) fresh = before;
        current = fresh;
        if (changed) main.post(onChange);
        worker.postDelayed(check, Math.min(nextCheck, 30 * 60_000L));
    }

    /** The earliest timed event (not all-day, not declined) that begins inside [from, to). */
    private Event first(long from, long to, long now) {
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, from);
        ContentUris.appendId(uri, to);
        String where = CalendarContract.Instances.VISIBLE + "=1 AND " + CalendarContract.Instances.ALL_DAY + "=0 AND "
                + CalendarContract.Instances.BEGIN + ">=" + from + " AND " + CalendarContract.Instances.BEGIN + "<" + to;
        try (Cursor c = ctx.getContentResolver().query(uri.build(), COLUMNS, where, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                Event e = new Event();
                e.id = c.getLong(0);
                e.title = c.getString(1);
                e.begin = c.getLong(2);
                e.end = c.getLong(3);
                e.location = c.getString(4);
                e.description = c.getString(5);
                if (e.end > 0 && e.end <= now) continue;
                return e;
            }
        }
        return null;
    }

    private IslandItem card(Event e, long now) {
        IslandItem it = new IslandItem("calendar:" + e.id + ":" + e.begin);
        it.kind = IslandItem.Kind.LIVE;
        it.eventLayout = IslandItem.EventLayout.NOW;
        it.packageName = CALENDAR_PKG;
        it.postTime = e.begin - LEAD_MS;
        it.icon = GlyphIcon.of(GlyphIcon.Kind.CALENDAR);
        it.nowHeaderGlyph = GlyphIcon.of(GlyphIcon.Kind.CALENDAR);
        it.appIcon = apps.icon(CALENDAR_PKG);
        it.appName = apps.label(CALENDAR_PKG);
        String timeRange = timeOf(e.begin) + (e.end > e.begin ? " – " + timeOf(e.end) : "");
        it.nowHeader = it.appName != null ? it.appName + " · " + timeRange : timeRange;
        it.title = e.title != null && !e.title.isEmpty() ? e.title : IslandText.get(ctx, "island_calendar");
        String meeting = meetingLink(e);
        String place = e.location != null && !e.location.isEmpty() && (meeting == null || !e.location.contains(meeting)) ? e.location : null;
        it.accent = -1;
        if (e.begin > now) {
            it.chronoBase = e.begin;
            it.chronoCountDown = true;
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.nowValueLabel = IslandText.get(ctx, "island_calendar_starts_in", "").trim();
            it.subtitle = place != null ? place : timeRange;
            it.progress = 1f - (e.begin - now) / (float) LEAD_MS;
        } else {
            it.nowValue = IslandText.get(ctx, "island_calendar_now");
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = it.nowValue;
            it.subtitle = place != null ? place : timeRange;
        }
        it.compactLabel = it.title;
        it.expiresAtWall = e.begin + AFTER_MS;
        PendingIntent open = activity(new Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end), (int) e.id);
        it.contentIntent = open;
        if (meeting != null) {
            PendingIntent join = activity(new Intent(Intent.ACTION_VIEW, Uri.parse(meeting)), (int) e.id + 1);
            if (join != null) {
                IslandItem.Action action = new IslandItem.Action(IslandText.get(ctx, "island_calendar_join"), null, join, null, false);
                action.affirmative = true;
                it.actions.add(action);
            }
        }
        if (open != null) it.actions.add(new IslandItem.Action(IslandText.get(ctx, "island_calendar_open"), null, open, null, false));
        return it;
    }

    private static String meetingLink(Event e) {
        for (String text : new String[] {e.location, e.description}) {
            if (text == null) continue;
            Matcher m = MEETING.matcher(text);
            if (m.find()) return m.group();
        }
        return null;
    }

    private String timeOf(long wall) {
        return android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(wall));
    }

    private PendingIntent activity(Intent intent, int code) {
        try {
            if (intent.resolveActivity(ctx.getPackageManager()) == null) return null;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0x6CA1 ^ code, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    public void collect(List<IslandItem> out) {
        IslandItem it = current;
        if (it == null || !ModuleSettings.calendarCard()) return;
        long now = System.currentTimeMillis();
        if (it.expiresAtWall > 0 && it.expiresAtWall <= now) return;
        if (it.chronoCountDown && it.chronoBase <= now) {
            // the countdown reached zero between two reads: read again so the card says it is starting
            reschedule(0);
        } else if (it.chronoCountDown) {
            it.progress = Math.max(0f, Math.min(1f, 1f - (it.chronoBase - now) / (float) LEAD_MS));
        }
        out.add(it);
    }
}
