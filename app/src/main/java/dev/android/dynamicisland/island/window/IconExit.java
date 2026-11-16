package dev.android.dynamicisland.island.window;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Takes one status-bar view out of the island's way and brings it back, the way iOS clears the status bar
 * for the Dynamic Island: the view starts shrinking at once, a blur joins in only once it has shrunk past a
 * threshold, and it fades last, while it slides away from the island. One progress value {@code p}
 * (0 = shown, 1 = gone) drives all of it; the curves are {@link #scale}, {@link #blur} and {@link #alpha}.
 *
 * <p>Measured frame by frame on an iPhone 15 recording (60 fps, approximate: filmed by a camera): the clock
 * goes from full size to about 0.7 in roughly 200-230 ms, sharp until about 0.9, then blurred and dimmed
 * together; it comes back about 100 ms after the island starts to shrink, over about 300 ms.
 *
 * <p>Every view shrinks ({@code scale}); only views whose position System UI does not animate itself
 * ({@code slide}) are also moved, since icons inside System UI's icon container have their translation set
 * by it. Main thread only.
 */
final class IconExit {
    /** Size at p = 1. */
    static final float MIN_SCALE = 0.7f;
    /** The blur starts here (scale about 0.91) and reaches its full radius at p = 1. */
    static final float BLUR_FROM = 0.3f;
    /** The fade starts here, so the view stays bright while it blurs. */
    static final float FADE_FROM = 0.45f;
    private static final float MAX_BLUR_DP = 6.0f;
    /** How far the view slides away from the island while it leaves. */
    private static final float SLIDE_DP = 10.0f;
    /** How much a view hit hard by the island's edge shrinks for a moment, and its blur at the hardest hit. */
    static final float SQUEEZE_SCALE = 0.12f;
    private static final float SQUEEZE_BLUR_DP = 2.5f;
    /** A light touch only moves the view; the blur joins past this squeeze, as in the exit. */
    static final float SQUEEZE_BLUR_FROM = 0.35f;
    /** Below this radius (px) the blur is dropped: a render effect costs a layer every frame. */
    private static final float MIN_BLUR_PX = 0.5f;

    private static final long OUT_MS = 220L;
    private static final long IN_MS = 300L;
    private static final long IN_DELAY_MS = 100L;
    private static final PathInterpolator OUT_CURVE = new PathInterpolator(0.4f, 0.0f, 0.6f, 1.0f);
    private static final PathInterpolator IN_CURVE = new PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f);

    private final View view;
    private final boolean scale;
    private final boolean slide;
    private final float density;
    private float progress;
    private boolean hiding;
    /** -1 slides left (the view is left of the island), +1 slides right. */
    private int outward = -1;
    /** Sideways shift (px) that makes room for the island before the view has to leave, as the spring has it now. */
    private float push;
    /** How hard the island's edge is moving the view right now, 0..1 (see {@link IconNudge}). */
    private float squeeze;
    private final IconNudge nudge;
    private boolean captured;
    private float baseTx;
    private float baseScaleX = 1.0f;
    private float baseScaleY = 1.0f;
    private boolean blurred;
    /** Whether the transition alpha is ours to set (the view is, or was just, leaving). */
    private boolean alphaOwned;
    private ValueAnimator running;

    IconExit(View view, boolean scale, boolean slide, float density) {
        this.view = view;
        this.scale = scale || slide;
        this.slide = slide;
        this.density = density;
        this.nudge = new IconNudge(density, (offset, hit) -> {
            push = offset;
            squeeze = hit;
            apply();
        });
    }

    // ---- curves (pure, tested) -----------------------------------------------------------------

    static float scale(float p) {
        return 1.0f - (1.0f - MIN_SCALE) * dev.android.dynamicisland.island.graphics.Easing.clamp01(p);
    }

    /** Blur as a fraction of the full radius: none until {@link #BLUR_FROM}, then growing faster and faster. */
    static float blur(float p) {
        float t = (dev.android.dynamicisland.island.graphics.Easing.clamp01(p) - BLUR_FROM) / (1.0f - BLUR_FROM);
        return t <= 0.0f ? 0.0f : (float) Math.pow(t, 1.5d);
    }

    static float alpha(float p) {
        float t = (dev.android.dynamicisland.island.graphics.Easing.clamp01(p) - FADE_FROM) / (1.0f - FADE_FROM);
        return t <= 0.0f ? 1.0f : 1.0f - t * t;
    }

    // ---- driving -------------------------------------------------------------------------------

    boolean isHiding() {
        return hiding;
    }


    /** The view's position before anything here moved it, for measuring where it would rest. */
    float baseTranslationX() {
        // a view we never slide is positioned by System UI: its live translation is the resting one
        return captured && slide ? baseTx : view.getTranslationX();
    }

    float baseScaleX() {
        return captured ? baseScaleX : view.getScaleX();
    }

    /** Leaves ({@code hide}) or comes back. {@code outwardSign} is -1 when the view is left of the island. */
    void animateTo(boolean hide, int outwardSign) {
        if (outwardSign != 0) {
            outward = outwardSign < 0 ? -1 : 1;
        }
        float target = hide ? 1.0f : 0.0f;
        // an idle view whose alpha was changed behind our back (System UI fading it with the shade) is re-driven
        boolean drifted = running == null && hide && alphaOwned && view.getTransitionAlpha() != alpha(progress);
        if (hide == hiding && !drifted && (running != null || progress == target)) {
            return;
        }
        hiding = hide;
        cancel();
        if (progress == target) {
            apply();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(hide ? OUT_MS : IN_MS);
        animator.setStartDelay(hide ? 0L : IN_DELAY_MS);
        animator.setInterpolator(hide ? OUT_CURVE : IN_CURVE);
        animator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator finished) {
                if (running == finished) {
                    running = null;
                    if (!hiding && pushTarget() == 0.0f && nudge.value() == 0.0f) {
                        restore();
                    }
                }
            }
        });
        running = animator;
        animator.start();
    }

    /**
     * Shifts the view sideways (px, negative = left) to make room for the island. The view gets there on a
     * bouncy spring ({@link IconNudge}), squeezed for a moment by how hard it was hit.
     */
    void setPush(float px) {
        if (!slide) {
            return;
        }
        nudge.setTarget(px);
    }

    /** Where the view is being pushed to, for deciding whether it is still pushed at the end of an animation. */
    private float pushTarget() {
        return nudge.target();
    }

    /** Shrink factor of a hit (1 at rest). */
    static float squeezeScale(float squeeze) {
        return 1.0f - SQUEEZE_SCALE * dev.android.dynamicisland.island.graphics.Easing.clamp01(squeeze);
    }

    /** Blur of a hit as a fraction of its full radius: nothing for a light touch. */
    static float squeezeBlur(float squeeze) {
        float t = (dev.android.dynamicisland.island.graphics.Easing.clamp01(squeeze) - SQUEEZE_BLUR_FROM) / (1.0f - SQUEEZE_BLUR_FROM);
        return t <= 0.0f ? 0.0f : t;
    }

    void cancel() {
        if (running != null) {
            ValueAnimator r = running;
            running = null;
            r.cancel();
        }
    }

    /** Stops and puts the view back exactly as System UI left it. */
    void restore() {
        cancel();
        progress = 0.0f;
        hiding = false;
        nudge.snap(0.0f);
        push = 0.0f;
        squeeze = 0.0f;
        if (alphaOwned) {
            view.setTransitionAlpha(1.0f);
            alphaOwned = false;
        }
        if (blurred) {
            view.setRenderEffect(null);
            blurred = false;
        }
        if (captured) {
            if (slide) {
                view.setTranslationX(baseTx);
            }
            view.setScaleX(baseScaleX);
            view.setScaleY(baseScaleY);
            view.resetPivot();
            captured = false;
        }
    }

    private float blurRadius = -1;

    /** Blur radii rounded to half a pixel, so an animated blur re-creates its RenderEffect only when it shows. */
    static float quantizeBlur(float radius) {
        return Math.max(0.5f, Math.round(radius * 2.0f) / 2.0f);
    }

    private void apply() {
        // a view only pushed aside keeps System UI's own transition alpha (the shade fades it with that)
        if (progress > 0.0f || alphaOwned) {
            view.setTransitionAlpha(alpha(progress));
            alphaOwned = progress > 0.0f;
        }
        float radius = Math.max(blur(progress) * MAX_BLUR_DP, squeezeBlur(squeeze) * SQUEEZE_BLUR_DP) * density;
        if (radius >= MIN_BLUR_PX) {
            // a new native effect per frame otherwise: half-pixel steps look the same and repeat often
            float stepped = quantizeBlur(radius);
            if (!blurred || stepped != blurRadius) {
                view.setRenderEffect(RenderEffect.createBlurEffect(stepped, stepped, Shader.TileMode.DECAL));
                blurRadius = stepped;
            }
            blurred = true;
        } else if (blurred) {
            view.setRenderEffect(null);
            blurred = false;
        }
        if (!scale) {
            return;
        }
        if (!captured) {
            baseTx = view.getTranslationX();
            baseScaleX = view.getScaleX();
            baseScaleY = view.getScaleY();
            captured = true;
        }
        float s = scale(progress) * squeezeScale(squeeze);
        // shrink towards the outer edge, so the view never grows back into the island while it shrinks
        view.setPivotX(outward < 0 ? 0.0f : view.getWidth());
        view.setPivotY(view.getHeight() / 2.0f);
        view.setScaleX(baseScaleX * s);
        view.setScaleY(baseScaleY * s);
        if (slide) {
            view.setTranslationX(baseTx + push + outward * SLIDE_DP * density * dev.android.dynamicisland.island.graphics.Easing.clamp01(progress));
        }
    }
}
