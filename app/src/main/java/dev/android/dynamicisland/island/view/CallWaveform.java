package dev.android.dynamicisland.island.view;

import static dev.android.dynamicisland.island.view.IslandView.clamp01;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import dev.android.dynamicisland.island.feedback.CallAudio;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Spring;
import java.util.Random;

/**
 * Green voice bars beside an ongoing call, like the music waveform but driven by the loudness of the call:
 * every bar follows the same level with its own share of it, so speech ripples across them. Without a live level
 * the bars breathe slowly at a low height.
 */
final class CallWaveform {
    static final int BARS = 5;
    private static final float[] ENVELOPE = {.55f, .85f, 1f, .85f, .55f};
    private static final float MAX_HEIGHT_DP = 15f;

    private final Metrics m;
    private final Spring[] bars = new Spring[BARS];
    private final float[] share = new float[BARS];
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float smooth;

    CallWaveform(Metrics m) {
        this.m = m;
        for (int i = 0; i < BARS; i++) {
            bars[i] = new Spring(0.2f, 0.3f, 1.0f).setValueThreshold(0.003f);
            share[i] = .6f + random.nextFloat() * .4f;
        }
    }

    float width() {
        return BARS * m.waveBarW + (BARS - 1) * m.waveGap;
    }

    /** Advances the bars; true while frames are needed. */
    boolean step(float dt, CallAudio audio) {
        boolean live = audio != null && audio.isLive();
        float raw = live ? audio.level() : 0f;
        float k = 1f - (float) Math.exp(-dt / 0.06f);
        smooth += (raw - smooth) * k;
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            Spring bar = bars[i];
            if (live) {
                if (Math.abs(bar.value() - bar.target()) < 0.05f) share[i] = .55f + random.nextFloat() * .45f;
                float target = clamp01(smooth * share[i] * 1.25f);
                bar.animateTo(target, target > bar.value() ? 0.1f : 0.24f, 0.9f);
            } else if (bar.isAtRest() || Math.abs(bar.value() - bar.target()) < 0.03f) {
                bar.animateTo(.08f + random.nextFloat() * .22f, .5f + random.nextFloat() * .3f, 1f);
            }
            moving |= bar.step(dt);
        }
        return true;
    }

    void draw(Canvas c, RectF scratch, float left, float midY, int color, float alpha) {
        paint.setColor(Colors.withAlpha(color, alpha));
        float maxH = m.dp(MAX_HEIGHT_DP);
        float minH = m.waveBarW;
        float radius = m.waveBarW / 2f;
        float x = left;
        for (int i = 0; i < BARS; i++) {
            float h = minH + (maxH - minH) * ENVELOPE[i] * clamp01(bars[i].value());
            scratch.set(x, midY - h / 2f, x + m.waveBarW, midY + h / 2f);
            c.drawRoundRect(scratch, radius, radius, paint);
            x += m.waveBarW + m.waveGap;
        }
    }
}
