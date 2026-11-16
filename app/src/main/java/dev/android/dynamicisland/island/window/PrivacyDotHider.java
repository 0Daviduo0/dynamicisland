package dev.android.dynamicisland.island.window;

import android.graphics.RectF;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import dev.android.dynamicisland.xposed.Refl;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hides System UI's camera / microphone privacy dot while the island covers it (an expanded island reaches the
 * corner the dot sits in), the same way {@link StatusBarIconFader} takes covered status-bar icons out of the way.
 *
 * <p>The dot is drawn by {@code PrivacyDotViewControllerImpl} in its own corner window; the controller shows and hides
 * the corner container ({@code tl/tr/bl/br}) by its alpha and visibility, so this fades only the container's children
 * and never fights it. Controllers are registered from their constructor; island frames report the covered rectangle.
 * Changes to a dot run on that dot's own thread (its window may live on a background looper).
 */
public final class PrivacyDotHider {
    private static final String[] CORNERS = {"tl", "tr", "bl", "br"};
    private static final long FADE_MS = 160L;
    /** Gap (dp) around the island within which the dot already counts as covered. */
    private static final float COVER_MARGIN_DP = 6.0f;

    private static final ArrayList<WeakReference<Object>> CONTROLLERS = new ArrayList<>();
    /** Corner containers whose dot is faded out right now. */
    private static final Map<View, Boolean> HIDDEN = new WeakHashMap<>();
    private static final RectF ISLAND = new RectF();
    private static final RectF DOT = new RectF();
    private static final int[] POS = new int[2];
    private static boolean islandActive;
    private static float margin;

    private PrivacyDotHider() { }

    /** A privacy dot controller was built (one per display). */
    public static synchronized void register(Object controller) {
        CONTROLLERS.add(new WeakReference<>(controller));
    }

    /** Before the controller shows a corner: a dot appearing under the island stays faded out. */
    public static synchronized void onShowDot(View corner) {
        if (corner != null && covered(corner)) setHidden(corner, true, false);
    }

    /** After every island frame: {@code coverage} is the island's screen rectangle, meaningful while {@code active}. */
    public static synchronized void update(RectF coverage, boolean active, float density) {
        if (CONTROLLERS.isEmpty()) return;
        if (active == islandActive && (!active || ISLAND.equals(coverage))) return;
        islandActive = active;
        if (active) ISLAND.set(coverage);
        margin = COVER_MARGIN_DP * density;
        for (int i = CONTROLLERS.size() - 1; i >= 0; i--) {
            Object controller = CONTROLLERS.get(i).get();
            if (controller == null) {
                CONTROLLERS.remove(i);
                continue;
            }
            for (String name : CORNERS) {
                Object corner = Refl.get(controller, name);
                if (corner instanceof View) sync((View) corner);
            }
        }
    }

    private static void sync(View corner) {
        boolean hide = covered(corner);
        if (hide != HIDDEN.containsKey(corner)) setHidden(corner, hide, true);
    }

    private static boolean covered(View corner) {
        if (!islandActive || !corner.isAttachedToWindow() || corner.getVisibility() != View.VISIBLE) return false;
        Display display = corner.getDisplay();
        if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) return false;
        corner.getLocationOnScreen(POS);
        DOT.set(POS[0], POS[1], POS[0] + corner.getWidth(), POS[1] + corner.getHeight());
        DOT.inset(-margin, -margin);
        return RectF.intersects(DOT, ISLAND);
    }

    private static void setHidden(View corner, boolean hide, boolean animate) {
        if (hide) HIDDEN.put(corner, Boolean.TRUE);
        else HIDDEN.remove(corner);
        float alpha = hide ? 0.0f : 1.0f;
        Runnable apply = () -> {
            if (!(corner instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) corner;
            for (int i = 0; i < group.getChildCount(); i++) {
                View dot = group.getChildAt(i);
                dot.animate().cancel();
                if (animate) dot.animate().alpha(alpha).setDuration(FADE_MS).start();
                else dot.setAlpha(alpha);
            }
        };
        if (!animate || !corner.post(apply)) apply.run();
    }
}
