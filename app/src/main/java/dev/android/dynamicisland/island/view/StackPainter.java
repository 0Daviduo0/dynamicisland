package dev.android.dynamicisland.island.view;

import dev.android.dynamicisland.island.model.IslandItem;

import dev.android.dynamicisland.island.view.media.MediaFx;
import dev.android.dynamicisland.island.material.FrostedSurface;
import dev.android.dynamicisland.island.material.MaterialShape;
import static dev.android.dynamicisland.island.graphics.Easing.easeOutBack;
import static dev.android.dynamicisland.island.graphics.Easing.lerp;
import static dev.android.dynamicisland.island.view.IslandView.clamp01;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.graphics.Easing;
import dev.android.dynamicisland.island.graphics.Metaball;
import dev.android.dynamicisland.island.view.card.CardBody;
import dev.android.dynamicisland.island.view.card.RemoteBody;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Draws the expanded cards: card surface with its glass and rim, the content on top, the metaball neck between
 * cards that are being swallowed, and the blur backdrops placed behind the cards.
 */
final class StackPainter {
    /** Card content fades in from this far below its place and from this size. */
    private static final float CONTENT_RISE_DP = 10.0f;
    private static final float CONTENT_SCALE_FROM = 0.95f;
    /** How long a leaving card's content takes to fade while it shrinks with the card. */
    private static final float LEAVING_CONTENT_FADE_MS = 90.0f;
    /** Fold progress by which a leaving card's content is gone: it fades first, then the empty shape folds into the pill. */
    private static final float FOLD_FADE_TO = 0.38f;
    /** Opening progress at which a new card's content is fully there. */
    private static final float OPEN_FADE_TO = 0.25f;
    /** A card that is leaving reports this "elapsed" time so its content is fully shown while it fades. */
    private static final long LEAVING_CONTENT_ELAPSED_MS = 2305843009213693951L;
    /** Opening fraction from which a card turns from the pill's black into its own colour and glass. */
    private static final float CARD_MATERIAL_FROM = 0.35f;
    private static final float GLASS_FROM = 0.1f;
    /** The captured material has no lagging native surface to hide during a fold. */
    private static final float FROSTED_FULL_AT = 0.65f;
    /** Stop the native backdrop well before the compact pill takes over; its compositor can lag a frame. */
    private static final float FOLD_BLUR_FADE_FROM = 0.1f;
    private static final float FOLD_BLUR_FADE_TO = 0.55f;
    /** Necks thinner than this are not drawn; they reach full opacity this much wider. */
    private static final float NECK_FADE_FROM_DP = 4.0f;
    private static final float NECK_FADE_RANGE_DP = 6.0f;

    private final Metrics m;
    private final Paints paints;
    private final Scene scene;
    private final Shapes shapes;
    /** Set by the view once it exists; draws the compact row beside the camera hole of a card opened around it. */
    IslandView view;
    private CompactContent holeRow;
    private final RectF holeRowRect = new RectF();
    /** Inset of the row beside the hole from the card's sides, so the icon sits off the rounded corner. */
    private static final float HOLE_ROW_INSET_DP = 14.0f;
    private final Path clip = new Path();
    private final RectF inner = new RectF();
    private final RectF slot = new RectF();
    private final RectF gooUpper = new RectF();
    private final RectF gooLower = new RectF();
    private final Path gooPath = new Path();

    /** Outline of a card that is still changing shape: brighter than the pill's, so the motion shows on a black screen. */
    private static final int MORPH_OUTLINE = 0x70FFFFFF;
    private LinearGradient rim;
    private float rimHeight = -1.0f;
    private float rimTop = Float.NaN;
    private float rimAlpha = -1.0f;
    private float rimSettle = -1.0f;
    private int rimBase;
    private boolean drawFailureLogged;
    private Runnable materialFrameRequester;

    StackPainter(Metrics m, Paints paints, Scene scene, Shapes shapes) {
        this.m = m;
        this.paints = paints;
        this.scene = scene;
        this.shapes = shapes;
    }

    void setMaterialFrameRequester(Runnable request) { materialFrameRequester=request; }

    /** Fade of a card's content: in once the card is nearly open, out quickly when it leaves. */
    static float contentAlpha(Card c, long t, float opening) {
        if (c.handoff != null) return clamp01(c.handoff.expanded(t) * c.handoffScale);
        if (c.leaving) {
            if (ModuleSettings.legacyAnimation()) {
                return c.leaveContentAlpha * (1.0f - clamp01((t - c.leaveAt) / LEAVING_CONTENT_FADE_MS));
            }
            // the content blurs and fades out in the first part of the fold, while the card is still large; what
            // travels back into the pill is the plain shape, which does not leave a smear of tiny text behind
            return c.leaveContentAlpha * (1.0f - Easing.easeInOut(clamp01(foldProgress(c) / FOLD_FADE_TO)));
        }
        if (!ModuleSettings.legacyAnimation()) {
            // the mirror of folding: the content grows with the card from the first moment instead of appearing in an empty shape
            return clamp01(opening / OPEN_FADE_TO);
        }
        return clamp01((opening - 0.85f) / IslandView.PAUSED_SPEED) * clamp01((t - c.contentStart) / 150.0f);
    }

    /** Internal material transition, independent of the card surface and its native backdrop. */
    static float contentBlurAmount(Card card, long time, float opening) {
        if(card.iMode && card.handoff!=null)
            return Math.max(card.iModePaging ? clamp01(Math.abs(card.dx.value())/Math.max(1,card.body.w))*.6f : 0,Math.max(IMode.motionBlur(card,card.body.density()*1000),
                    (float)Math.min(1,1.2*Math.sqrt(Math.max(0,1-contentAlpha(card,time,opening))))));
        if (card.leaving) {
            float fade = 1 - contentAlpha(card, time, opening) / Math.max(.001f, card.leaveContentAlpha);
            return lerp(card.leaveContentBlur, 1, clamp01(fade));
        }
        if (card.handoff != null) return 1 - contentAlpha(card, time, opening);
        float clarity = Easing.easeInOut(clamp01((opening - 0.08f) / 0.82f));
        return Math.max(1 - clarity, 1 - contentAlpha(card, time, opening));
    }

    /** How far a leaving card has folded towards the size it is going to (0 = not yet, 1 = there). */
    private static float foldProgress(Card c) {
        if (!c.launched) {
            return 0.0f;
        }
        float spanH = c.leaveFromH - c.targetH;
        if (spanH > 1.0f) {
            return clamp01((c.leaveFromH - c.h.value()) / spanH);
        }
        float spanW = c.leaveFromW - c.targetW;
        return spanW > 1.0f ? clamp01((c.leaveFromW - c.w.value()) / spanW) : 1.0f;
    }

    /** A liquid neck fades out as its waist gets thin, so it never shows as a hair-thin line. */
    float neckAlpha(float waist) {
        return clamp01((waist - m.dp(NECK_FADE_FROM_DP)) / m.dp(NECK_FADE_RANGE_DP));
    }

    /**
     * How much of the card's glass look is kept while it folds back into the pill (0..1): it turns from the grey glass
     * into the pill's black gradually along the fold. Taken in one go (as soon as the card's height was nearly the
     * pill's) it showed as a flash of flat grey just before the black.
     */
    float blurFraction(Card c, float opening) {
        if(c.iModePaging) return 1;
        if (c.leaving) {
            float fold = (foldProgress(c) - FOLD_BLUR_FADE_FROM) / (FOLD_BLUR_FADE_TO - FOLD_BLUR_FADE_FROM);
            return 1.0f - Easing.easeInOut(clamp01(fold));
        }
        return ModuleSettings.legacyAnimation() ? 1 : Easing.easeInOut(clamp01((opening - GLASS_FROM) / 0.6f));
    }

    /** The "opening" a card is drawn with: a leaving card goes back to the pill's black along its fold, not along its height. */
    float visualOpening(Card c, float opening) {
        if(c.iModePaging) return opening;
        if (c.leaving && !ModuleSettings.legacyAnimation()) {
            return Math.min(opening, 1.0f - Easing.easeInOut(foldProgress(c)));
        }
        return opening;
    }

    /** The card's fill: the pill's black while it pours out, turning into the card colour (and glass) as it opens. */
    int fillColor(float opening, float blurFraction) {
        if (ModuleSettings.lowPerformance()) return Colors.BLACK;
        boolean glass = scene.blur != null && scene.blur.available();
        float material = Easing.easeInOut(clamp01((opening - CARD_MATERIAL_FROM) / (1.0f - CARD_MATERIAL_FROM)));
        int fill = Colors.blend(Colors.BLACK, glass ? Colors.CARD_GLASS : Colors.CARD_SOLID, material);
        // the see-through glass comes in over the whole opening: squeezing it into the last stretch made the black
        // turn transparent in one jump
        float glassIn = Easing.easeInOut(clamp01((opening - GLASS_FROM) / (1.0f - GLASS_FROM)));
        // the see-through part only counts as far as the real blur is there behind it
        return Colors.withAlpha(fill, lerp(1.0f, glass ? 0.62f : 0.94f, glassIn * (glass ? blurFraction : 1.0f)));
    }

    /** Blur callback: paints a card into its blur slot, whose canvas origin is the slot, not the view. */
    void paintInBlurSlot(Object owner, Canvas canvas, float viewLeft, float viewTop) {
        if (!(owner instanceof Card)) {
            return;
        }
        Card c = (Card) owner;
        shapes.card(c, slot);
        int save = canvas.save();
        canvas.translate(-viewLeft, -viewTop);
        drawCard(canvas, c, slot, IslandView.now(), shapes.expansion(c));
        canvas.restoreToCount(save);
    }

    /** Tells the blur layer where every card is for this frame. */
    void placeBlurs() {
        BlurSink blur = scene.blur;
        if (blur == null) {
            return;
        }
        blur.beginFrame();
        float alpha = scene.visibleAlpha();
        if (alpha > IslandView.MIN_VISIBLE_ALPHA) {
            // back to front: the first card is the one nearest the pill, so it lies on top of the cards below it
            boolean legacy = ModuleSettings.legacyAnimation();
            for (int n = 0; n < scene.cards.size(); n++) {
                Card c = scene.cards.get(legacy ? n : scene.cards.size() - 1 - n);
                shapes.card(c, slot);
                if (slot.width() >= 1.0f && slot.height() >= 1.0f) {
                    float opening = visualOpening(c, shapes.expansion(c));
                    boolean frosted = FrostedSurface.requested();
                    blur.place(c, slot, c.iMode ? IMode.corner(c,slot,m.pillH,m.cardRadius)
                                    : c.body.cornerRadius(m.cardRadius, slot.height()), alpha * (c.iMode ? clamp01(c.iModeOpacity.value()) : 1),
                            frosted ? 0 : blurFraction(c, opening));
                }
            }
        }
        blur.endFrame();
    }

    /** The neck of goo between two cards that are folding back into the pill one after the other. */
    void drawGoo(Canvas canvas) {
        if(scene.iMode!=null) return;
        Card previous = null;
        for (Card c : scene.cards) {
            if (!c.leaving) {
                continue;
            }
            if (previous != null) {
                drawGooBetween(canvas, previous, c);
            }
            previous = c;
        }
    }

    private void drawGooBetween(Canvas canvas, Card a, Card b) {
        shapes.card(a, gooUpper);
        shapes.card(b, gooLower);
        RectF top = gooUpper.top <= gooLower.top ? gooUpper : gooLower;
        RectF bottom = top == gooUpper ? gooLower : gooUpper;
        float gap = bottom.top - top.bottom;
        float openness = Math.max(shapes.expansion(a), shapes.expansion(b));
        float spread = (1.0f - clamp01(gap / m.dp(30.0f))) * 0.6f * (1.0f - openness);
        if (spread <= 0.02f) {
            return;
        }
        float topR = Math.min(Math.min(top.height(), top.width()) / 2.0f, m.dp(22.0f));
        float bottomR = Math.min(Math.min(bottom.height(), bottom.width()) / 2.0f, m.dp(22.0f));
        gooPath.reset();
        float alpha = neckAlpha(Metaball.neck(top.centerX(), top.bottom - topR, topR, bottom.centerX(), bottom.top + bottomR, bottomR, spread, gooPath));
        if (alpha > 0.0f) {
            paints.shape.setAlpha(Math.round(255.0f * alpha));
            canvas.drawPath(gooPath, paints.shape);
            paints.shape.setAlpha(255);
        }
    }

    /** Draws one card whose rectangle {@code r} was already computed. */
    void drawCard(Canvas canvas, Card c, RectF r, long t, float opening) {
        int save=canvas.save();
        if(c.iMode && c.iModeOpacity.value()<.995f)
            canvas.saveLayerAlpha(r,Math.round(255*clamp01(c.iModeOpacity.value())));
        try { drawCardSurface(canvas,c,r,t,opening); }
        finally { canvas.restoreToCount(save); }
    }

    private void drawCardSurface(Canvas canvas, Card c, RectF r, long t, float opening) {
        float corner = c.iMode ? IMode.corner(c,r,m.pillH,m.cardRadius) : c.body.cornerRadius(m.cardRadius, r.height());
        float rimSettled = c.iMode ? Easing.easeInOut(clamp01(opening)) : clamp01((opening - 0.82f) / 0.18f);
        // while it pours out of (or back into) the pill the card is the pill's black, but it wears a clearly
        // visible outline from the first frame, so the changing shape can be followed on a black screen; the card
        // colour and glass fade in as it settles, and the outline turns into the card's light rim
        opening = visualOpening(c, opening);
        boolean canFrost = canvas.isHardwareAccelerated() && FrostedSurface.requested();
        MediaFx music = canFrost && c.item.isMedia ? c.body.materialMusic() : null;
        if (music != null) music.advance(t);
        float material = frostedAmount(opening);
        float cornerPower=c.iMode ? IMode.materialPower(c,m.pillH) : 2.4f;
        boolean frosted = c.material.draw(canvas, scene.materialFrame, r, r, corner, material, music, t,cornerPower);
        // A capture can begin blending in this draw, after doFrame checked for
        // motion. Explicitly schedule its following frames even on a static card.
        if(frosted&&c.material.animating(t)&&materialFrameRequester!=null)materialFrameRequester.run();
        if(canFrost&&!frosted&&materialFrameRequester!=null)materialFrameRequester.run();
        if (!frosted) {
            paints.card.setColor(fillColor(opening, blurFraction(c, opening)));
            canvas.drawRoundRect(r, corner, corner, paints.card);
        }
        // iMotion carries its pill contrast rim in the overlay, outside native slot clipping.
        // This gradient is only the card material's own rim, which develops as the card opens.
        int morphOutline = c.iMode ? 0x00FFFFFF
                : Colors.withAlpha(c.leaving ? Colors.PILL_OUTLINE : MORPH_OUTLINE, scene.pillOutlineOpacity());
        paints.cardStroke.setShader(ModuleSettings.legacyAnimation()
                ? rimShader(r, rimSettled, 1.0f, MORPH_OUTLINE)
                : rimShader(r, 1.0f, rimSettled, morphOutline));
        inner.set(r);
        inner.inset(m.outlineW / 2.0f, m.outlineW / 2.0f);
        if (!frosted) canvas.drawRoundRect(inner, corner, corner, paints.cardStroke);
        if(c.iMode && scene.iMode!=null) {
            int compactSave=canvas.save();
            clip.reset();clip.addRoundRect(r,corner,corner,Path.Direction.CW);canvas.clipPath(clip);
            scene.iMode.drawCompactCard(canvas,c,r,t);
            canvas.restoreToCount(compactSave);
        }
        float alpha = contentAlpha(c, t, opening);
        if (alpha <= IslandView.MIN_VISIBLE_ALPHA || c.body instanceof RemoteBody && !IMode.paintsRemoteInSurface(c,t)) {
            return;
        }
        int save = canvas.save();
        clip.reset();
        if (frosted) MaterialShape.path(clip, r, corner,cornerPower);
        else clip.addRoundRect(r, corner, corner, Path.Direction.CW);
        canvas.clipPath(clip);
        float backgroundAlpha=c.iMode && c.item.isMedia ? Easing.easeOutCubic(opening) : alpha;
        if (!frosted || !c.item.isMedia) drawBackground(c.body, canvas, r, backgroundAlpha);
        drawHoleBand(c.body, canvas, r, backgroundAlpha);
        Canvas target = canvas;
        canvas = c.contentBlur.begin(target, r, contentBlurAmount(c, t, opening), m.dp(ContentBlur.RADIUS_DP));
        int contentSave = canvas.save();
        try {
            if (!c.leaving && alpha < 1.0f && ModuleSettings.legacyAnimation()) {
                // the content rises into place and settles from a slightly smaller size while it fades in
                float settle = easeOutBack(alpha);
                float scale = CONTENT_SCALE_FROM + ((1.0f - CONTENT_SCALE_FROM) * settle);
                canvas.translate(0.0f, (1.0f - settle) * m.dp(CONTENT_RISE_DP));
                canvas.scale(scale, scale, r.centerX(), r.top);
            }
            // the content follows the card's current shape (squashed while it bounces, shrinking into the pill
            // when it leaves) instead of keeping its laid-out size and being cut off at the edges
            long elapsed = c.leaving || c.handoff != null ? LEAVING_CONTENT_ELAPSED_MS : t - c.contentStart;
            if(c.iMode) {
                // The surface morphs independently. Artwork, text and buttons keep their aspect
                // ratio as a single foreground layout, including when a transition reverses.
                float scale=Math.max(.001f,Math.min(r.width()/c.body.w,r.height()/c.body.h));
                canvas.translate(r.centerX()-c.body.w*scale/2,r.centerY()-c.body.h*scale/2);
                canvas.scale(scale,scale);
                if(c.body instanceof RemoteBody) {
                    // A sibling RemoteViews layer would otherwise paint its text above every moving surface.
                    RemoteBody remote=(RemoteBody)c.body;
                    canvas.translate(remote.pad,remote.pad);
                    int remoteSave=canvas.saveLayerAlpha(0,0,remote.view.getMeasuredWidth(),remote.view.getMeasuredHeight(),Math.round(alpha*255));
                    try { remote.view.draw(canvas); }
                    finally { canvas.restoreToCount(remoteSave); }
                } else drawBody(c.body,canvas,0,0,alpha,elapsed);
            } else if (c.leaving && !ModuleSettings.legacyAnimation()) {
                // a leaving card keeps its content at its true size, centred, and the shrinking card clips it
                drawBody(c.body, canvas, r.left + ((r.width() - c.body.w) / 2.0f), r.top, alpha, elapsed);
            } else if (ModuleSettings.legacyAnimation()) {
                canvas.scale(r.width() / c.body.w, r.height() / c.body.h, r.left, r.top);
                drawBody(c.body, canvas, r.left, r.top, alpha, elapsed);
            } else {
                // the content keeps its true size, hung from the card's top, and the card opening downwards
                // uncovers it: stretching it to the growing shape made every element squash and bounce back
                drawBody(c.body, canvas, r.centerX() - (c.body.w / 2.0f), r.top, alpha, elapsed);
            }
        } finally {
            canvas.restoreToCount(contentSave);
            c.contentBlur.end(target, canvas);
            target.restoreToCount(save);
        }
    }

    static float frostedAmount(float opening) {
        return Easing.easeInOut(clamp01(opening / FROSTED_FULL_AT));
    }

    /**
     * The light rim gradient of a card. The gradient is drawn in unit space and moved onto the card with its local
     * matrix, so cards at different heights or positions share it; it is rebuilt only when its colours change
     * noticeably (alpha / settle steps of 0.02).
     */
    private Shader rimShader(RectF r, float alpha, float settle, int base) {
        if (rim == null || Math.abs(rimAlpha - alpha) > 0.02f || Math.abs(rimSettle - settle) > 0.02f || rimBase != base) {
            rimAlpha = alpha;
            rimSettle = settle;
            rimBase = base;
            rim = new LinearGradient(0.0f, 0.0f, 0.0f, 1.0f,
                    Colors.withAlpha(whiteMix(base, Colors.RIM_TOP, settle), alpha),
                    Colors.withAlpha(whiteMix(base, Colors.RIM_BOTTOM, settle), alpha), Shader.TileMode.CLAMP);
            rimHeight = -1.0f;
        }
        if (rimHeight != r.height() || rimTop != r.top) {
            rimHeight = r.height();
            rimTop = r.top;
            rimMatrix.setScale(1.0f, Math.max(1.0f, r.height()));
            rimMatrix.postTranslate(0.0f, r.top);
            rim.setLocalMatrix(rimMatrix);
        }
        return rim;
    }

    private final android.graphics.Matrix rimMatrix = new android.graphics.Matrix();

    /** Between two white colours that differ only in alpha. */
    private static int whiteMix(int from, int to, float t) {
        int a = Math.round((from >>> 24) + (((to >>> 24) - (from >>> 24)) * t));
        return (a << 24) | 0x00FFFFFF;
    }

    /** How far below the band the black keeps fading into the content (artwork, glow), in dp. */
    private static final float HOLE_FADE_DP = 34.0f;
    private static final float HOLE_ROW_FADE_DP = 16.0f;
    private final android.graphics.Paint bandPaint = new android.graphics.Paint();
    private android.graphics.LinearGradient bandFade;
    private float bandFadeKey = -1.0f;
    private final android.graphics.Matrix bandMatrix = new android.graphics.Matrix();

    /**
     * Paints the top band of a card that wraps the camera hole: pure black down to just under the hole, then a
     * fade into whatever the card shows, so a bright album cover or glow never outlines the hole.
     */
    void drawHoleBand(CardBody body, Canvas canvas, RectF frame, float alpha) {
        if (body.shadeBottom <= 0.0f || alpha <= 0.0f || body.h <= 0.0f) {
            return;
        }
        // The card hangs from the pill's top, so the hole keeps its place on screen while the card grows: the
        // band keeps its true height from the first frame (a band scaled with the card let the cover show
        // around the hole until the card was almost open) and only the fade gives way to a short card.
        float band = Math.min(body.shadeBottom, frame.height());
        // a row next to the hole sits on the black: only a short soft edge, so the artwork starts right under it
        float wanted = body.shadeFade > 0.0f ? body.shadeFade : m.dp(body.holeRow ? HOLE_ROW_FADE_DP : HOLE_FADE_DP);
        float fade = Math.min(wanted, frame.height() - band);
        if (fade < 1.0f) {
            bandPaint.setShader(null);
            bandPaint.setColor(0xFF000000);
            bandPaint.setAlpha(Math.round(255 * Math.min(1.0f, alpha)));
            canvas.drawRect(frame.left, frame.top, frame.right, frame.top + band, bandPaint);
            return;
        }
        int a = Math.round(255 * Math.min(1.0f, alpha));
        bandPaint.setShader(null);
        bandPaint.setColor(0xFF000000);
        bandPaint.setAlpha(a);
        canvas.drawRect(frame.left, frame.top, frame.right, frame.top + band, bandPaint);
        // one unit-high gradient, stretched to the fade and dimmed by the paint: nothing is allocated while the
        // card opens (its fade length and alpha change every frame)
        if (bandFade == null) {
            bandFade = new android.graphics.LinearGradient(0.0f, 0.0f, 0.0f, 1.0f,
                    new int[]{0xFF000000, 0x8C000000, 0x00000000}, new float[]{0.0f, 0.45f, 1.0f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        if (fade != bandFadeKey) {
            bandMatrix.setScale(1.0f, fade);
            bandFade.setLocalMatrix(bandMatrix);
            bandFadeKey = fade;
        }
        int save = canvas.save();
        canvas.translate(0.0f, frame.top + band);
        bandPaint.setShader(bandFade);
        bandPaint.setAlpha(a);
        canvas.drawRect(frame.left, 0.0f, frame.right, fade, bandPaint);
        canvas.restoreToCount(save);
        bandPaint.setShader(null);
    }

    void drawBackground(CardBody body, Canvas canvas, RectF frame, float alpha) {
        try {
            body.drawBackground(canvas, frame, alpha);
        } catch (Throwable t) {
            XLog.once("IslandView.drawBackground", t);
        }
    }

    /**
     * The band of a card opened around the camera hole carries what the compact pill showed, on either side
     * of the hole (icon on the left, value on the right), as iOS keeps an activity's leading and trailing
     * content in the top row of its expanded view. Media and custom app layouts already lead with their own.
     */
    private void drawHoleRow(CardBody body, Canvas canvas, float x, float y, float alpha, long elapsed) {
        IslandItem item = body.item;
        if (body.topInset <= 0.0f || view == null || item == null || item.isMedia || body instanceof RemoteBody) {
            return;
        }
        if (holeRow == null || holeRow.item != item) {
            holeRow = new CompactContent(view, item);
        }
        float inset = m.dp(HOLE_ROW_INSET_DP);
        float midY = y + (m.pillH / 2.0f); // the card's top is the pill's top, so the hole is centred here
        holeRowRect.set(x + inset, midY - (m.pillH / 2.0f), x + body.w - inset, midY + (m.pillH / 2.0f));
        holeRow.draw(canvas, holeRowRect, alpha, elapsed);
    }

    /** A bad notification payload must never take System UI down with an exception inside onDraw. */
    void drawBody(CardBody body, Canvas canvas, float x, float y, float alpha, long elapsed) {
        try {
            body.draw(canvas, x, y + body.topInset, alpha, elapsed);
            drawHoleRow(body, canvas, x, y, alpha, elapsed);
        } catch (Throwable t) {
            if (!drawFailureLogged) {
                drawFailureLogged = true;
                XLog.e("card draw failed for " + body.item, t);
            }
        }
    }
}
