package dev.android.dynamicisland.island.view;

import dev.android.dynamicisland.island.model.IslandItem;

/** Formats the clocks shown on the island ("3:07", "12:04:09", "01:30", hundredths for stopwatches). */
public final class TimeFormat {
    private TimeFormat() {
    }

    /** Elapsed / remaining time of a chronometer item, 0:00 style (hundredths for stopwatches). */
    public static String chronometer(IslandItem item) {
        long now = item.chronoUsesElapsedRealtime ? android.os.SystemClock.elapsedRealtime() : System.currentTimeMillis();
        long base = item.chronoUsesElapsedRealtime ? item.chronoElapsedBase : item.chronoBase;
        long ms = item.chronoCountDown ? base - now : now - base;
        if (item.chronoMillis) return withHundredths(ms);
        // a Clock timer that ran out counts up again behind a minus sign, as the Clock app shows it
        if (ms < 0 && item.chronoCountDown && item.clockKind == IslandItem.CLOCK_TIMER) return "-" + clock(-ms);
        return clock(Math.max(0L, ms));
    }

    /** Whether {@code item} is a Clock timer that already ran out. */
    public static boolean timerEnded(IslandItem item) {
        if (item.clockKind != IslandItem.CLOCK_TIMER) return false;
        long left = remainingMillis(item);
        return left != Long.MIN_VALUE && left < 0;
    }

    /**
     * Signed time left on a countdown (negative once it ran out), from the running chronometer or a frozen
     * clock text ("9:41", "1:02:03"); {@code Long.MIN_VALUE} when neither is there.
     */
    public static long remainingMillis(IslandItem item) {
        if (item.trailing == IslandItem.Trailing.CHRONOMETER && item.chronoCountDown) {
            boolean mono = item.chronoUsesElapsedRealtime;
            long now = mono ? android.os.SystemClock.elapsedRealtime() : System.currentTimeMillis();
            return (mono ? item.chronoElapsedBase : item.chronoBase) - now;
        }
        if (item.trailing == IslandItem.Trailing.TEXT && item.trailingText != null) {
            return parseClock(item.trailingText);
        }
        return Long.MIN_VALUE;
    }

    /**
     * "9:41" / "-0:12" / "1:02:03" / "1:05.37" to milliseconds; {@code Long.MIN_VALUE} for anything else.
     * Grammar {@code -?\d{1,2}(:\d{2}){1,2}(\.\d{2})?} after trimming, parsed without allocating: it runs on every frame.
     */
    public static long parseClock(CharSequence text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') start++;
        while (end > start && text.charAt(end - 1) <= ' ') end--;
        int i = start;
        boolean negative = i < end && text.charAt(i) == '-';
        if (negative) i++;
        int lead = 0;
        long seconds = 0;
        while (i < end && lead < 3 && isDigit(text.charAt(i))) {
            seconds = seconds * 10 + (text.charAt(i++) - '0');
            lead++;
        }
        if (lead < 1 || lead > 2) return Long.MIN_VALUE;
        int groups = 0;
        while (i < end && text.charAt(i) == ':') {
            if (i + 2 >= end || !isDigit(text.charAt(i + 1)) || !isDigit(text.charAt(i + 2))) return Long.MIN_VALUE;
            seconds = seconds * 60 + (text.charAt(i + 1) - '0') * 10 + (text.charAt(i + 2) - '0');
            i += 3;
            groups++;
        }
        if (groups < 1 || groups > 2) return Long.MIN_VALUE;
        long centis = 0;
        if (i < end && text.charAt(i) == '.') {
            if (i + 2 >= end || !isDigit(text.charAt(i + 1)) || !isDigit(text.charAt(i + 2))) return Long.MIN_VALUE;
            centis = (text.charAt(i + 1) - '0') * 10 + (text.charAt(i + 2) - '0');
            i += 3;
        }
        if (i != end) return Long.MIN_VALUE;
        long ms = seconds * 1000 + centis * 10;
        return negative ? -ms : ms;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A tick this long after a boundary surely sees the clock moved on. */
    private static final long TICK_SLACK_MS = 8;

    /**
     * Delay until just after {@code item}'s chronometer next changes its seconds, so a once-a-second redraw
     * lands right after the digit flips instead of at an unrelated phase (which shows the change late and
     * sometimes skips one, rolling two seconds in quick succession). Without a chronometer: the next wall
     * clock second.
     */
    public static long millisToNextSecond(IslandItem item) {
        if (item == null || item.chronoBase == 0) return 1000 - System.currentTimeMillis() % 1000 + TICK_SLACK_MS;
        boolean monotonic = item.chronoUsesElapsedRealtime;
        long now = monotonic ? android.os.SystemClock.elapsedRealtime() : System.currentTimeMillis();
        long base = monotonic ? item.chronoElapsedBase : item.chronoBase;
        return Math.floorMod(base - now, 1000L) + TICK_SLACK_MS;
    }

    /** "1:05.37": the clock plus hundredths of a second. */
    public static String withHundredths(long ms) {
        long clamped=Math.max(0L,ms);
        StringBuilder out=new StringBuilder(16);
        appendClock(out,clamped/1000);out.append('.');appendTwo(out,(clamped%1000)/10);
        return out.toString();
    }

    /** "01:30" below an hour (the seek bar's fixed-width times), "1:02:03" above. */
    public static String padded(long ms) {
        long totalSeconds=Math.max(0L,ms)/1000;
        if(totalSeconds>=3600)return clock(ms);
        StringBuilder out=new StringBuilder(8);
        appendTwo(out,(totalSeconds/60)%60);out.append(':');appendTwo(out,totalSeconds%60);
        return out.toString();
    }

    /** "3:07", or "1:02:03" from one hour on; negative times read "0:00". */
    public static String clock(long ms) {
        if(ms<0)return "0:00";
        StringBuilder out=new StringBuilder(16);appendClock(out,ms/1000);return out.toString();
    }

    private static void appendClock(StringBuilder out,long totalSeconds) {
        long hours=totalSeconds/3600,minutes=(totalSeconds/60)%60;
        if(hours>0){out.append(hours).append(':');appendTwo(out,minutes);}
        else out.append(minutes);
        out.append(':');appendTwo(out,totalSeconds%60);
    }

    private static void appendTwo(StringBuilder out,long value) {
        if(value<10)out.append('0');out.append(value);
    }
}
