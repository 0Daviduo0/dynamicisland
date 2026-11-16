package dev.android.dynamicisland.island.model;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a turn-by-turn notification says, pulled apart the way iOS shows a route: the distance to the next
 * manoeuvre, the instruction, the road it leads onto (drawn bold), and the arrival time. Google Maps sends
 * the instruction as its title, the distance as {@code shortCriticalText}, "towards Road" as the chip text and
 * "Arrival: 13:01" as the sub text; other apps put the distance in the title and the instruction in the text.
 */
public final class NavFacts {
    private static final Pattern DISTANCE = Pattern.compile(
            "^\\s*\\d+(?:[.,]\\d+)?\\s?(?:m|km|ft|mi|yd|metri|miles?|feet)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})[:.](\\d{2})\\s*([AaPp]\\.?\\s?[Mm]\\.?)?");

    /** Distance to the next manoeuvre ("300 m"), or null. */
    public CharSequence distance;
    /** The instruction ("Turn right onto Church St"); never null once applied. */
    public CharSequence instruction;
    /** Where in {@link #instruction} the road name starts and ends, or -1. */
    public int roadStart = -1, roadEnd = -1;
    /** The arrival line as the app wrote it ("Arrival: 13:01"), or null. */
    public CharSequence arrival;
    /** Wall-clock time of arrival in ms, or 0 when the app gave none. */
    public long arriveAt;

    /**
     * @param title    notification title
     * @param text     notification text
     * @param subText  notification sub text (the arrival line in Google Maps)
     * @param critical {@code shortCriticalText}: the distance in Google Maps
     * @param chip     the chip text ("towards Road"), used to find the road in the instruction
     * @param now      current wall-clock time, to place the arrival time on the right day
     */
    public static NavFacts of(CharSequence title, CharSequence text, CharSequence subText, CharSequence critical,
            CharSequence chip, long now) {
        NavFacts f = new NavFacts();
        CharSequence instruction = title;
        if (filled(critical)) {
            f.distance = critical.toString().trim();
        } else if (filled(title) && DISTANCE.matcher(title).matches() && filled(text)) {
            f.distance = title.toString().trim();
            instruction = text;
        } else if (filled(text) && DISTANCE.matcher(text).matches()) {
            f.distance = text.toString().trim();
        }
        if (!filled(instruction)) instruction = filled(text) && f.distance != text ? text : chip;
        f.instruction = instruction == null ? "" : instruction;
        f.findRoad(chip);
        CharSequence arrival = filled(subText) ? subText : null;
        if (arrival == null && filled(text) && instruction != text && f.distance != text && CLOCK.matcher(text).find()) {
            arrival = text;
        }
        if (arrival != null) {
            f.arrival = arrival.toString().trim();
            f.arriveAt = clockAfter(f.arrival, now);
        }
        return f;
    }

    /** Whole minutes left until {@link #arriveAt} ("12 min", "1 h 05"), or null. */
    public String minutesLeft(long now) {
        if (arriveAt <= 0) return null;
        long minutes = Math.max(0, (arriveAt - now + 30_000) / 60_000);
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h " + (minutes % 60 < 10 ? "0" : "") + (minutes % 60);
    }

    /** Marks the road the chip names inside the instruction: the whole chip text, else the chip minus its first word. */
    private void findRoad(CharSequence chip) {
        if (!filled(chip)) return;
        String hay = instruction.toString();
        String needle = chip.toString().trim();
        int at = hay.indexOf(needle);
        if (at < 0) {
            int space = needle.indexOf(' ');
            if (space > 0 && space < needle.length() - 1) {
                needle = needle.substring(space + 1).trim();
                at = hay.indexOf(needle);
            }
        }
        if (at >= 0 && needle.length() > 1) {
            roadStart = at;
            roadEnd = at + needle.length();
        }
    }

    /** The first clock time in {@code text} as the next moment it occurs after {@code now}; 0 when there is none. */
    static long clockAfter(CharSequence text, long now) {
        Matcher m = CLOCK.matcher(text);
        if (!m.find()) return 0;
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        String half = m.group(3);
        if (half != null) {
            boolean pm = Character.toLowerCase(half.charAt(0)) == 'p';
            hour = hour % 12 + (pm ? 12 : 0);
        }
        if (hour > 23 || minute > 59) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // a time a little in the past is a late arrival today, not tomorrow
        if (c.getTimeInMillis() < now - 30 * 60_000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static boolean filled(CharSequence s) {
        return s != null && s.toString().trim().length() > 0;
    }
}
