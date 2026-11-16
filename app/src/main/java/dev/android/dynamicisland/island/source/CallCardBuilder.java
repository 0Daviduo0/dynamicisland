package dev.android.dynamicisland.island.source;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.widget.ImageView;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The in-call card, made from System UI's ongoing-call controller ({@code callNotificationInfo}) and,
 * when the Now Bar has one, its caller name, photo and call buttons. Adds a speaker toggle and an
 * end-call button that work even though the real call notification is hidden by the island.
 */
final class CallCardBuilder {
    private static final String SPEAKER_TOGGLE = "com.samsung.rcs.intent.action.SPEAKER_TOGGLE";
    private static final String INCALLUI = "com.samsung.android.incallui";
    /** Apps whose calls Telecom can end and whose speaker the in-call UI can toggle. */
    private static final Set<String> TELECOM_PACKAGES = new HashSet<>(Arrays.asList(INCALLUI,
            "com.samsung.android.dialer", "com.android.server.telecom", "com.android.phone",
            "com.google.android.dialer", "com.android.dialer", "com.android.incallui"));
    /** Glyph ids drawn by the card view for buttons without an icon. */
    private static final int GLYPH_END_CALL = 9;
    private static final int GLYPH_SPEAKER = 10;

    /** What the call controller says about the call chip. */
    private enum Chip { NONE, SHOWN, APP_VISIBLE }

    private final Context ctx;
    private final IconLoader icons;
    private final OaReader reader;
    private final WhatsAppCallClock callClock;
    /** Run after the speaker was toggled, once the audio route has had time to settle. */
    private final Runnable afterSpeakerToggle;
    /** Whether the phone is ringing with a call nobody has answered yet. */
    private BooleanSupplier phoneRinging = this::telephonyRinging;
    private static final int PHOTO_CACHE_SIZE = 16;
    /** Caller pictures already checked for readability, by call key: {source Icon, shown Drawable or null}. */
    private final java.util.Map<String, Object[]> photos = new java.util.HashMap<>();
    private final AppLook apps;

    CallCardBuilder(Context ctx, IconLoader icons, OaReader reader, Runnable afterSpeakerToggle, WhatsAppCallClock callClock) {
        this.ctx = ctx;
        this.apps = AppLook.shared(ctx);
        this.icons = icons;
        this.reader = reader;
        this.callClock = callClock;
        this.afterSpeakerToggle = afterSpeakerToggle;
        this.contacts = new ContactPhotos(ctx, afterSpeakerToggle);
    }

    /** Address-book pictures; a loaded one triggers the same refresh as a speaker toggle. */
    private final ContactPhotos contacts;

    /** Adds the card of the ongoing call, if any; {@code controller} is System UI's call controller (may be null). */
    void collect(Object controller, List<IslandItem> out, Collection<String> packages, Set<String> hiddenByForeground) {
        collect(controller, out, packages, hiddenByForeground, null, false);
    }

    /** {@code foreground}: the app on screen, used only when {@code foregroundConfirmed} (a fresh task snapshot). */
    void collect(Object controller, List<IslandItem> out, Collection<String> packages, Set<String> hiddenByForeground,
            String foreground, boolean foregroundConfirmed) {
        Object info = controller == null ? null : Refl.get(controller, "callNotificationInfo");
        String key = info == null ? null : (String) Refl.get(info, "key");
        if (key == null) {
            return;
        }
        String pkg = (String) Refl.get(info, "packageName");
        if (pkg == null) {
            pkg = packageOfKey(key); // One UI 7 does not keep the package on the call info
        }
        if (pkg != null) {
            packages.add(pkg);
        }
        switch (chipState(controller, info, pkg, foregroundConfirmed ? foreground : null)) {
            case NONE:
                return;
            case APP_VISIBLE:
                hiddenByForeground.add("call:" + key);
                return;
            default:
                out.add(card(info, key, pkg));
        }
    }

    /**
     * System UI keeps the call chip away while the call app counts as visible. That comes from a process-state
     * change which arrives a second or two after the call screen was closed, but the task stack says so at once:
     * when the app on screen is not the call app, the card does not have to wait.
     */
    private static Chip chipState(Object controller, Object info, String callPkg, String confirmedForeground) {
        if (!Refl.getBool(info, "isOngoing", false)) {
            return Chip.NONE;
        }
        if (Boolean.TRUE.equals(Refl.call(controller, "hasOngoingCall", new Object[0]))) {
            return Chip.SHOWN;
        }
        return confirmedForeground != null && !isCallApp(callPkg, confirmedForeground) ? Chip.SHOWN : Chip.APP_VISIBLE;
    }

    /** Whether {@code foreground} is the app of the call; the phone app's own packages count as one app. */
    static boolean isCallApp(String callPkg, String foreground) {
        if (callPkg == null || foreground.equals(callPkg)) {
            return true;
        }
        return TELECOM_PACKAGES.contains(callPkg) && TELECOM_PACKAGES.contains(foreground);
    }

    /** Replaces the check for a ringing phone; tests only. */
    void setPhoneRinging(BooleanSupplier probe) {
        phoneRinging = probe;
    }

    /** The telephony call state, or the ringtone audio mode when the state cannot be read. */
    private boolean telephonyRinging() {
        try {
            TelephonyManager telephony = ctx.getSystemService(TelephonyManager.class);
            if (telephony != null && telephony.getCallState() == TelephonyManager.CALL_STATE_RINGING) {
                return true;
            }
        } catch (Throwable t) {
            // no permission to read it: the audio mode below still tells
        }
        try {
            AudioManager audio = ctx.getSystemService(AudioManager.class);
            return audio != null && audio.getMode() == AudioManager.MODE_RINGTONE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A call of the phone app that still rings: the call has no start time and the phone reports ringing. */
    private boolean isRinging(Object info, String pkg) {
        if (pkg != null && !TELECOM_PACKAGES.contains(pkg)) {
            return false;
        }
        Object start = Refl.get(info, "callStartTime");
        return !(start instanceof Long && (Long) start > 0) && phoneRinging.getAsBoolean();
    }

    private IslandItem card(Object info, String key, String pkg) {
        IslandItem it = new IslandItem("call:" + key);
        it.kind = IslandItem.Kind.CALL;
        it.packageName = pkg;
        it.accent = Colors.CALL_GREEN;

        Drawable chipIcon = copyOfShownIcon(Refl.get(info, "notificationIconView"));
        it.icon = chipIcon != null ? chipIcon : icons.appIcon(pkg);
        it.iconIsImage = chipIcon == null;
        it.appName = (String) Refl.get(info, "appName");
        if (it.appName == null && pkg != null) {
            it.appName = labelOf(pkg);
        }
        it.title = it.appName;
        it.cardImage = icons.appIcon(pkg);
        it.cardImageIsImage = true;
        it.contentIntent = (PendingIntent) Refl.get(info, "intent");

        Object nowBar = reader.nowBarOnly(key);
        if (nowBar != null) {
            String caller = (String) Refl.get(nowBar, "mPrimaryInfo");
            if (OaCardBuilder.hasText(caller)) {
                it.title = caller;
            }
            it.subtitle = it.appName;
            Drawable photo = icons.load((Icon) Refl.get(nowBar, "mNowbarIcon"));
            if (photo != null) {
                it.cardImage = photo;
                it.cardImageRound = true;
            }
        }
        Notification callNotification = callNotification(key);
        Drawable person = contactPhoto(callNotification, it.title, it.appName);
        if (person == null) person = callerPhoto(key, callNotification, icons.appIcon(pkg), true);
        if (person != null) {
            it.cardImage = person; // the contact's own picture beats the now bar's, which can be the dialer's logo
            it.cardImageRound = true;
        } else if (it.cardImage == null || !(nowBar != null && Refl.get(nowBar, "mNowbarIcon") != null)) {
            // no photo from the now bar: the call notification's large icon, else the caller's initials
            Drawable photo = callerPhoto(key, callNotification, it.cardImage, false);
            if (photo == null && it.title != null && !String.valueOf(it.title).equals(it.appName)) {
                photo = dev.android.dynamicisland.island.graphics.Monogram.of(it.title);
            }
            if (photo != null) {
                it.cardImage = photo;
                it.cardImageRound = true;
            }
        }
        boolean ringing = isRinging(info, pkg);
        addButtons(it, nowBar, pkg, ringing);
        if (ringing) {
            // nobody has answered: the caller instead of a clock, and answer / decline instead of speaker / end
            it.callRinging = true;
            boolean named = it.title != null && !String.valueOf(it.title).equals(it.appName);
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = named ? it.title : IslandText.get(ctx, "island_incoming_call");
            if (named) it.subtitle = IslandText.get(ctx, "island_incoming_call");
            if (it.cardImage != null && it.cardImage != it.icon && it.cardImageRound) {
                // the caller's contact photo leads the pill too
                it.icon = it.cardImage;
                it.iconIsImage = true;
                it.cardImageRound = true;
            }
        } else {
            startClock(it, info, key);
        }
        it.postTime = Long.MAX_VALUE;
        return it;
    }

    /** The caller's address-book picture, by the CallStyle person's URI or the shown name / number; null until loaded. */
    private Drawable contactPhoto(Notification n, CharSequence title, CharSequence appName) {
        String uri = null;
        CharSequence shown = title != null && !String.valueOf(title).equals(String.valueOf(appName)) ? title : null;
        try {
            Object person = n == null ? null : n.extras.getParcelable(Notification.EXTRA_CALL_PERSON);
            if (person instanceof android.app.Person) {
                android.app.Person p = (android.app.Person) person;
                uri = p.getUri();
                if (shown == null) shown = p.getName();
            }
        } catch (Throwable t) {
            // no notification to read: shown caller still works
        }
        return contacts.photo(uri, shown);
    }

    /** The call notification behind this key, read once per card build; null when there is none. */
    private Notification callNotification(String key) {
        try {
            Object data = reader.findByNotificationKey(key);
            return data == null ? null : new OaActivity(data).notification;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The caller's picture from the call notification (CallStyle person, then large icon), or null. */
    private Drawable callerPhoto(String key, Notification n, Drawable appIcon, boolean personOnly) {
        try {
            if (n == null) return null;
            Object person = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON);
            Icon icon = person instanceof android.app.Person ? ((android.app.Person) person).getIcon() : null;
            if (icon == null && !personOnly) icon = n.getLargeIcon();
            if (icon == null) return null;
            // the card is rebuilt on every refresh during a call: render and sample the picture once per icon
            String cacheKey = key + (personOnly ? "#person" : "#large");
            Object[] known = photos.get(cacheKey);
            if (known != null && known[0] == icon) return copy((Drawable) known[1]);
            Drawable picture = icons.load(icon);
            Drawable shown = picture == null ? null
                    : dev.android.dynamicisland.island.detect.IconContrast.readable(ctx, picture, appIcon);
            Drawable result = shown == appIcon ? null : shown; // swapped back for the app icon: not a real picture
            if (photos.size() > PHOTO_CACHE_SIZE) photos.clear();
            photos.put(cacheKey, new Object[] {icon, result});
            return copy(result);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable copy(Drawable d) {
        return d == null || d.getConstantState() == null ? d : d.getConstantState().newDrawable().mutate();
    }

    /** The package in a notification key ("user|package|id|tag|uid"), or null. */
    static String packageOfKey(String key) {
        String[] parts = key.split("\\|", 3);
        return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
    }

    private String labelOf(String pkg) {
        return apps.label(pkg);
    }

    private static Drawable copyOfShownIcon(Object iconView) {
        if (!(iconView instanceof ImageView)) {
            return null;
        }
        Drawable shown = ((ImageView) iconView).getDrawable();
        return shown != null && shown.getConstantState() != null ? shown.getConstantState().newDrawable().mutate() : null;
    }

    private void startClock(IslandItem it, Object info, String key) {
        Object start = WhatsAppCallClock.applies(it.packageName) ? callClock.start(key) : Refl.get(info, "callStartTime");
        if (start instanceof Long && (Long) start > 0) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = (Long) start;
            it.chronoCountDown = false;
        }
    }

    /** Speaker toggle, at most one extra app button and the end-call button. */
    private void addButtons(IslandItem it, Object nowBar, String pkg, boolean ringing) {
        PendingIntent hangUp = null;
        Drawable hangUpIcon = null;
        PendingIntent speaker = null;
        Notification.Action extra = null;
        Object actions = nowBar == null ? null : Refl.get(nowBar, "mActions");
        if (actions instanceof List) {
            for (Object o : (List<?>) actions) {
                if (!(o instanceof Notification.Action)) {
                    continue;
                }
                Notification.Action action = (Notification.Action) o;
                if (action.actionIntent == null || action.title == null) {
                    continue;
                }
                if (hangUp == null) {
                    // a call notification lists its hang-up first
                    hangUp = action.actionIntent;
                    hangUpIcon = icons.load(action.getIcon(), pkg);
                } else if (ActionWords.isSpeaker(action.title)) {
                    speaker = action.actionIntent;
                } else if (extra == null && !ActionWords.isMute(action.title)) {
                    extra = action;
                }
            }
        }

        boolean telecom = pkg == null || TELECOM_PACKAGES.contains(pkg);
        if (ringing) {
            addRingingButtons(it, telecom, hangUp, extra == null ? null : extra.actionIntent);
            return;
        }
        AudioManager audio = ctx.getSystemService(AudioManager.class);
        if (audio != null && (telecom || speaker != null)) {
            PendingIntent viaAction = speaker;
            IslandItem.Action toggle = new IslandItem.Action(IslandText.get(ctx, "island_speaker"), null, null, () -> toggleSpeaker(viaAction, telecom), false);
            toggle.glyph = GLYPH_SPEAKER;
            toggle.active = isSpeakerRoute(audio);
            it.actions.add(toggle);
        }
        if (extra != null) {
            it.actions.add(new IslandItem.Action(extra.title, icons.load(extra.getIcon(), pkg), extra.actionIntent, null, false));
        }
        if (telecom || hangUp != null) {
            PendingIntent viaAction = hangUp;
            IslandItem.Action end = new IslandItem.Action(IslandText.get(ctx, "island_end_call"), hangUpIcon, null, () -> endCall(telecom, viaAction), true);
            if (hangUpIcon == null) {
                end.glyph = GLYPH_END_CALL;
            }
            it.actions.add(end);
        }
    }

    /** Answer and decline: a call that has not been answered has no speaker to switch and nothing to end yet. */
    private void addRingingButtons(IslandItem it, boolean telecom, PendingIntent decline, PendingIntent answerAction) {
        if (telecom || answerAction != null) {
            IslandItem.Action answer = new IslandItem.Action(IslandText.get(ctx, "island_answer_call"), null, null,
                    () -> {
                        answerCall(telecom, answerAction);
                        afterSpeakerToggle.run();
                    }, false);
            answer.glyph = Glyphs.ANSWER;
            answer.affirmative = true;
            it.actions.add(answer);
        }
        if (telecom || decline != null) {
            IslandItem.Action reject = new IslandItem.Action(IslandText.get(ctx, "island_decline_call"), null, null,
                    () -> {
                        endCall(telecom, decline);
                        afterSpeakerToggle.run();
                    }, true);
            reject.glyph = GLYPH_END_CALL;
            it.actions.add(reject);
        }
    }

    /** Answers the ringing call through Telecom when allowed, otherwise fires the notification's answer action. */
    // Runs inside SystemUI; Telecom enforces its host permissions and failure uses the action fallback.
    @android.annotation.SuppressLint("MissingPermission")
    private void answerCall(boolean telecom, PendingIntent answer) {
        if (telecom) {
            try {
                TelecomManager telecomManager = ctx.getSystemService(TelecomManager.class);
                if (telecomManager != null) {
                    telecomManager.acceptRingingCall();
                    XLog.i("call answered via telecom");
                    return;
                }
            } catch (Throwable t) {
                XLog.e("telecom acceptRingingCall failed", t);
            }
        }
        if (answer == null) {
            return;
        }
        try {
            sendFromBackground(answer);
            XLog.i("call answer intent sent");
        } catch (Throwable t) {
            XLog.e("answer intent failed", t);
        }
    }

    private static boolean isSpeakerRoute(AudioManager audio) {
        try {
            AudioDeviceInfo device = audio.getCommunicationDevice();
            return device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ends the call through Telecom when allowed, otherwise fires the notification's hang-up action. */
    // Runs inside SystemUI; Telecom enforces its host permissions and failure uses the action fallback.
    @android.annotation.SuppressLint("MissingPermission")
    private void endCall(boolean telecom, PendingIntent hangUp) {
        if (telecom) {
            try {
                TelecomManager telecomManager = ctx.getSystemService(TelecomManager.class);
                if (telecomManager != null && telecomManager.endCall()) {
                    XLog.i("call ended via telecom");
                    return;
                }
            } catch (Throwable t) {
                XLog.e("telecom endCall failed", t);
            }
        }
        if (hangUp == null) {
            return;
        }
        try {
            sendFromBackground(hangUp);
            XLog.i("call hang-up intent sent");
        } catch (Throwable t) {
            XLog.e("hang-up intent failed", t);
        }
    }

    private void toggleSpeaker(PendingIntent viaAction, boolean telecom) {
        try {
            if (telecom) {
                ctx.sendBroadcast(new Intent(SPEAKER_TOGGLE).setPackage(INCALLUI));
                XLog.i("speaker toggled via incallui");
            } else if (viaAction != null) {
                sendFromBackground(viaAction);
                XLog.i("speaker toggled via call action");
            }
        } catch (Throwable t) {
            XLog.e("speaker toggle failed", t);
        }
        afterSpeakerToggle.run();
    }

    private void sendFromBackground(PendingIntent intent) throws PendingIntent.CanceledException {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        intent.send(ctx, 0, null, null, null, null, options.toBundle());
    }
}
