package dev.android.dynamicisland.island.view;

import static dev.android.dynamicisland.island.view.IslandView.clamp01;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Spring;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.media.MediaFx;
import java.util.Random;
import dev.android.dynamicisland.settings.ModuleSettings;

/**
 * Small now-playing waveform at the right of the pill: six thin capsules in a single colour taken from the
 * artwork, taller in the middle and shorter at the edges. With live audio each bar follows a frequency band;
 * without it the bars drift smoothly. When paused they shrink to small dots.
 */
final class Waveform {
    static final int BARS = 6;
    /** Height envelope: the middle bars reach higher than the outer ones. */
    private static final float[] ENVELOPE = {.52f, .82f, 1f, .94f, .76f, .5f};
    private static final float[] STATIC_LEVELS = {.35f, .7f, .55f, .9f, .45f, .3f};
    /** Audio band driving each bar, left to right (low frequencies in the middle). */
    private static final int[] BAND_ORDER = {3, 1, 0, 0, 2, 4};
    private static final float MAX_HEIGHT_DP = 16.0f;
    /** Paused height: equal to the bar width, so each bar becomes a dot. */
    private static final float MIN_HEIGHT_FACTOR = 1.0f;
    /** Time constant in seconds smoothed levels follow raw ones. */
    private static final float LEVEL_SMOOTHING_S = 0.07f;

    private final Metrics m;
    private final Spring[] bars = new Spring[BARS];
    private final float[] level = new float[BARS];
    private final Random random = new Random();
    /** Waveforms never mutate the shared fill paint. */
    private final Paint flatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Waveform(Metrics m) {
        this.m = m;
        for (int i = 0; i < BARS; i++) {
            bars[i] = new Spring(0.2f, 0.3f, 1.0f).setValueThreshold(0.003f);
        }
    }

    float width() {
        return (BARS * m.waveBarW) + ((BARS - 1) * m.waveGap);
    }

    /**
     * Advances the bars for the playing/paused {@code item}.
     *
     * @return true while the display needs more frames
     */
    boolean step(float dt, IslandItem item, AudioLevels audio) {
        if (ModuleSettings.staticWaveform()) return false;
        boolean live = item.playing && ModuleSettings.visualizer() && audio != null && audio.isLive();
        float k = 1.0f - ((float) Math.exp((-dt) / LEVEL_SMOOTHING_S));
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            Spring bar = bars[i];
            if (live) {
                followAudio(i, bar, audio, k);
            } else if (!item.playing) {
                bar.animateTo(0.0f, 0.35f, 1.0f);
            } else if (bar.isAtRest() || Math.abs(bar.value() - bar.target()) < 0.04f) {
                // smooth, unhurried drift with no overshoot
                bar.animateTo((random.nextFloat() * 0.7f) + 0.25f, (random.nextFloat() * 0.18f) + 0.26f, 1.0f);
            }
            moving |= bar.step(dt);
        }
        return moving | item.playing;
    }

    private void followAudio(int index, Spring bar, AudioLevels audio, float k) {
        float raw = audio.level(Math.min(BAND_ORDER[index], AudioLevels.BANDS - 1));
        level[index] += (raw - level[index]) * k;
        float target = (level[index] * 0.85f) + 0.15f;
        if (target > bar.value()) {
            bar.animateTo(target, 0.11f, 0.9f);
        } else {
            bar.animateTo(target, 0.26f, 1.0f);
        }
    }

    void draw(Canvas c, RectF scratch, float left, float midY, int color, float alpha) {
        flatPaint.setShader(null);
        flatPaint.setColor(Colors.withAlpha(Colors.readableOnBlack(color), alpha));
        drawBars(c, flatPaint, scratch, left, midY);
    }

    /** Music: one flat colour from the middle of the artwork palette, lifted so it reads on the black pill. */
    void draw(Canvas c, RectF scratch, float left, float midY, MediaFx fx, float alpha, long now) {
        draw(c, scratch, left, midY, fx.waveformColorAt(0.5f, now), alpha);
    }

    private void drawBars(Canvas c, Paint paint, RectF scratch, float left, float midY) {
        float maxH = m.dp(MAX_HEIGHT_DP);
        float minH = m.waveBarW * MIN_HEIGHT_FACTOR;
        float radius = m.waveBarW / 2.0f;
        float x = left;
        for (int i = 0; i < BARS; i++) {
            float value = ModuleSettings.staticWaveform() ? STATIC_LEVELS[i] : bars[i].value();
            float h = minH + ((maxH - minH) * ENVELOPE[i] * clamp01(value));
            scratch.set(x, midY - (h / 2.0f), m.waveBarW + x, midY + (h / 2.0f));
            c.drawRoundRect(scratch, radius, radius, paint);
            x += m.waveBarW + m.waveGap;
        }
    }
}
