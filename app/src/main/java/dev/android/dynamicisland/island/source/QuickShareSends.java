package dev.android.dynamicisland.island.source;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.model.IslandItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Outgoing Quick Share transfers on the island. Quick Share's own notifications tell the phase (sending, sent,
 * couldn't send) and where a tap goes (its outgoing-transfer screen); the byte counters come from
 * {@code QuickShareSendWatch} in Quick Share's process, since the sending notification has no progress bar.
 */
final class QuickShareSends {
    private static final int ACCENT = 0xFF3E91FF;
    private static final int DONE_GREEN = 0xFF3DB653;
    private static final int FAILED_RED = 0xFFFF5B62;
    private static final long END_SHOWN_MS = 5000;
    /** An end notification older than this was already there (System UI restart, shade rebuild): no new card. */
    private static final long END_FRESH_MS = 15000;
    private static final int MAX_ACTIONS = 2;

    private enum Phase { SENDING, SENT, FAILED }

    private static final class Send {
        final String shareId;
        Phase phase;
        CharSequence notificationTitle;
        android.app.PendingIntent contentIntent;
        List<IslandItem.Action> actions = new ArrayList<>();
        float progress = -1f;
        String device;
        String file;
        int count;
        long endsAt;
        IslandItem item;

        Send(String shareId) {
            this.shareId = shareId;
        }
    }

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final AppLook apps;
    private final Map<String, Send> sends = new LinkedHashMap<>();
    /** Details of shares read from Quick Share's database, kept for the end card (its notification has none). */
    private final Map<String, Send> known = new HashMap<>();
    /** shareId + phase of end cards already shown once. */
    private final Set<String> ended = new HashSet<>();
    private final Runnable expire = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (sends.values().removeIf(s -> s.endsAt > 0 && now >= s.endsAt)) onChange.run();
            schedule();
        }
    };

    QuickShareSends(Context ctx, Handler main, Runnable onChange, AppLook apps) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
        this.apps = apps;
    }

    /** Counters of one outgoing share, from Quick Share's process. */
    void onProgress(Intent intent) {
        String id = intent.getStringExtra(QuickShareState.EXTRA_SEND_SHARE);
        if (id == null) return;
        Send info = known.computeIfAbsent(id, Send::new);
        info.progress = intent.getFloatExtra(QuickShareState.EXTRA_SEND_PROGRESS, -1f);
        String device = intent.getStringExtra(QuickShareState.EXTRA_SEND_DEVICE);
        String file = intent.getStringExtra(QuickShareState.EXTRA_SEND_FILE);
        if (device != null) info.device = device;
        if (file != null) info.file = file;
        info.count = Math.max(info.count, intent.getIntExtra(QuickShareState.EXTRA_SEND_COUNT, 0));
        Send live = sends.get(id);
        if (live != null && live.phase == Phase.SENDING) {
            rebuild(live);
            onChange.run();
        }
    }

    /** Quick Share's notifications of outgoing shares, from the full notification list. */
    void update(List<StatusBarNotification> all) {
        Set<String> sendingNow = new HashSet<>();
        boolean changed = false;
        long wall = System.currentTimeMillis();
        for (StatusBarNotification sbn : all) {
            if (sbn == null || !QuickShareState.PACKAGE.equals(sbn.getPackageName())) continue;
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null || !QuickShareState.SEND_CHANNEL.equals(n.getChannelId())
                    || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            String id = n.extras.getString(QuickShareState.EXTRA_SHARE_ID);
            if (id == null) continue;
            Phase phase = "progress".equals(n.category) ? Phase.SENDING
                    : n.extras.getBoolean("extra_is_sent", false) ? Phase.SENT
                    : "err".equals(n.category) ? Phase.FAILED : null;
            if (phase == null) continue;
            if (phase == Phase.SENDING) {
                sendingNow.add(id);
            } else {
                Send live = sends.get(id);
                boolean wasSending = live != null && live.phase == Phase.SENDING;
                if (!wasSending && (wall - sbn.getPostTime() > END_FRESH_MS || ended.contains(id + phase))) continue;
                if (live != null && live.phase == phase) continue;
                ended.add(id + phase);
            }
            Send s = sends.get(id);
            if (s != null && s.phase == phase && java.util.Objects.equals(s.contentIntent, n.contentIntent)
                    && String.valueOf(s.notificationTitle).equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))) continue;
            if (s == null) {
                s = new Send(id);
                sends.put(id, s);
            }
            s.phase = phase;
            s.notificationTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            s.contentIntent = n.contentIntent;
            s.actions = new ArrayList<>();
            if (phase == Phase.SENDING && n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a.actionIntent != null && a.title != null && s.actions.size() < MAX_ACTIONS) {
                        s.actions.add(new IslandItem.Action(a.title, null, a.actionIntent, null, OaSource.isDestructive(a.title)));
                    }
                }
            }
            s.endsAt = phase == Phase.SENDING ? 0 : SystemClock.uptimeMillis() + END_SHOWN_MS;
            rebuild(s);
            changed = true;
        }
        // a sending notification gone without an end notification: the share is over
        if (sends.values().removeIf(s -> s.phase == Phase.SENDING && !sendingNow.contains(s.shareId))) changed = true;
        if (changed) {
            schedule();
            onChange.run();
        }
    }

    private void schedule() {
        main.removeCallbacks(expire);
        long next = Long.MAX_VALUE;
        for (Send s : sends.values()) if (s.endsAt > 0) next = Math.min(next, s.endsAt);
        if (next != Long.MAX_VALUE) main.postDelayed(expire, Math.max(0, next - SystemClock.uptimeMillis()));
    }

    boolean isEmpty() {
        return sends.isEmpty();
    }

    void collect(List<IslandItem> out) {
        for (Send s : sends.values()) if (s.item != null) out.add(s.item);
    }

    private void rebuild(Send s) {
        Send info = known.get(s.shareId);
        IslandItem it = new IslandItem("qss:" + s.shareId);
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = QuickShareState.PACKAGE;
        it.appName = IslandText.get(ctx, "island_qs_name");
        String device = info != null ? info.device : null;
        it.title = device != null && !device.isEmpty() ? device : it.appName;
        it.postTime = System.currentTimeMillis();
        Drawable icon = apps.icon(QuickShareState.PACKAGE);
        it.icon = icon;
        it.iconIsImage = true;
        it.cardImage = icon;
        it.cardImageIsImage = true;
        it.actionType = 1;
        it.transfer = true;
        it.contentIntent = s.contentIntent; // Quick Share's own outgoing-transfer screen
        String file = info != null ? info.file : null;
        int count = info != null ? info.count : 0;
        CharSequence what = count > 1 ? IslandText.get(ctx, "island_qs_files", count)
                : file != null ? file : s.notificationTitle;
        it.eventLayout = IslandItem.EventLayout.QUICK_SHARE;
        it.shareKind = kindOf(file, count);
        it.shareLabel = what;
        it.shareBadge = badge(file);
        it.shareDone = s.phase == Phase.SENT;
        it.detail = what;
        switch (s.phase) {
            case SENDING: {
                it.accent = ACCENT;
                it.subtitle = IslandText.get(ctx, "island_qs_sending");
                it.trailing = IslandItem.Trailing.PROGRESS_RING;
                float p = info != null ? info.progress : -1f;
                if (p >= 0f && p < 1f) {
                    it.progress = p;
                    it.trailingText = Math.round(p * 100) + "%";
                } else {
                    it.progress = -1f;
                    it.progressIndeterminate = true;
                }
                it.actions.addAll(s.actions);
                break;
            }
            case SENT:
                it.accent = DONE_GREEN;
                it.subtitle = IslandText.get(ctx, "island_qs_sent");
                it.trailing = IslandItem.Trailing.ICON;
                it.trailingIcon = new dev.android.dynamicisland.island.graphics.TickDrawable();
                break;
            default:
                it.accent = FAILED_RED;
                it.subtitle = IslandText.get(ctx, "island_qs_send_failed");
                it.trailing = IslandItem.Trailing.ICON;
                it.trailingIcon = new dev.android.dynamicisland.island.graphics.ErrorDrawable();
                // "Couldn't send 1 file": Quick Share's own line says how many
                if (s.notificationTitle != null) it.detail = s.notificationTitle;
        }
        s.item = it;
    }

    private static String extension(String file) {
        int dot = file == null ? -1 : file.lastIndexOf('.');
        return dot > 0 && file.length() - dot <= 5 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String kindOf(String file, int count) {
        if (count > 1) return "file";
        switch (extension(file)) {
            case "jpg": case "jpeg": case "png": case "heic": case "heif": case "webp": case "gif": case "dng":
                return "image";
            case "mp4": case "mov": case "mkv": case "3gp": case "webm":
                return "video";
            case "mp3": case "m4a": case "wav": case "ogg": case "flac": case "aac":
                return "audio";
            case "apk":
                return "app";
            case "vcf":
                return "contact";
            default:
                return "file";
        }
    }

    private static String badge(String file) {
        String ext = extension(file);
        return ext.isEmpty() || kindOf(file, 1).equals("image") || kindOf(file, 1).equals("video") ? null : ext.toUpperCase(Locale.ROOT);
    }
}
