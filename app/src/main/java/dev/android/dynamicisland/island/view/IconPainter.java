package dev.android.dynamicisland.island.view;

import static dev.android.dynamicisland.island.view.IslandView.clamp01;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;

/**
 * The small pieces the pill, the bubble and the cards share: the leading icon, the trailing text / ring /
 * waveform, progress rings and the segmented progress bar.
 */
final class IconPainter {
    private final android.graphics.Paint.FontMetrics fm = new android.graphics.Paint.FontMetrics();
    /** "key#compact" per item, so the running clock does not build a new string every frame. */
    private final java.util.HashMap<String, String> compactKeys = new java.util.HashMap<>();
    private static final ColorMatrixColorFilter GRAYSCALE = grayscale();

    private final Metrics m;
    private final Paints paints;
    private final Waveform waveform;
    private final RecordingWaveform recordingWaveform;
    private final dev.android.dynamicisland.island.graphics.OneUiBattery batteryCell = new dev.android.dynamicisland.island.graphics.OneUiBattery();
    private final DigitClock clock;
    private final MediaFxCache mediaFx;
    /** Scratch objects owned by the view; the card bodies share them. */
    private final RectF scratch;
    private final Path barClip;
    private final Path imageClip = new Path();

    IconPainter(Metrics m, Paints paints, Waveform waveform, RecordingWaveform recordingWaveform, DigitClock clock, MediaFxCache mediaFx, RectF scratch, Path barClip) {
        this.m = m;
        this.paints = paints;
        this.waveform = waveform;
        this.recordingWaveform = recordingWaveform;
        this.clock = clock;
        this.mediaFx = mediaFx;
        this.scratch = scratch;
        this.barClip = barClip;
    }

    private static ColorMatrixColorFilter grayscale() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0.0f);
        return new ColorMatrixColorFilter(matrix);
    }

    private String compactKey(String key) {
        String cached = compactKeys.get(key);
        if (cached == null) {
            if (compactKeys.size() > 32) compactKeys.clear();
            cached = key + "#compact";
            compactKeys.put(key, cached);
        }
        return cached;
    }

    /** Leading icon of the compact pill: monochrome unless the icon is artwork. */
    void drawLeadingMono(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        if (item.eventLayout == IslandItem.EventLayout.BATTERY && item.batteryPercent >= 0) {
            // One UI's battery: a horizontal cell filled in green, with the bolt while it charges
            batteryCell.drawCell(c, left + size / 2, midY, size * 1.15f, item.batteryPercent, item.fillAccent(), item.batteryCharging, alpha);
            return;
        }
        if (item.clockKind == IslandItem.CLOCK_TIMER && item.icon != null && !item.iconIsImage) {
            drawTimerSymbol(c, item, left, midY, size, alpha);
            return;
        }
        if (item.voiceRecording && ModuleSettings.concurrentRecordingInput()) {
            // like a call: the recording symbol on the left, in red; the live waveform goes on the right
            if (item.icon != null) drawDrawable(c, item.icon, item.iconIsImage, Colors.RECORDING_RED, left, midY - size / 2f, size, alpha, 0f);
            else drawLeading(c, item, left, midY, size, alpha);
            return;
        }
        if (item.pulsingDot || item.icon == null) {
            drawLeading(c, item, left, midY, size, alpha);
            return;
        }
        float top = midY - (size / 2.0f);
        if (!item.iconIsImage && ModuleSettings.colorIcons() && item.icon instanceof android.graphics.drawable.BitmapDrawable) {
            // a picture the app gave as a symbol (a team logo): tinting it would leave a flat white shape
            drawDrawable(c, item.icon, true, 0, left, top, size, alpha, size * 0.26f);
        } else if (!item.iconIsImage) {
            drawDrawable(c, item.icon, false, item.kind == IslandItem.Kind.CALL ? item.accent : item.leadingSymbolTint(), left, top, size, alpha, 0.0f);
        } else if (!item.isMedia) {
            if (item.kind == IslandItem.Kind.CALL && item.cardImageRound && item.icon == item.cardImage) {
                // a caller's photo: round and in colour, like the call screen
                drawDrawable(c, item.icon, true, 0, left, top, size, alpha, size / 2f);
            } else if (ModuleSettings.colorIcons()) {
                drawDrawable(c, item.icon, true, 0, left, top, size, alpha, size * 0.26f);
            } else {
                item.icon.setColorFilter(GRAYSCALE);
                drawDrawable(c, item.icon, true, 0, left, top, size, alpha, size * 0.26f);
                item.icon.setColorFilter(null);
            }
        } else if (item.cardImage != null) {
            // same flipping album art as the card, so the pill animates on track changes
            mediaFx.obtain(item).drawArt(c, left, top, size, alpha, size * 0.26f, IslandView.now());
        } else {
            drawLeading(c, item, left, midY, size, alpha);
        }
    }

    /** The timer symbol inside a ring of the time left: emptying as it runs, all red once it ran out. */
    private void drawTimerSymbol(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        float cx = left + size / 2.0f;
        long leftMs = TimeFormat.remainingMillis(item);
        boolean over = leftMs != Long.MIN_VALUE && leftMs < 0;
        float frac = over ? 1.0f : leftMs == Long.MIN_VALUE || item.timerTotalMs <= 0 ? -1.0f
                : clamp01(leftMs / (float) item.timerTotalMs);
        float strokeW = m.dp(2.2f);
        if (frac >= 0.0f) {
            Paint stroke = paints.stroke;
            stroke.setStrokeWidth(strokeW);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            float rr = size / 2.0f - strokeW / 2.0f;
            scratch.set(cx - rr, midY - rr, cx + rr, midY + rr);
            stroke.setColor(Colors.withAlpha(Colors.CARD_STROKE, alpha));
            c.drawArc(scratch, 0.0f, 360.0f, false, stroke);
            if (frac > 0.0f) {
                stroke.setColor(Colors.withAlpha(over ? Colors.TIMER_ENDED : item.symbolTint(), alpha));
                c.drawArc(scratch, -90.0f, frac * 360.0f, false, stroke);
            }
        }
        float glyph = frac >= 0.0f ? size * 0.56f : size;
        drawDrawable(c, item.icon, false, over ? Colors.TIMER_ENDED : item.symbolTint(), cx - glyph / 2.0f, midY - glyph / 2.0f, glyph, alpha, 0.0f);
    }

    /** Red of a Clock timer that ran out (the Clock card's alert colour). */

    void drawLeading(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        Paint fill = paints.fill;
        if (item.pulsingDot) {
            float phase = (SystemClock.uptimeMillis() % 1100) / 1100.0f;
            float breathe = (((((float) Math.cos(phase * 3.141592653589793d * 2.0d)) * 0.5f) + 0.5f) * 0.45f) + 0.55f;
            fill.setColor(Colors.withAlpha(Colors.DESTRUCTIVE_RED, alpha * breathe));
            c.drawCircle((size / 2.0f) + left, midY, 0.32f * size, fill);
        } else if (item.icon == null) {
            fill.setColor(Colors.withAlpha(item.symbolTint(), alpha));
            c.drawCircle((size / 2.0f) + left, midY, 0.3f * size, fill);
        } else {
            drawDrawable(c, item.icon, item.iconIsImage, item.leadingSymbolTint(), left, midY - (size / 2.0f), size, alpha, size * 0.26f);
        }
    }

    /** Draws in a square slot: artwork is centre-cropped; tinted icons fit inside without stretching. */
    /** {@code item}'s trailing icon centred on (cx, cy) in a {@code size} slot, at its own tint and scale. */
    void drawTrailingIcon(Canvas c, IslandItem item, float cx, float cy, float size, float alpha) {
        float s = size * item.trailingIconScale;
        drawDrawable(c, item.trailingIcon, false, item.trailingTint(), cx - s / 2.0f, cy - s / 2.0f, s, alpha, 0);
    }

    void drawDrawable(Canvas c, Drawable d, boolean image, int tint, float left, float top, float size, float alpha, float corner) {
        int l = Math.round(left);
        int t = Math.round(top);
        int sz = Math.round(size);
        // VectorDrawable records its shared renderer into a hardware display list. Mutating its
        // alpha for a second representation can also fade the first one (e.g. pill and bubble).
        // Keep the drawable opaque and composite each occurrence's opacity on the canvas instead.
        d.setAlpha(255);
        int opacity = alpha < .999f ? c.saveLayerAlpha(l, t, l + sz, t + sz,
                Math.round(255.0f * clamp01(alpha))) : c.save();
        try {
            if (!image) {
                centerInside(d, l, t, sz, bounds);
                d.setBounds(bounds);
                tint(d, false, tint);
                d.draw(c);
                return;
            }
            int save = c.save();
            scratch.set(l, t, l + sz, t + sz);
            imageClip.rewind();
            imageClip.addRoundRect(scratch, corner, corner, Path.Direction.CW);
            c.clipPath(imageClip);
            centerCrop(d, l, t, sz, bounds);
            d.setBounds(bounds);
            tint(d, true, 0);
            d.draw(c);
            c.restoreToCount(save);
        } finally { c.restoreToCount(opacity); }
    }

    /** Reused bounds: drawDrawable runs for every icon of every frame. Main thread only. */
    private final android.graphics.Rect bounds = new android.graphics.Rect();
    /** Last tint given to each drawable (drawDrawable is the only place that tints them): {colour, 1 = no tint}. */
    private final java.util.WeakHashMap<Drawable, int[]> tints = new java.util.WeakHashMap<>();

    /**
     * setTint on a VectorDrawable rebuilds its tint filter and invalidates it even when the colour is the same:
     * skip the call when this drawable already has that tint. No boxing: this runs every frame.
     */
    private void tint(Drawable d, boolean none, int color) {
        int[] last = tints.get(d);
        if (last != null && last[1] == (none ? 1 : 0) && (none || last[0] == color)) return;
        if (none) d.setTintList(null); else d.setTint(color);
        if (last == null) tints.put(d, last = new int[2]);
        last[0] = color;
        last[1] = none ? 1 : 0;
    }

    /** Fits a symbol inside its slot, preserving its intrinsic aspect ratio and centring both axes. */
    private static void centerInside(Drawable d, int l, int t, int sz, android.graphics.Rect out) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (sz <= 0 || iw <= 0 || ih <= 0 || iw == ih) {
            out.set(l, t, l + sz, t + sz);
            return;
        }
        float scale = Math.min(sz / (float) iw, sz / (float) ih);
        int width = Math.max(1, Math.round(iw * scale));
        int height = Math.max(1, Math.round(ih * scale));
        int left = l + ((sz - width) / 2);
        int top = t + ((sz - height) / 2);
        out.set(left, top, left + width, top + height);
    }

    /**
     * Bounds that fit {@code d} into the square {@code sz} at ({@code l}, {@code t}) like ImageView's
     * CENTER_CROP, so non 1:1 artwork keeps its aspect ratio (the caller clips the overflow).
     */
    private static void centerCrop(Drawable d, int l, int t, int sz, android.graphics.Rect out) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0 || iw == ih) {
            out.set(l, t, l + sz, t + sz);
            return;
        }
        float scale = Math.max(sz / (float) iw, sz / (float) ih);
        int dw = Math.round(iw * scale);
        int dh = Math.round(ih * scale);
        int dl = l + ((sz - dw) / 2);
        int dt = t + ((sz - dh) / 2);
        out.set(dl, dt, dl + dw, dt + dh);
    }

    float trailingWidth(IslandItem item, String text) {
        if (item.voiceRecording && ModuleSettings.concurrentRecordingInput()) return waveform.width();
        switch (item.trailing) {
            case TEXT:
            case CHRONOMETER:
                return text == null ? 0.0f : DigitClock.width(paints.trail, text);
            case PROGRESS_RING:
                return m.ringSize;
            case WAVEFORM:
                return waveform.width();
            case ICON:
                return item.trailingIcon == null ? 0.0f : m.leadSize;
            default:
                return 0.0f;
        }
    }

    void drawTrailing(Canvas c, IslandItem item, String text, float right, float midY, float w, float alpha) {
        if (item.voiceRecording && ModuleSettings.concurrentRecordingInput()) {
            recordingWaveform.draw(c, item, right - w, midY, w, m.leadSize * .85f, alpha);
            return;
        }
        switch (item.trailing) {
            case TEXT:
            case CHRONOMETER:
                if (text != null) {
                    paints.trail.getFontMetrics(fm);
                    paints.trail.setColor(Colors.withAlpha(item.kind == IslandItem.Kind.CALL ? item.accent
                            : TimeFormat.timerEnded(item) ? Colors.TIMER_ENDED : -1, alpha));
                    float baseline = midY - ((fm.ascent + fm.descent) / 2.0f);
                    float natural = DigitClock.width(paints.trail, text);
                    int save = c.save();
                    if (DigitClock.isRollable(text) && w > 0 && natural > w) c.scale(w / natural, w / natural, right, midY);
                    clock.draw(c, compactKey(item.key), text, paints.trail, right - natural, baseline, item.chronoCountDown);
                    c.restoreToCount(save);
                }
                break;
            case PROGRESS_RING:
                drawRing(c, right - (m.ringSize / 2.0f), midY, m.ringSize / 2.0f, item.progress, -1, alpha);
                break;
            case WAVEFORM:
                if (item.isMedia) {
                    waveform.draw(c, scratch, right - w, midY, mediaFx.obtain(item), alpha, IslandView.now());
                } else {
                    waveform.draw(c, scratch, right - w, midY, item.accent, alpha);
                }
                break;
            case ICON:
                if (item.trailingIcon != null && w > 0) {
                    float size = Math.min(w, m.leadSize);
                    drawTrailingIcon(c, item, right - size / 2.0f, midY, size, alpha);
                }
                break;
            default:
                break;
        }
    }

    /** A ring that fills clockwise with {@code progress}, or a spinning arc when the progress is negative. */
    void drawRing(Canvas c, float x, float y, float radius, float progress, int color, float alpha) {
        float strokeW = m.dp(2.6f);
        Paint stroke = paints.stroke;
        stroke.setStrokeWidth(strokeW);
        float rr = radius - (strokeW / 2.0f);
        scratch.set(x - rr, y - rr, x + rr, y + rr);
        stroke.setColor(Colors.withAlpha(Colors.CARD_STROKE, alpha));
        c.drawArc(scratch, 0.0f, 360.0f, false, stroke);
        stroke.setColor(Colors.withAlpha(color, alpha));
        boolean spinning = progress < 0.0f;
        float sweep = spinning ? 90.0f : clamp01(progress) * 360.0f;
        float start = spinning ? ((SystemClock.uptimeMillis() % 1200) / 1200.0f) * 360.0f : -90.0f;
        c.drawArc(scratch, start, Math.max(sweep, 4.0f), false, stroke);
    }

    /**
     * Fills [l, r] of a bar row in {@code paint}: solid, or as round dashes (a walking leg). Dashes
     * start at {@code anchor} so the pattern does not shift where the elapsed part ends.
     */
    void drawSpan(Canvas c, float l, float r, float anchor, float top, float h, boolean dashed, Paint paint) {
        if (r <= l) return;
        if (!dashed) {
            c.drawRect(l, top, r, top + h, paint);
            return;
        }
        float dash = Math.max(h, m.dp(DASH_DP));
        float period = dash + m.dp(DASH_GAP_DP);
        int save = c.save();
        c.clipRect(l, top, r, top + h);
        for (float d = anchor + (float) Math.floor((l - anchor) / period) * period; d < r; d += period) {
            scratch.set(d, top, d + dash, top + h);
            c.drawRoundRect(scratch, h / 2.0f, h / 2.0f, paint);
        }
        c.restoreToCount(save);
    }

    private static final float DASH_DP = 5.0f;
    private static final float DASH_GAP_DP = 3.0f;

    /**
     * Android 16 ProgressStyle bar: coloured segments with small gaps; the part behind the progress
     * keeps its colour, the rest is faded. Milestone points and the tracker sit on top.
     */
    void drawSegmentedBar(Canvas c, IslandItem it, float left, float right, float top, float boxH, float alpha) {
        Paint fill = paints.fill;
        float width = right - left;
        float barH = m.dp(8.0f);
        float barTop = top + ((boxH - barH) / 2.0f);
        float midY = barTop + (barH / 2.0f);
        float progress = clamp01(it.progress);
        float fillX = left + (width * progress);
        int n = it.progressSegmentColors.length;
        float gap = m.dp(2.0f);
        scratch.set(left, barTop, right, barTop + barH);
        int save = c.save();
        barClip.rewind();
        barClip.addRoundRect(scratch, barH / 2.0f, barH / 2.0f, Path.Direction.CW);
        c.clipPath(barClip);
        for (int i = 0; i < n; i++) {
            float segStart = left + (width * it.progressSegmentStarts[i]) + (i == 0 ? 0.0f : gap / 2.0f);
            float segEnd = i + 1 < n ? left + (width * it.progressSegmentStarts[i + 1]) - (gap / 2.0f) : right;
            int color = it.progressSegmentColors[i] | Colors.BLACK;
            // elapsed part in full colour, remainder faded when the style says so
            float elapsedEnd = Math.min(segEnd, Math.max(segStart, fillX));
            boolean dashed = it.segmentDashed(i);
            if (elapsedEnd > segStart) {
                fill.setColor(Colors.withAlpha(color, alpha));
                drawSpan(c, segStart, elapsedEnd, segStart, barTop, barH, dashed, fill);
            }
            if (segEnd > elapsedEnd) {
                fill.setColor(Colors.withAlpha(color, alpha * (it.progressStyledByProgress ? 0.32f : 1.0f)));
                drawSpan(c, elapsedEnd, segEnd, segStart, barTop, barH, dashed, fill);
            }
        }
        c.restoreToCount(save);
        if (it.progressPointPositions != null) {
            float pointR = barH * 0.34f;
            for (int i = 0; i < it.progressPointPositions.length; i++) {
                float px = left + (width * it.progressPointPositions[i]);
                int pointColor = it.progressPointColors[i] != 0 ? (it.progressPointColors[i] | Colors.BLACK) : -1;
                fill.setColor(Colors.withAlpha(Colors.BLACK, alpha * 0.55f));
                c.drawCircle(px, midY, pointR + m.dp(1.2f), fill);
                fill.setColor(Colors.withAlpha(pointColor, alpha));
                c.drawCircle(px, midY, pointR, fill);
            }
        }
        if (it.progressThumb != null) {
            float thumb = m.dp(20.0f);
            float tx = Math.max(left, Math.min(right - thumb, fillX - (thumb / 2.0f)));
            drawDrawable(c, it.progressThumb, true, 0, tx, top + ((boxH - thumb) / 2.0f), thumb, alpha, thumb / 2.0f);
        } else if (progress > 0.0f && progress < 1.0f) {
            // no tracker icon: a small dot keeps the current position readable against the faded rest
            fill.setColor(Colors.withAlpha(Colors.WHITE, alpha));
            c.drawCircle(fillX, midY, barH * 0.62f, fill);
        }
    }
}
