package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/** Reference layout with the island's existing action colours. */
public final class RecordingBody extends CardBody {
    private final Paint time;
    private final float pad;
    private final float controlsLeft;

    public static boolean supports(IslandItem item) {
        boolean toggle = false, stop = false;
        for (IslandItem.Action action : item.actions) {
            toggle |= action.recordingControl == 1 || action.recordingControl == 2;
            stop |= action.recordingControl == 3;
        }
        return toggle && stop;
    }

    public RecordingBody(IslandView owner, IslandItem item, float width) {
        super(owner, item, width);
        h = owner.dp(88);
        pad = owner.dp(20);
        time = new Paint(owner.bigTimePaint);
        float diameter = owner.dp(width < owner.dp(330) ? 48 : 52);
        float gap = owner.dp(12);
        float right = width - pad;
        IslandItem.Action toggle = null, stop = null;
        for (IslandItem.Action action : item.actions) {
            if (action.recordingControl == 1 || action.recordingControl == 2) toggle = action;
            if (action.recordingControl == 3) stop = action;
        }
        add(stop, right - diameter, diameter, 6);
        right -= diameter + gap;
        add(toggle, right - diameter, diameter, toggle != null && toggle.recordingControl == 2 ? Glyphs.PLAY : Glyphs.PAUSE);
        controlsLeft = right - diameter;
    }

    private void add(IslandItem.Action action, float left, float diameter, int glyph) {
        if (action == null) return;
        Btn button = new Btn();
        button.action = action;
        button.circle = true;
        button.glyph = glyph;
        // Same precedence as TimerBody, including the user's accent on a primary action.
        button.bg = action.destructive ? Colors.DESTRUCTIVE_RED
                : action.glyph != 0 ? (action.active ? Colors.WHITE : Colors.BUTTON_GRAY)
                : item.actions.indexOf(action) == 0 ? item.primaryButtonFill(IslandView.PAUSED_SPEED)
                : Colors.BUTTON_GRAY;
        button.label = action.title == null ? null : action.title.toString();
        button.r.set(left, (h - diameter) / 2f, left + diameter, (h + diameter) / 2f);
        buttons.add(button);
    }

    /** The buttons, the tallest thing in the row, leave this much free above them. */
    @Override protected float bandOverlap() {
        float tallest = 0;
        for (Btn b : buttons) tallest = Math.max(tallest, b.r.height());
        return Math.max(0, (h - Math.max(tallest, owner.dp(38))) / 2f - owner.dp(4));
    }

    /** Height of the time + buttons row when it sits under the hole. */
    private float rowShift;

    /**
     * Like the call card: the recording symbol left of the hole, the live waveform right of it, and the
     * time with pause / stop under the hole. Nothing of the compact pill is drawn twice.
     */
    @Override protected float layoutBesideHole() {
        if (leftLane(pad) < owner.dp(28) || rightLane(pad) < owner.dp(40)) return -1.0f;
        // the buttons (18dp from the row's top) start just under the hole
        rowShift = Math.max(0f, holeBottom - owner.dp(10));
        shiftButtons(rowShift);
        shadeFade = owner.dp(16);
        return h + rowShift;
    }

    @Override protected void onClearHole() {
        rowShift = 0f;
    }

    private static final int RED = dev.android.dynamicisland.island.graphics.Colors.RECORDING_RED;

    @Override public void draw(Canvas canvas, float x, float y, float alpha, long elapsed) {
        float a = alpha * rowAlpha(elapsed, 0);
        String value = owner.trailingString(item);
        if (value == null) value = item.trailingText == null ? "0:00" : item.trailingText.toString();
        time.setTextSize(owner.dp(32));
        time.setColor(Colors.withAlpha(RED, a));
        Paint.FontMetrics fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(time);
        if (holeRow) {
            // hole row: symbol on the left, waveform on the right
            float symbol = owner.dp(22);
            float mid = y + holeMid;
            if (item.icon != null) {
                owner.drawDrawable(canvas, item.icon, item.iconIsImage, RED, x + pad, mid - symbol / 2f, symbol, a, 0f);
            }
            float waveWidth = Math.min(owner.dp(92), rightLane(pad) - owner.dp(6));
            owner.drawRecordingWaveform(canvas, item, x + w - pad - waveWidth, mid, waveWidth, owner.dp(26), a);
            // under the hole: the time, left of the buttons
            float middle = y + rowShift + (h - rowShift) / 2f;
            float room = controlsLeft - pad - owner.dp(12);
            float clockWidth = time.measureText(value);
            if (clockWidth > room) time.setTextSize(time.getTextSize() * room / clockWidth);
            fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(time);
            owner.drawTime(canvas, timeKey(0, "#voice"), value, time, x + pad, middle - (fm.ascent + fm.descent) / 2f, false);
            drawButtons(canvas, x, y, a);
            return;
        }
        float middle = y + (h - topInset) / 2f; // same centre as the buttons, whose rects ignore the band
        float usable = controlsLeft - pad - owner.dp(12);
        float clockWidth = time.measureText(value);
        float waveWidth = Math.max(owner.dp(24), Math.min(owner.dp(92), usable - clockWidth - owner.dp(14)));
        float clockRoom = Math.max(owner.dp(32), usable - waveWidth - owner.dp(12));
        if (clockWidth > clockRoom) time.setTextSize(time.getTextSize() * clockRoom / clockWidth);
        fm = dev.android.dynamicisland.island.graphics.Fonts.metrics(time);
        // the time on the left, the waveform next to the buttons
        owner.drawTime(canvas, timeKey(0, "#voice"), value, time, x + pad, middle - (fm.ascent + fm.descent) / 2f, false);
        owner.drawRecordingWaveform(canvas, item, x + controlsLeft - owner.dp(12) - waveWidth, middle, waveWidth, owner.dp(38), a);
        drawButtons(canvas, x, y, a);
    }
}
