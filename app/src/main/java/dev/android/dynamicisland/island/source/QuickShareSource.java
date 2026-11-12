package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;
import java.util.List;

/**
 * Incoming Quick Share on the island, AirDrop style: the request opens as a card with Decline / Accept, the transfer
 * runs as a progress ring in the pill, and a short "received" card closes it. Fed by {@code QuickShareHooks} in
 * Quick Share's own process, whose sheet stays hidden while this card stands in for it.
 */
public final class QuickShareSource {
    private static final int ACCENT = 0xFF3E91FF;
    private static final int DONE_GREEN = 0xFF3DB653;
    private static final long DONE_SHOWN_MS = 5000;
    /** How long the request card stays open by itself. */
    public static final long REQUEST_OPEN_MS = 30000;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final AppLook apps;
    private QuickShareState state;
    private IslandItem item;
    private long endsAt;
    /** Key of a request card that should open once, taken by the controller. */
    private String pendingOpen;
    private int lastRequestId = -1;
    private int previewId = -1;
    private java.util.List<android.graphics.Bitmap> previews = new java.util.ArrayList<>();
    /** Quick Share's receiving sheet is still there, so its Open button can be pressed. */
    private boolean sheetAlive;
    /** The island cancelled or declined the session: the sheet closing then is no failure. */
    private boolean userEnded;
    private final QuickShareSends sends;
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (endsAt > 0 && SystemClock.uptimeMillis() >= endsAt) {
                clear();
                onChange.run();
            }
        }
    };

    public QuickShareSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
        this.apps = new AppLook(ctx);
        this.sends = new QuickShareSends(ctx, main, onChange, apps);
    }

    /** Fed with the whole notification list: Quick Share's outgoing transfers live in its notifications. */
    public void updateNotifications(List<android.service.notification.StatusBarNotification> all) {
        if (!ModuleSettings.quickShare()) return;
        List<android.service.notification.StatusBarNotification> copy = new java.util.ArrayList<>(all);
        Runnable run = () -> {
            try {
                sends.update(copy);
            } catch (Throwable t) {
                XLog.once("QuickShareSource.updateNotifications", t);
            }
        };
        if (android.os.Looper.myLooper() == main.getLooper()) run.run();
        else main.post(run);
    }

    public void start() {
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        onState(intent);
                    } catch (Throwable t) {
                        XLog.once("QuickShareSource.onState", t);
                    }
                }
            }, new IntentFilter(QuickShareState.ACTION_STATE), QuickShareState.PERMISSION, main, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("quick share receiver", t);
        }
    }

    private void onState(Intent intent) {
        if (!ModuleSettings.quickShare()) {
            command(QuickShareState.CMD_RELEASE);
            return;
        }
        if (intent.hasExtra(QuickShareState.EXTRA_SEND_SHARE)) {
            sends.onProgress(intent);
            return;
        }
        if (intent.hasExtra(QuickShareState.EXTRA_PREVIEW + 0)) {
            int id = intent.getIntExtra(QuickShareState.EXTRA_ID, -1);
            java.util.List<android.graphics.Bitmap> list = new java.util.ArrayList<>();
            for (int i = 0; i < QuickShareState.MAX_PREVIEWS; i++) {
                byte[] jpeg = intent.getByteArrayExtra(QuickShareState.EXTRA_PREVIEW + i);
                if (jpeg == null) continue;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bmp != null) list.add(bmp);
            }
            if (list.isEmpty()) return;
            previewId = id;
            previews = list;
            if (state != null && state.id == id) {
                item = build(state);
                onChange.run();
            }
            return;
        }
        if (intent.getBooleanExtra(QuickShareState.EXTRA_GONE, false)) {
            sheetAlive = false;
            // sheet closed: a finished transfer keeps its short card; one that was still running ended badly
            if (state != null && state.phase() == QuickShareState.Phase.TRANSFER && !userEnded) {
                state = state.withStatus(state.progress >= 0.999f ? "TRANSFER_FINISHED" : "TRANSFER_FAILED");
                item = build(state);
                Diagnostics.fact("quick share", "sheet closed mid-transfer: " + state.phase());
                main.removeCallbacks(expire);
                endsAt = SystemClock.uptimeMillis() + DONE_SHOWN_MS;
                main.postDelayed(expire, DONE_SHOWN_MS);
                onChange.run();
            } else if (state == null || state.phase() == QuickShareState.Phase.REQUEST || state.phase() == QuickShareState.Phase.TRANSFER) {
                clear();
                onChange.run();
            } else if (item != null) {
                item = build(state); // a tap now opens Quick Share instead of the gone sheet
                onChange.run();
            }
            return;
        }
        QuickShareState next = QuickShareState.parse(intent.getStringExtra(QuickShareState.EXTRA_STATE));
        if (next == null) return;
        command(QuickShareState.CMD_ACK);
        if (state == null || state.id != next.id) userEnded = false;
        sheetAlive = true;
        state = next;
        item = build(next);
        Diagnostics.fact("quick share", next.phase() + " " + next.receiveStatus + " " + Math.round(next.progress * 100) + "%");
        QuickShareState.Phase phase = next.phase();
        if (phase == QuickShareState.Phase.REQUEST && next.id != lastRequestId) {
            lastRequestId = next.id;
            pendingOpen = item.key;
        }
        main.removeCallbacks(expire);
        if (phase == QuickShareState.Phase.DONE || phase == QuickShareState.Phase.FAILED || phase == QuickShareState.Phase.DECLINED) {
            endsAt = SystemClock.uptimeMillis() + DONE_SHOWN_MS;
            main.postDelayed(expire, DONE_SHOWN_MS);
            if (phase != QuickShareState.Phase.DONE) {
                main.postDelayed(() -> command(QuickShareState.CMD_CLOSE), 600);
            }
        } else {
            endsAt = 0;
        }
        onChange.run();
    }

    private void clear() {
        state = null;
        item = null;
        endsAt = 0;
        pendingOpen = null;
        userEnded = false;
    }

    IslandItem build(QuickShareState s) {
        IslandItem it = new IslandItem("qs:" + s.id);
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = QuickShareState.PACKAGE;
        it.appName = IslandText.get(ctx, "island_qs_name");
        it.title = s.sender != null ? s.sender : it.appName;
        it.accent = ACCENT;
        it.postTime = System.currentTimeMillis();
        Drawable icon = apps.icon(QuickShareState.PACKAGE);
        it.icon = icon;
        it.iconIsImage = true;
        it.cardImage = icon;
        it.cardImageIsImage = true;
        it.actionType = 1;
        it.transfer = true;
        it.eventLayout = IslandItem.EventLayout.QUICK_SHARE;
        it.shareKind = s.kind();
        if (previewId == s.id) it.sharePreviews = previews;
        it.shareLabel = label(s);
        it.shareBadge = badge(s);
        it.shareDone = s.phase() == QuickShareState.Phase.DONE;
        String what = s.fileCount > 1 ? IslandText.get(ctx, "island_qs_files", s.fileCount)
                : s.fileName != null ? s.fileName : s.text;
        switch (s.phase()) {
            case REQUEST: {
                it.subtitle = s.fileCount > 1 ? IslandText.get(ctx, "island_qs_req_many", s.fileCount)
                        : IslandText.get(ctx, "island_qs_req_" + s.kind());
                it.detail = what;
                it.trailing = IslandItem.Trailing.PROGRESS_RING;
                it.progress = -1f;
                it.keepExpandedUntilUptime = SystemClock.uptimeMillis() + REQUEST_OPEN_MS;
                IslandItem.Action decline = new IslandItem.Action(IslandText.get(ctx, "island_qs_decline"), null, null,
                        () -> { userEnded = true; command(QuickShareState.CMD_DECLINE); }, true);
                IslandItem.Action accept = new IslandItem.Action(IslandText.get(ctx, "island_qs_accept"), null, null,
                        () -> command(QuickShareState.CMD_ACCEPT), false);
                accept.affirmative = true;
                it.actions.add(decline);
                it.actions.add(accept);
                it.primaryActionIndex = 1;
                break;
            }
            case TRANSFER: {
                it.subtitle = IslandText.get(ctx, "island_qs_receiving");
                it.detail = what;
                it.trailing = IslandItem.Trailing.PROGRESS_RING;
                if (s.progress >= 0f && s.progress < 1f) {
                    it.progress = s.progress;
                    it.trailingText = Math.round(s.progress * 100) + "%";
                } else {
                    it.progress = -1f;
                    it.progressIndeterminate = true;
                }
                it.actions.add(new IslandItem.Action(IslandText.get(ctx, "island_qs_cancel"), null, null,
                        () -> { userEnded = true; command(QuickShareState.CMD_CANCEL); }, true));
                break;
            }
            case DONE:
                it.subtitle = IslandText.get(ctx, "island_qs_received");
                it.detail = what;
                it.accent = DONE_GREEN;
                it.trailing = IslandItem.Trailing.ICON;
                it.trailingIcon = new dev.android.dynamicisland.island.graphics.TickDrawable();
                // the sheet's own Open / View: the received file, link or contact opens where Quick Share would take it
                if (sheetAlive) it.open = () -> command(QuickShareState.CMD_OPEN);
                break;
            default:
                it.subtitle = IslandText.get(ctx, s.phase() == QuickShareState.Phase.DECLINED ? "island_qs_declined" : "island_qs_receive_failed");
                it.detail = what;
                it.accent = 0xFFFF5B62;
                if (s.phase() == QuickShareState.Phase.FAILED) {
                    it.trailing = IslandItem.Trailing.ICON;
                    it.trailingIcon = new dev.android.dynamicisland.island.graphics.ErrorDrawable();
                }
        }
        return it;
    }

    /** The line about the content; a shared Wi-Fi only ever shows its network name. */
    private CharSequence label(QuickShareState s) {
        switch (s.kind()) {
            case "wifi":
                return QuickShareState.wifiName(s.text);
            case "link": {
                String t = s.text != null ? s.text : s.fileName;
                if (t == null) return null;
                try {
                    String host = android.net.Uri.parse(t.trim()).getHost();
                    return host != null ? host.replaceFirst("^www\\.", "") : t;
                } catch (Throwable e) {
                    return t;
                }
            }
            case "text":
                return s.text != null ? s.text : s.fileName;
            case "contact": {
                String n = s.fileName;
                return n == null ? null : n.replaceFirst("(?i)\\.vcf$", "");
            }
            default:
                return s.fileCount > 1 ? IslandText.get(ctx, "island_qs_files", s.fileCount) : s.fileName;
        }
    }

    /** Extension badge for files without a picture: "PDF", "ZIP" ... */
    private static String badge(QuickShareState s) {
        String f = s.fileType == null ? "" : s.fileType;
        switch (f) {
            case "PDF": case "ZIP": case "APK": return f;
            case "WORD": return "DOC";
            case "EXCEL": return "XLS";
            case "POWERPOINT": return "PPT";
            case "HANGUL": return "HWP";
            case "CALENDAR": return "ICS";
            default:
        }
        String name = s.fileName;
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 5) return name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
        return null;
    }

    private void command(String cmd) {
        try {
            Intent intent = new Intent(QuickShareState.ACTION_COMMAND).setPackage(QuickShareState.PACKAGE)
                    .putExtra(QuickShareState.EXTRA_COMMAND, cmd).putExtra(QuickShareState.EXTRA_ID, state == null ? -1 : state.id);
            ctx.sendBroadcast(intent, null);
        } catch (Throwable t) {
            XLog.once("QuickShareSource.command", t);
        }
    }

    /** Adds the session's card; while it runs, Quick Share's ordinary progress card of the same transfer goes. */
    public void collect(List<IslandItem> out) {
        if ((item == null && sends.isEmpty()) || !ModuleSettings.quickShare()) return;
        out.removeIf(it -> QuickShareState.PACKAGE.equals(it.packageName) && !it.key.startsWith("qs:") && !it.key.startsWith("qss:"));
        if (item != null) out.add(item);
        sends.collect(out);
    }

    /** The request card to open now, once; null otherwise. */
    public String takePendingOpen() {
        String key = pendingOpen;
        pendingOpen = null;
        return key;
    }
}
