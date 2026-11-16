package dev.android.dynamicisland.island.source;

/**
 * Broadcast protocol between apps whose own alert pop-ups the island takes over (Samsung Clock's "timer ended"
 * window, EasySetup's "connect your earbuds" sheet) and System UI.
 *
 * <p>The app keeps its pop-up, made invisible and untouchable, and sends what it shows ({@link #ACTION_ALERT}).
 * System UI opens a card with the same buttons and answers {@link #CMD_ACK}; without that answer the app puts its
 * own pop-up back, so a hidden or busy island never leaves the user without the buttons. A tap on the card's button
 * presses the pop-up's own button ({@link #CMD_CLICK}), so the app does exactly what it would do.
 */
public final class AlertBridge {
    /** App → System UI: an alert is up ({@link #EXTRA_TITLE}, ...) or went away ({@link #EXTRA_GONE}). */
    public static final String ACTION_ALERT = "dev.android.dynamicisland.ALERT";
    /** System UI → app: {@link #EXTRA_COMMAND} for alert {@link #EXTRA_ID}. */
    public static final String ACTION_COMMAND = "dev.android.dynamicisland.ALERT_COMMAND";
    /** Signature|privileged: held by System UI, Clock and EasySetup, not by ordinary apps. */
    public static final String PERMISSION = "android.permission.INTERNAL_SYSTEM_WINDOW";

    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    /** Labels of the pop-up's buttons, in screen order. */
    public static final String EXTRA_BUTTONS = "buttons";
    public static final String EXTRA_GONE = "gone";
    public static final String EXTRA_COMMAND = "command";
    /** Index into {@link #EXTRA_BUTTONS} for {@link #CMD_CLICK}. */
    public static final String EXTRA_INDEX = "index";

    public static final String CMD_ACK = "ack";
    public static final String CMD_CLICK = "click";
    /** The island cannot show it any more: the app shows its own pop-up again. */
    public static final String CMD_RESTORE = "restore";

    /** Samsung Clock's window when a timer ends. */
    public static final String SOURCE_TIMER = "timer";
    /** EasySetup's sheet offering to connect earbuds found nearby. */
    public static final String SOURCE_BUDS = "buds";
    /** Samsung Auto Switch (MCFDs): earbuds moved to / back from another device, with Reconnect. */
    public static final String SOURCE_AUTOSWITCH = "autoswitch";

    private AlertBridge() {
    }

    /** "-00:00:03", "12:04", "1:02:03": a running clock, not a label. */
    public static boolean isClockText(CharSequence text) {
        return text != null && text.toString().trim().matches("[-−+]?\\d{1,2}(:\\d{2}){1,2}");
    }
}
