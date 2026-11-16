package dev.android.dynamicisland.xposed.hooks;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowInsets;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Runs in System UI's {@code :edgelighting} process. The "brief" notification pop-up (Samsung's pill with
 * the app icon and the text, {@code MorphView}) normally drops in from above the screen on a spring and
 * flies back up. Here it comes out of the camera hole instead, and goes back into it.
 *
 * <p>The pill is never scaled out of shape: the pop-up is clipped to a capsule that is always fully
 * round. On the way out a circle holding the app icon comes down out of the hole and, while it is still
 * coming down, opens sideways into the pill (always centred under the hole) as the text fades in. The
 * way back is the same movement backwards, ending in a circle smaller than the hole, inside it.
 *
 * <p>Only the motion changes: Samsung's callbacks (end of the show animation, dismissal of the toast)
 * run where the stock code runs them, and {@code reset()} still puts the view away at the end. Without
 * a camera hole on the top edge, or a pop-up without its listener, the stock animation runs.
 */
public final class BriefPopupHole {
    public static final String PROCESS = "com.android.systemui:edgelighting";
    private static final String MORPH = "com.android.systemui.edgelighting.effect.view.MorphView";

    static final long SHOW_MS = 680;
    static final long HIDE_MS = 520;
    /** For disappear(): the shade is coming down, so out of the way quickly. */
    static final long DISAPPEAR_MS = 300;
    /** The circle at the start / end is this fraction of the hole: fully hidden behind it. */
    static final float HIDDEN_FRACTION = 0.8f;
    /** Share of the time the circle takes to come down from the hole to its place. */
    static final float MOVE_SHARE = 0.62f;
    /** When it starts to open sideways: early, so the drop and the opening are one movement. */
    static final float STRETCH_FROM = 0.14f;
    /** Decelerating, with a light overshoot that shows as a small bounce of the pill. */
    private static final Bezier EASE = new Bezier(0.3f, 1.15f, 0.45f, 1.0f);
    /** Plain smooth curve for the move, so the circle never passes the hole or its place. */
    private static final Bezier MOVE = new Bezier(0.4f, 0.0f, 0.2f, 1.0f);

    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private BriefPopupHole() {
    }

    public static void install(ClassLoader cl) {
        int show = Refl.hookAll(cl, MORPH, "show", SafeHook.before("brief popup show", BriefPopupHole::beforeShow));
        int hide = Refl.hookAll(cl, MORPH, "hide", SafeHook.before("brief popup hide", BriefPopupHole::beforeHide));
        // disappear(): the quick fade used when the pop-up must go at once (the shade is pulled down)
        int disappear = Refl.hookAll(cl, MORPH, "disappear", SafeHook.before("brief popup disappear", BriefPopupHole::beforeDisappear));
        int reset = Refl.hookAll(cl, MORPH, "reset", SafeHook.after("brief popup reset", p -> clear((View) p.thisObject)));
        XLog.i("brief popup hole hooks: show=" + show + " hide=" + hide + " disappear=" + disappear + " reset=" + reset);
    }

    private static void beforeShow(XC_MethodHook.MethodHookParam p) {
        View view = (View) p.thisObject;
        if (Boolean.TRUE.equals(Refl.get(view, "isAnimating"))) return; // stock ignores it too
        Object effect = effectOf(view);
        if (effect == null || hole(view) == null) return;
        Refl.set(view, "isAnimating", Boolean.TRUE);
        p.setResult(null);
        // like the stock code: start on the next loop, after the layout of the new text
        view.post(() -> state(view).run(true, () -> Refl.call(effect, "finishToastPopupAnimation")));
    }

    private static void beforeHide(XC_MethodHook.MethodHookParam p) {
        View view = (View) p.thisObject;
        if (Refl.getBool(view, "mIsHiding", false)) return; // stock logs and ignores it
        Object effect = effectOf(view);
        if (effect == null || hole(view) == null) return;
        Refl.set(view, "mIsHiding", true);
        if (!Boolean.TRUE.equals(Refl.call(view, "isEmptyTickerText"))) Refl.call(effect, "dismissToastPopup");
        p.setResult(null);
        state(view).run(false, () -> {
            view.setAlpha(0.0f); // the pill's shadow goes with it
            Refl.call(view, "reset");
            Refl.set(view, "mIsHiding", false);
        });
    }

    /**
     * Same as Samsung's disappear() (no dismissal callback, reset at the end), but back into the hole and
     * quicker; it also takes over a show animation still running, from where it is.
     */
    private static void beforeDisappear(XC_MethodHook.MethodHookParam p) {
        View view = (View) p.thisObject;
        if (Refl.getBool(view, "mIsHiding", false)) return; // stock ignores it
        if (hole(view) == null) return;
        Refl.set(view, "mIsHiding", true);
        p.setResult(null);
        state(view).run(false, DISAPPEAR_MS, () -> {
            view.setAlpha(0.0f);
            Refl.call(view, "reset");
            Refl.set(view, "mIsHiding", false);
        });
    }

    /** The NotificationEffect behind the pop-up's listener, or null without one. */
    private static Object effectOf(View view) {
        Object listener = Refl.get(view, "mPopupListener");
        return listener == null ? null : Refl.get(listener, "this$0");
    }

    private static Rect hole(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
        Rect top = cutout == null ? null : cutout.getBoundingRectTop();
        return top == null || top.isEmpty() ? null : top;
    }

    private static State state(View view) {
        State state = STATES.get(view);
        if (state == null) {
            state = new State(view);
            STATES.put(view, state);
        }
        return state;
    }

    /** Puts the view's transform, clip and content back after Samsung's reset(). */
    private static void clear(View view) {
        State state = STATES.get(view);
        if (state != null) state.restore();
    }

    /** Text and button: shown only once the pill opens. */
    private static View[] contentOf(View view) {
        java.util.ArrayList<View> out = new java.util.ArrayList<>(2);
        for (String name : new String[]{"mTextRootLayout", "mExpandButton"}) {
            Object v = Refl.get(view, name);
            if (v instanceof View) out.add((View) v);
        }
        return out.toArray(new View[0]);
    }

    private static float clamp(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    /** CSS-style cubic bezier easing (0,0)-(x1,y1)-(x2,y2)-(1,1), in plain Java so the tests can run it. */
    static final class Bezier {
        private final float x1, y1, x2, y2;

        Bezier(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        private static float at(float a, float b, float s) {
            float u = 1.0f - s;
            return 3.0f * u * u * s * a + 3.0f * u * s * s * b + s * s * s;
        }

        float getInterpolation(float x) {
            if (x <= 0.0f) return 0.0f;
            if (x >= 1.0f) return 1.0f;
            float lo = 0.0f, hi = 1.0f, s = x;
            for (int i = 0; i < 24; i++) { // x(s) is monotonic for x1, x2 in [0, 1]
                s = (lo + hi) / 2.0f;
                if (at(x1, x2, s) < x) lo = s; else hi = s;
            }
            return at(y1, y2, s);
        }
    }

    /** One frame of the capsule and of the pop-up's position. Pure math, for the tests. */
    static final class Frame {
        float width;
        float height;
        /** Capsule centre minus pill centre, horizontally (the capsule starts around the icon). */
        float centre;
        float dx;
        float dy;
        /** Uniform scale around the pill's centre: above 1 only during the bounce. */
        float scale;
        float iconAlpha;
        float textAlpha;
    }

    /**
     * First the app icon, in a circle, drops straight down out of the hole; then the circle opens
     * sideways into the pill and the text appears. The way back runs the same frames backwards.
     *
     * @param t      0 = hidden in the hole, 1 = the pill in place (linear time)
     * @param pillW  pill width, pillH its height
     * @param icon   icon centre minus pill centre, horizontally (negative: the icon is on the left)
     * @param fromX  hole centre minus pill centre (window pixels), fromY likewise
     * @param hole   hole diameter
     */
    static Frame frame(float t, float pillW, float pillH, float icon, float fromX, float fromY, float hole, Frame out) {
        float dot = Math.min(hole * HIDDEN_FRACTION, Math.min(pillW, pillH));
        float drop = MOVE.getInterpolation(clamp(t / MOVE_SHARE));
        float grow = EASE.getInterpolation(clamp(t / MOVE_SHARE));
        float stretch = EASE.getInterpolation(clamp((t - STRETCH_FROM) / (1.0f - STRETCH_FROM)));
        float open = Math.min(1.0f, stretch);
        out.height = dot + (pillH - dot) * Math.min(1.0f, grow);
        out.width = out.height + (pillW - out.height) * open;
        out.centre = icon * (1.0f - open);
        // while it drops, the icon is right under the hole; it slides into place as the pill opens
        out.dx = (fromX - icon) * (1.0f - open);
        out.dy = fromY * (1.0f - drop);
        out.scale = 1.0f + Math.max(0.0f, stretch - 1.0f) * 0.5f;
        out.iconAlpha = clamp((t - 0.06f) / 0.2f);
        out.textAlpha = clamp((t - 0.5f) / 0.35f);
        return out;
    }

    /** The running animation of one pop-up view. */
    private static final class State extends ViewOutlineProvider {
        private final java.lang.ref.WeakReference<View> viewRef;
        private final Frame frame = new Frame();
        private final int[] window = new int[2];
        private ValueAnimator animator;
        /** Linear time, 0 = in the hole, 1 = in place; a reverse starts from where it is. */
        private float time;
        private boolean installed;
        private ViewOutlineProvider stockOutline;
        private boolean stockClip;
        private float centreX;
        private float centreY;
        /** The view that carries Samsung's blur and the pill's content, and its bounds at rest. */
        private View blurRoot;
        private final Rect blurRest = new Rect();
        /**
         * Priority ("AI highlight") pop-ups carry the blur on the frame around the pill instead, with a glow
         * layer under it; that blur ignores the clip too, so the frame is fitted to the capsule the same way.
         */
        private View frameRoot;
        private final Rect frameRest = new Rect();
        private View glow;
        private float glowAlpha;
        private float pillLeft;
        private float pillTop;
        private float fromX;
        private float fromY;
        private float pillW;
        private float pillH;
        private float holeSize;
        private float iconOffset;
        private View icon;
        private View[] content = new View[0];

        State(View view) {
            viewRef = new java.lang.ref.WeakReference<>(view);
        }

        @Override
        public void getOutline(View view, Outline outline) {
            float r = frame.height / 2.0f;
            float cx = centreX + frame.centre;
            outline.setRoundRect(Math.round(cx - frame.width / 2.0f), Math.round(centreY - r),
                    Math.round(cx + frame.width / 2.0f), Math.round(centreY + r), r);
        }

        void cancel() {
            if (animator != null) {
                ValueAnimator a = animator;
                animator = null;
                a.removeAllListeners();
                a.cancel();
            }
        }

        void restore() {
            cancel();
            View view = viewRef.get();
            if (view == null) return;
            if (installed) {
                view.setOutlineProvider(stockOutline);
                view.setClipToOutline(stockClip);
                if (blurRoot != null) {
                    blurRoot.setScrollX(0);
                    blurRoot.setScrollY(0);
                    blurRoot.setLeftTopRightBottom(blurRest.left, blurRest.top, blurRest.right, blurRest.bottom);
                    blurRoot.requestLayout();
                    blurRoot = null;
                }
                if (frameRoot != null) {
                    frameRoot.setScrollX(0);
                    frameRoot.setScrollY(0);
                    frameRoot.setLeftTopRightBottom(frameRest.left, frameRest.top, frameRest.right, frameRest.bottom);
                    frameRoot.requestLayout();
                    frameRoot = null;
                }
                if (glow != null) {
                    glow.setAlpha(glowAlpha);
                    glow = null;
                }
                installed = false;
            }
            view.setScaleX(1.0f);
            view.setScaleY(1.0f);
            view.setTranslationX(0.0f);
            for (View content : contentOf(view)) content.setAlpha(1.0f);
            Object icon = Refl.get(view, "mIconRootLayout");
            if (icon instanceof View) ((View) icon).setAlpha(1.0f);
        }

        private void apply(View view) {
            frame(time, pillW, pillH, iconOffset, fromX, fromY, holeSize, frame);
            view.setTranslationX(frame.dx);
            view.setTranslationY(frame.dy);
            view.setScaleX(frame.scale);
            view.setScaleY(frame.scale);
            if (icon != null) icon.setAlpha(frame.iconAlpha);
            for (View c : content) c.setAlpha(frame.textAlpha);
            if (glow != null) glow.setAlpha(glowAlpha * frame.textAlpha);
            fitBlur();
            fitFrame();
            view.invalidateOutline();
        }

        /**
         * Samsung's blur is drawn for the whole bounds of its view and ignores any clip, so that view is
         * resized to the capsule every frame (its corner radius is capped at half the height, so it stays
         * round); a matching scroll keeps the icon and the text where they are.
         */
        private void fitBlur() {
            if (blurRoot == null) return;
            float r = frame.height / 2.0f;
            float cx = centreX + frame.centre;
            // capsule in the blur view's parent (the pill) coordinates
            int left = Math.round(cx - frame.width / 2.0f - pillLeft);
            int top = Math.round(centreY - r - pillTop);
            int right = Math.round(cx + frame.width / 2.0f - pillLeft);
            int bottom = Math.round(centreY + r - pillTop);
            blurRoot.setLeftTopRightBottom(left, top, right, bottom);
            blurRoot.setScrollX(left - blurRest.left);
            blurRoot.setScrollY(top - blurRest.top);
        }

        /** {@link #fitBlur} for the priority frame, whose parent is the pop-up view itself. */
        private void fitFrame() {
            if (frameRoot == null) return;
            float r = frame.height / 2.0f;
            float cx = centreX + frame.centre;
            int left = Math.round(cx - frame.width / 2.0f);
            int top = Math.round(centreY - r);
            int right = Math.round(cx + frame.width / 2.0f);
            int bottom = Math.round(centreY + r);
            frameRoot.setLeftTopRightBottom(left, top, right, bottom);
            frameRoot.setScrollX(left - frameRest.left);
            frameRoot.setScrollY(top - frameRest.top);
        }

        void run(boolean show, Runnable end) {
            run(show, show ? SHOW_MS : HIDE_MS, end);
        }

        void run(boolean show, long fullDuration, Runnable end) {
            View view = viewRef.get();
            if (view == null) return;
            View pill = (View) Refl.get(view, "mToastRootLayout");
            Rect hole = hole(view);
            if (pill == null || hole == null || pill.getWidth() <= 0) {
                // nothing to morph: show or hide at once, the callbacks still run
                if (show) {
                    view.setTranslationY(0.0f);
                    view.setAlpha(1.0f);
                }
                end.run();
                return;
            }
            boolean resumed = animator != null;
            cancel();
            if (!resumed) time = show ? 0.0f : 1.0f;

            // measured once, with the view at rest: while a morph runs, the blur view is resized and
            // scrolled, and measuring again then would put the capsule in the wrong place
            if (!installed) {
                view.setTranslationX(0.0f);
                view.setTranslationY(0.0f);
                view.setScaleX(1.0f);
                view.setScaleY(1.0f);
                float[] centre = centreIn(pill, view);
                centreX = centre[0];
                centreY = centre[1];
                view.setPivotX(centreX);
                view.setPivotY(centreY);
                view.getLocationInWindow(window);
                fromX = hole.exactCenterX() - (window[0] + centreX);
                fromY = hole.exactCenterY() - (window[1] + centreY);
                pillW = pill.getWidth();
                pillH = pill.getHeight();
                holeSize = Math.min(hole.width(), hole.height());
                content = contentOf(view);
                Object iconObj = Refl.get(view, "mIconRootLayout");
                icon = iconObj instanceof View && ((View) iconObj).getWidth() > 0 ? (View) iconObj : null;
                iconOffset = icon == null ? 0.0f : centreIn(icon, view)[0] - centreX;
                pillLeft = centreX - pillW / 2.0f;
                pillTop = centreY - pillH / 2.0f;
                stockOutline = view.getOutlineProvider();
                stockClip = view.getClipToOutline();
                Object blurObj = Refl.get(view, "mToastBlurLayout");
                if (blurObj instanceof View && ((View) blurObj).getParent() == pill) {
                    blurRoot = (View) blurObj;
                    blurRest.set(blurRoot.getLeft(), blurRoot.getTop(), blurRoot.getRight(), blurRoot.getBottom());
                }
                Object frameObj = Refl.get(view, "mFrameContainerLayout");
                if (frameObj instanceof View && ((View) frameObj).getParent() == view && ((View) frameObj).getBackground() != null
                        && pill.getParent() == frameObj) {
                    frameRoot = (View) frameObj;
                    frameRest.set(frameRoot.getLeft(), frameRoot.getTop(), frameRoot.getRight(), frameRoot.getBottom());
                }
                Object glowObj = Refl.get(view, "mToastRootAIVisualLayout");
                if (glowObj instanceof View && ((View) glowObj).getVisibility() == View.VISIBLE) {
                    glow = (View) glowObj;
                    glowAlpha = glow.getAlpha();
                }
                view.setOutlineProvider(this);
                view.setClipToOutline(true);
                installed = true;
            }
            view.setAlpha(1.0f);

            float from = time;
            float to = show ? 1.0f : 0.0f;
            ValueAnimator a = ValueAnimator.ofFloat(from, to);
            a.setInterpolator(null); // linear: the curves are in frame()
            a.setDuration(Math.max(1L, (long) (fullDuration * Math.abs(to - from))));
            a.addUpdateListener(v -> {
                time = (Float) v.getAnimatedValue();
                apply(view);
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) return;
                    animator = null;
                    if (show) restore(); // in place: Samsung's own clip and shadow again
                    end.run();
                }
            });
            animator = a;
            // first frame now, so nothing of the full pill shows before the animation starts
            apply(view);
            a.start();
        }

        /** Centre of {@code child} in {@code ancestor}'s coordinates, ignoring transforms. */
        private static float[] centreIn(View child, View ancestor) {
            float x = child.getWidth() / 2.0f;
            float y = child.getHeight() / 2.0f;
            View v = child;
            while (v != ancestor) {
                x += v.getLeft();
                y += v.getTop();
                ViewParent parent = v.getParent();
                if (!(parent instanceof View)) break;
                v = (View) parent;
                if (v != ancestor) {
                    x -= v.getScrollX();
                    y -= v.getScrollY();
                }
            }
            return new float[]{x, y};
        }
    }
}
