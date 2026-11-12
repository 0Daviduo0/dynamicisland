package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Glyphs;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.island.view.media.MediaFx;

/**
 * Music player card modelled on the One UI 9 stock player ({@code SecMediaControlPanel}, notification shade):
 * the cover art fills the card behind a dark scrim and everything is centred over it.
 *
 * <pre>
 * +----------------------------------------------+
 * |  (o) Buds3 Pro                         (app) |   output device (or app name), app icon
 * |        (s) Title  •  Artist                  |   one centred line
 * |  ~~~~~~~~~~~~~~~~~~~~~~~~~~O-------          |   accent bar with a live wave riding on it, ringed thumb
 * |  02:16                                02:36  |
 * |      (c1)   (|<)   (||)   (>|)   (c2)        |   translucent round prev / play / next, bare customs
 * +----------------------------------------------+
 * </pre>
 *
 * Measured on One UI 9 (384 dpi): card 150dp high, seek bar 74dp from the top, controls centred 119dp from the
 * top, prev / play / next 42dp circles 60dp apart, the custom actions 120dp from the centre.
 * The seek bar can be dragged ({@link #onSeekBar}, {@link #fractionAt}); while dragging, the view keeps this
 * body in {@code owner.seekBody} and the dragged position is shown instead of the playback position.
 */
public final class MediaBody extends CardBody {
    private static final float HEIGHT_DP = 150.0f;
    private static final float SIDE_PAD_DP = 26.0f;
    private static final float TOP_ROW_DP = 25.0f;
    private static final float TITLE_ROW_DP = 49.0f;
    private static final float SEEK_ROW_DP = 76.0f;
    private static final float TIME_ROW_DP = 92.0f;
    private static final float CONTROLS_ROW_DP = 121.0f;
    private static final float BAR_DP = 4.0f;
    private static final float BAR_DRAGGED_DP = 6.0f;
    private static final float THUMB_R_DP = 7.5f;
    private static final float THUMB_RING_DP = 2.5f;
    private static final float THUMB_GAP_DP = 5.0f;
    /** Tallest the wave rises above the bar. */
    private static final float WAVE_DP = 13.0f;
    private static final float BUTTON_DP = 42.0f;
    private static final float BUTTON_STEP_DP = 60.0f;
    private static final float CUSTOM_STEP_DP = 120.0f;
    private static final float CUSTOM_DP = 38.0f;
    private static final float OUTPUT_ICON_DP = 14.0f;
    private static final float APP_ICON_DP = 16.0f;
    /** Slide-up distance of a row while it fades in. */
    private static final float ROW_SLIDE_DP = 8.0f;
    private static final String NO_DURATION = "--:--";
    private static final String SEPARATOR = "  •  ";

    final float pad;
    float barLeft;
    float barRight;
    private final float seekCenter;
    private final String line;
    private final String topText;
    private final boolean topIsOutput;
    private final Path wavePath = new Path();
    private final Path glyphPath = new Path();
    private final Btn prevBtn, playBtn, nextBtn;
    private final java.util.Map<Btn, android.graphics.drawable.Drawable> customIcons = new java.util.HashMap<>();
    /** Custom action icons (shuffle, like ...) as big as the stock player draws them. */
    private static final float CUSTOM_ICON_DP = 26.0f;
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long clockSecond = Long.MIN_VALUE, durationSecond = Long.MIN_VALUE;
    private String clockText, durationText;
    /** Smoothed wave height 0..1 (eases to 0 on pause) and per-layer smoothed audio. */
    private float waveAmp;
    private final float[] waveLevel = new float[3];
    private long lastWaveFrame;
    private float wavePhase;

    public MediaBody(IslandView owner, IslandItem item, float w) {
        super(owner, item, w);
        pad = owner.dp(SIDE_PAD_DP);
        h = owner.dp(HEIGHT_DP);
        seekCenter = owner.dp(SEEK_ROW_DP);
        barLeft = pad;
        barRight = w - pad;

        float lineRoom = w - pad * 2 - owner.dp(APP_ICON_DP + 8);
        CharSequence joined = item.title == null ? "" : item.title;
        if (item.subtitle != null && item.subtitle.length() > 0) {
            joined = joined.length() == 0 ? item.subtitle : joined + SEPARATOR + item.subtitle;
        }
        line = TextUtils.ellipsize(joined, owner.mediaTitlePaint(), Math.max(1, lineRoom), TextUtils.TruncateAt.END).toString();

        topIsOutput = item.mediaOutput != null;
        CharSequence top = topIsOutput
                ? item.mediaOutput + (item.mediaOutputBattery >= 0 ? " · " + item.mediaOutputBattery + "%" : "")
                : item.appName;
        topText = top == null ? null : TextUtils.ellipsize(top, owner.smallPaint,
                Math.max(1, w - pad * 2 - owner.dp(OUTPUT_ICON_DP + 8) - owner.dp(28)), TextUtils.TruncateAt.END).toString();

        float centerX = w / 2.0f;
        float controlsCenter = owner.dp(CONTROLS_ROW_DP);
        if (item.mediaCustom.size() > 0) {
            addCustom(item.mediaCustom.get(0), centerX - owner.dp(CUSTOM_STEP_DP), controlsCenter);
        }
        MediaFx fx0 = owner.mediaFx(item);
        prevBtn = addControl(centerX - owner.dp(BUTTON_STEP_DP), controlsCenter,
                tapped(item.mediaPrev, () -> fx0.skipPrevAt = IslandView.now()));
        playBtn = addControl(centerX, controlsCenter, item.mediaPlayPause);
        nextBtn = addControl(centerX + owner.dp(BUTTON_STEP_DP), controlsCenter,
                tapped(item.mediaNext, () -> fx0.skipNextAt = IslandView.now()));
        if (item.mediaCustom.size() > 1) {
            addCustom(item.mediaCustom.get(1), centerX + owner.dp(CUSTOM_STEP_DP), controlsCenter);
        }
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStyle(Paint.Style.FILL);
        waveAmp = item.playing ? 1.0f : 0.0f;
    }

    /** Whether ({@code lx}, {@code ly}) in body coordinates is on the seek bar and the item can seek. */
    public boolean onSeekBar(float lx, float ly) {
        return item.mediaSeek != null && item.mediaDuration > 0 && ly > seekCenter - owner.dp(18.0f)
                && ly < seekCenter + owner.dp(18.0f) && lx > barLeft - owner.dp(12.0f) && lx < barRight + owner.dp(12.0f);
    }

    /** Position on the seek bar (0..1) for a touch at {@code lx}. */
    public float fractionAt(float lx) {
        return IslandView.clamp01((lx - barLeft) / Math.max(1.0f, barRight - barLeft));
    }

    private static Runnable tapped(Runnable action, Runnable mark) {
        if (action == null) return null;
        return () -> { mark.run(); action.run(); };
    }

    /** Round translucent control; its glyph is drawn (and animated) by {@link #drawControlGlyphs}. */
    private Btn addControl(float centerX, float centerY, Runnable onTap) {
        Btn b = new Btn();
        b.circle = true;
        b.bg = Colors.CONTROL_FILL; // the stock player's translucent round buttons
        b.run = onTap;
        float r = owner.dp(BUTTON_DP) / 2.0f;
        b.r.set(centerX - r, centerY - r, centerX + r, centerY + r);
        buttons.add(b);
        return b;
    }

    /** Custom player action (shuffle, repeat, like ...): its icon, or its title when it has no icon. */
    private void addCustom(IslandItem.Action action, float centerX, float centerY) {
        Btn btn = new Btn();
        customIcons.put(btn, action.icon); // drawn at the stock size by drawControlGlyphs, not CardBody's half size
        btn.label = action.icon == null && action.title != null ? action.title.toString() : null;
        btn.circle = true;
        btn.bg = Colors.TRANSPARENT_MARK;
        btn.run = action.runnable;
        float r = owner.dp(CUSTOM_DP) / 2.0f;
        btn.r.set(centerX - r, centerY - r, centerX + r, centerY + r);
        buttons.add(btn);
    }

    @Override
    public boolean animating(long now) {
        MediaFx fx = owner.mediaFx(item);
        if (fx.backdropAnimating(now)) return true;
        if (fx.playMorph >= 0 && fx.playMorph != (item.playing ? 1.0f : 0.0f)) return true;
        if (now - fx.playToggleAt < 800 || now - fx.skipNextAt < 1000 || now - fx.skipPrevAt < 1000) return true;
        if (dev.android.dynamicisland.settings.ModuleSettings.stillMusicEffects()) return false;
        return item.playing || waveAmp > 0.002f;
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        long now = IslandView.now();
        MediaFx fx = owner.mediaFx(item);
        fx.advance(now);
        float p0 = rowAlpha(elapsed, 0);
        float dy0 = (1.0f - p0) * owner.dp(ROW_SLIDE_DP);
        drawTopRow(c, x, y + dy0, alpha * p0);
        drawTitleRow(c, x, y + dy0, alpha * p0);
        float p1 = rowAlpha(elapsed, 1);
        drawSeekRow(c, x, y + (1.0f - p1) * owner.dp(ROW_SLIDE_DP), alpha * p1, fx, now);
        float p2 = rowAlpha(elapsed, 2);
        drawButtons(c, x, y + (1.0f - p2) * owner.dp(ROW_SLIDE_DP), alpha * p2);
        drawControlGlyphs(c, x, y + (1.0f - p2) * owner.dp(ROW_SLIDE_DP), alpha * p2, fx, now);
    }

    private static final float MORPH_TAU_S = 0.075f;
    private static final float SKIP_MS = 420.0f;

    /** Play/pause morphs between triangle and bars; prev/next slide out and back in on tap (One UI player). */
    private void drawControlGlyphs(Canvas c, float x, float y, float alpha, MediaFx fx, long now) {
        float target = item.playing ? 1.0f : 0.0f;
        if (fx.playMorph < 0) fx.playMorph = target;
        float dt = fx.playMorphFrame == 0 ? 0 : Math.min(0.05f, (now - fx.playMorphFrame) / 1000f);
        fx.playMorphFrame = now;
        fx.playMorph += (target - fx.playMorph) * (1.0f - (float) Math.exp(-dt / MORPH_TAU_S));
        if (Math.abs(target - fx.playMorph) < 0.002f) fx.playMorph = target;
        if (fx.lastPlaying == null) fx.lastPlaying = item.playing;
        if (fx.lastPlaying != item.playing) { fx.lastPlaying = item.playing; fx.playToggleAt = now; }
        Lotties lot = Lotties.get(owner.getContext());
        owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
        if (lot.playPause != null) {
            // official One UI animation: 60 play -> 100 pause -> 140 play
            float f = (now - fx.playToggleAt) * lot.playPause.frameRate() / 1000f;
            float frame = item.playing ? Math.min(100f, 60f + f) : (f < 40f ? 100f + f : 60f);
            drawLottie(c, playBtn, x, y, lot.playPause, frame, PLAY_LOTTIE_DP, false, alpha);
        } else {
            drawPlayPause(c, playBtn, x, y, fx.playMorph);
        }
        if (lot.next != null) {
            drawLottie(c, nextBtn, x, y, lot.next, skipFrame(lot.next, now - fx.skipNextAt), SKIP_LOTTIE_DP, false, alpha);
        } else {
            drawSkip(c, nextBtn, x, y, Glyphs.NEXT, 1, now - fx.skipNextAt, alpha);
        }
        if (lot.prev != null) {
            drawLottie(c, prevBtn, x, y, lot.prev, skipFrame(lot.prev, now - fx.skipPrevAt), SKIP_LOTTIE_DP, false, alpha);
        } else {
            drawSkip(c, prevBtn, x, y, Glyphs.PREV, -1, now - fx.skipPrevAt, alpha);
        }
        float icon = owner.dp(CUSTOM_ICON_DP);
        for (java.util.Map.Entry<Btn, android.graphics.drawable.Drawable> e : customIcons.entrySet()) {
            if (e.getValue() == null) continue;
            Btn b = e.getKey();
            float cx = x + b.r.centerX(), cy = y + b.r.centerY();
            int save = pressScale(c, b, cx, cy);
            owner.drawDrawable(c, e.getValue(), false, Colors.WHITE, cx - icon / 2, cy - icon / 2, icon, alpha, 0);
            c.restoreToCount(save);
        }
    }

    /** Canvas size the official Lottie compositions are drawn at, so their glyphs match the stock player. */
    private static final float PLAY_LOTTIE_DP = 29.0f;
    private static final float SKIP_LOTTIE_DP = 34.0f;
    /** Skip animations run frames 60..115. */
    private static final float SKIP_FROM = 60f, SKIP_TO = 114.9f;
    private final RectF lottieDst = new RectF();

    private static float skipFrame(dev.android.dynamicisland.island.graphics.LottiePlayer p, long since) {
        if (since < 0) return SKIP_FROM;
        float f = SKIP_FROM + since * p.frameRate() / 1000f;
        return f >= SKIP_TO ? SKIP_FROM : f;
    }

    private void drawLottie(Canvas c, Btn b, float x, float y, dev.android.dynamicisland.island.graphics.LottiePlayer p,
            float frame, float sizeDp, boolean unused, float alpha) {
        float cx = x + b.r.centerX(), cy = y + b.r.centerY();
        float half = owner.dp(sizeDp) / 2f;
        int save = pressScale(c, b, cx, cy);
        lottieDst.set(cx - half, cy - half, cx + half, cy + half);
        p.drawFrame(c, lottieDst, frame, Colors.withAlpha(Colors.WHITE, alpha));
        c.restoreToCount(save);
    }

    /** System UI's own media control animations ({@code res/raw/media_*}), loaded once. */
    private static final class Lotties {
        private static Lotties instance;
        final dev.android.dynamicisland.island.graphics.LottiePlayer playPause, next, prev;

        private Lotties(android.content.Context ctx) {
            playPause = dev.android.dynamicisland.island.graphics.LottiePlayer.fromHostRaw(ctx, "media_play_pause_play");
            next = dev.android.dynamicisland.island.graphics.LottiePlayer.fromHostRaw(ctx, "media_next");
            prev = dev.android.dynamicisland.island.graphics.LottiePlayer.fromHostRaw(ctx, "media_prev");
        }

        static synchronized Lotties get(android.content.Context ctx) {
            if (instance == null) instance = new Lotties(ctx);
            return instance;
        }
    }

    private int pressScale(Canvas c, Btn b, float cx, float cy) {
        int save = c.save();
        float press = owner.pressOf(b, item);
        if (press != 0.0f) {
            float scale = 1.0f - 0.08f * press;
            c.scale(scale, scale, cx, cy);
        }
        return save;
    }

    // pause bars and play triangle as two quads each (x, y pairs, unit = glyph size), morphed point by point
    private static final float[] PAUSE_L = {-0.30f, -0.36f, -0.09f, -0.36f, -0.09f, 0.36f, -0.30f, 0.36f};
    private static final float[] PAUSE_R = {0.09f, -0.36f, 0.30f, -0.36f, 0.30f, 0.36f, 0.09f, 0.36f};
    private static final float[] PLAY_L = {-0.26f, -0.40f, 0.07f, -0.20f, 0.07f, 0.20f, -0.26f, 0.40f};
    private static final float[] PLAY_R = {0.07f, -0.20f, 0.40f, 0.0f, 0.40f, 0.0f, 0.07f, 0.20f};

    private void drawPlayPause(Canvas c, Btn b, float x, float y, float morph) {
        float cx = x + b.r.centerX(), cy = y + b.r.centerY();
        float size = b.r.height() * 0.44f;
        int save = pressScale(c, b, cx, cy);
        glyphPath.rewind();
        quad(PLAY_L, PAUSE_L, morph, cx, cy, size);
        quad(PLAY_R, PAUSE_R, morph, cx, cy, size);
        owner.fillPaint.setStyle(Paint.Style.FILL);
        c.drawPath(glyphPath, owner.fillPaint);
        // round off the corners a little
        owner.fillPaint.setStyle(Paint.Style.STROKE);
        owner.fillPaint.setStrokeJoin(Paint.Join.ROUND);
        owner.fillPaint.setStrokeWidth(size * 0.08f);
        c.drawPath(glyphPath, owner.fillPaint);
        owner.fillPaint.setStyle(Paint.Style.FILL);
        c.restoreToCount(save);
    }

    private void quad(float[] from, float[] to, float t, float cx, float cy, float size) {
        for (int i = 0; i < 8; i += 2) {
            float px = cx + (from[i] + (to[i] - from[i]) * t) * size;
            float py = cy + (from[i + 1] + (to[i + 1] - from[i + 1]) * t) * size;
            if (i == 0) glyphPath.moveTo(px, py); else glyphPath.lineTo(px, py);
        }
        glyphPath.close();
    }

    /** The arrow slides out the way it points and comes back in from the other side. */
    private void drawSkip(Canvas c, Btn b, float x, float y, int glyph, int dir, long sinceTap, float alpha) {
        float cx = x + b.r.centerX(), cy = y + b.r.centerY();
        float size = b.r.height() * 0.44f;
        int save = pressScale(c, b, cx, cy);
        float t = sinceTap < 0 ? 1.0f : IslandView.clamp01(sinceTap / SKIP_MS);
        float shift = 0.0f, a = 1.0f;
        if (t < 1.0f) {
            float travel = b.r.width() * 0.42f;
            if (t < 0.45f) {
                float u = IslandView.easeInOut(t / 0.45f);
                shift = dir * travel * u;
                a = 1.0f - u;
            } else {
                float u = IslandView.easeInOut((t - 0.45f) / 0.55f);
                shift = -dir * travel * (1.0f - u);
                a = u;
            }
            c.clipRect(x + b.r.left, y + b.r.top, x + b.r.right, y + b.r.bottom);
        }
        owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha * a));
        Glyphs.draw(c, glyph, cx + shift, cy, size, owner.fillPaint);
        c.restoreToCount(save);
    }

    /** The glow, stretched to the card's current shape so it always fills the card edge to edge. */
    @Override public MediaFx materialMusic() { return owner.mediaFx(item); }

    /** The cover art filling the card (stock player look); the colour glow when there is no art. */
    @Override
    public void drawBackground(Canvas c, RectF frame, float alpha) {
        long now = IslandView.now();
        MediaFx fx = owner.mediaFx(item);
        fx.advance(now);
        fx.syncBackdrop(item, now);
        int save = c.save();
        c.translate(frame.left, frame.top);
        c.scale(frame.width() / w, frame.height() / h);
        if (fx.hasBackdrop()) {
            fx.drawBackdrop(c, 0.0f, 0.0f, w, h, alpha, now);
        } else {
            fx.drawGlow(c, 0.0f, 0.0f, w, h, alpha, now);
        }
        c.restoreToCount(save);
    }

    /** Output device (or the app name) at the left, the player app's icon at the right. */
    private void drawTopRow(Canvas c, float x, float y, float alpha) {
        float midY = y + owner.dp(TOP_ROW_DP);
        float left = x + pad + owner.dp(4);
        if (topText != null) {
            float icon = owner.dp(OUTPUT_ICON_DP);
            if (topIsOutput && item.mediaOutputIcon != null) {
                owner.drawDrawable(c, item.mediaOutputIcon, false, Colors.WHITE, left, midY - icon / 2, icon, alpha, 0);
                left += icon + owner.dp(8);
            }
            TextPaint p = owner.smallPaint;
            p.setColor(Colors.withAlpha(Colors.TEXT_BODY, alpha));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(topText, left, midY - (fm.ascent + fm.descent) / 2, p);
        }
    }

    /** "Title  •  Artist" on one centred line, led by the app's glyph like the stock player. */
    private void drawTitleRow(Canvas c, float x, float y, float alpha) {
        if (line.isEmpty()) return;
        TextPaint p = owner.mediaTitlePaint();
        float midY = y + owner.dp(TITLE_ROW_DP);
        float textW = p.measureText(line);
        float glyph = item.appGlyph != null ? owner.dp(APP_ICON_DP) : 0;
        float gap = glyph > 0 ? owner.dp(6) : 0;
        float start = x + (w - textW - glyph - gap) / 2.0f;
        if (glyph > 0) {
            owner.drawDrawable(c, item.appGlyph, false, Colors.WHITE, start, midY - glyph / 2, glyph, alpha, 0);
        }
        p.setColor(Colors.withAlpha(Colors.WHITE, alpha));
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(line, start + glyph + gap, midY - (fm.ascent + fm.descent) / 2, p);
    }

    /** Accent bar + live wave up to the ringed thumb, a light track after it, times underneath. */
    private void drawSeekRow(Canvas c, float x, float y, float alpha, MediaFx fx, long now) {
        long position = fx.shownPosition(item, SystemClock.elapsedRealtime());
        long second = Math.max(0L, position) / 1000;
        if (clockText == null || clockSecond != second) { clockSecond = second; clockText = IslandView.formatTimePadded(Math.max(0L, position)); }
        long duration = item.mediaDuration > 0 ? item.mediaDuration / 1000 : -1;
        if (durationText == null || durationSecond != duration) { durationSecond = duration; durationText = item.mediaDuration > 0 ? IslandView.formatTimePadded(item.mediaDuration) : NO_DURATION; }
        boolean dragged = owner.seekBody == this;
        float fraction = dragged ? owner.seekFraction
                : item.mediaDuration > 0 ? IslandView.clamp01((float) position / item.mediaDuration) : 0.0f;

        float centerY = y + seekCenter;
        float left = x + barLeft, right = x + barRight;
        float thumbR = owner.dp(THUMB_R_DP) * (dragged ? 1.15f : 1.0f);
        float thumbX = left + thumbR + (right - left - thumbR * 2) * fraction;
        int accent = Colors.readableOnBlack(fx.waveformColorAt(0.5f, now));
        float bar = owner.dp(dragged ? BAR_DRAGGED_DP : BAR_DP);

        // remaining track after the thumb
        float trackStart = thumbX + thumbR + owner.dp(THUMB_GAP_DP);
        strokePaint.setStrokeWidth(bar);
        if (trackStart < right - bar / 2) {
            strokePaint.setColor(Colors.withAlpha(0x66FFFFFF, alpha));
            c.drawLine(trackStart + bar / 2, centerY, right - bar / 2, centerY, strokePaint);
        }
        // wave riding on the played part
        drawWave(c, left, thumbX, centerY - bar / 2, alpha, fx, now);
        // played part
        if (thumbX - left > bar) {
            strokePaint.setColor(Colors.withAlpha(accent, alpha));
            c.drawLine(left + bar / 2, centerY, thumbX, centerY, strokePaint);
        }
        // thumb: accent dot in a white ring
        owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, alpha));
        c.drawCircle(thumbX, centerY, thumbR, owner.fillPaint);
        owner.fillPaint.setColor(Colors.withAlpha(accent, alpha));
        c.drawCircle(thumbX, centerY, thumbR - owner.dp(THUMB_RING_DP), owner.fillPaint);

        String current = dragged ? IslandView.formatTimePadded((long) (Math.max(0L, item.mediaDuration) * owner.seekFraction)) : clockText;
        owner.smallPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, alpha));
        Paint.FontMetrics fm = owner.smallPaint.getFontMetrics();
        float baseline = y + owner.dp(TIME_ROW_DP) - (fm.ascent + fm.descent) / 2.0f;
        owner.drawTime(c, item.key + "#pos", current, owner.smallPaint, left + owner.dp(2), baseline, false);
        owner.drawTime(c, item.key + "#dur", durationText, owner.smallPaint,
                right - owner.dp(2) - IslandView.timeWidth(owner.smallPaint, durationText), baseline, false);
    }

    /**
     * Three translucent layers of rolling hills above the played part of the bar, like the One UI 9 player:
     * their height follows the music (live audio when available, a calm swell otherwise) and sinks into the
     * bar on pause.
     */
    private void drawWave(Canvas c, float left, float right, float baseY, float alpha, MediaFx fx, long now) {
        float dt = lastWaveFrame == 0 ? 0 : Math.min(0.05f, (now - lastWaveFrame) / 1000f);
        lastWaveFrame = now;
        if (dev.android.dynamicisland.settings.ModuleSettings.stillMusicEffects()) dt = 0; // low performance: a still wave
        waveAmp += ((item.playing ? 1.0f : 0.0f) - waveAmp) * (1.0f - (float) Math.exp(-dt / 0.22f));
        wavePhase += dt * (0.9f + 0.6f * waveLevel[0]);
        AudioLevels audio = owner.audioLevels();
        boolean live = item.playing && audio != null && audio.isLive();
        for (int i = 0; i < waveLevel.length; i++) {
            float target;
            if (live) {
                int band = Math.min(AudioLevels.BANDS - 1, i * 2);
                target = 0.25f + 0.75f * audio.level(band);
            } else {
                target = 0.6f + 0.25f * (float) Math.sin(now / 900.0 + i * 1.7);
            }
            waveLevel[i] += (target - waveLevel[i]) * (1.0f - (float) Math.exp(-dt / 0.12f));
        }
        float span = right - left;
        if (waveAmp < 0.01f || span < owner.dp(24)) return;
        float maxH = owner.dp(WAVE_DP) * waveAmp;
        float[] at = {0.25f, 0.6f, 0.85f};
        float[] freq = {2.1f, 3.3f, 1.4f};
        float[] speed = {1.0f, -1.4f, 0.7f};
        int steps = Math.max(12, Math.round(span / owner.dp(4)));
        for (int layer = 0; layer < 3; layer++) {
            wavePath.rewind();
            wavePath.moveTo(left, baseY);
            float h = maxH * waveLevel[layer] * (layer == 1 ? 0.8f : 1.0f);
            for (int s = 0; s <= steps; s++) {
                float u = s / (float) steps;
                float px = left + span * u;
                // taper to the bar at both ends; hills move along the bar
                float env = (float) Math.sin(Math.PI * u);
                env = (float) Math.pow(env, 0.7);
                float hills = 0.5f + 0.5f * (float) Math.sin(u * freq[layer] * Math.PI * 2 * (span / owner.dp(300))
                        + wavePhase * speed[layer] * 2.4f + layer * 1.3f);
                wavePath.lineTo(px, baseY - h * env * (0.35f + 0.65f * hills));
            }
            wavePath.lineTo(right, baseY);
            wavePath.close();
            int colour = Colors.readableOnBlack(fx.waveformColorAt(at[layer], now));
            wavePaint.setColor(Colors.withAlpha(colour, alpha * 0.55f));
            c.drawPath(wavePath, wavePaint);
        }
    }
}
