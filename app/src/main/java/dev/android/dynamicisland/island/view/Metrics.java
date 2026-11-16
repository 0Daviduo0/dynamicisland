package dev.android.dynamicisland.island.view;

import dev.android.dynamicisland.settings.ModuleSettings;

/**
 * Sizes of the island in pixels plus where the camera hole sits. The dimensions derive from the display
 * density once; the hole geometry and the limits that depend on the screen are refreshed by
 * {@link #update}. Main thread only.
 */
final class Metrics {
    /** Gap in dp between a window's content and its bottom edge. */
    private static final float WINDOW_MARGIN_DP = 40.0f;

    private final float density;

    float pillH;
    final float padding;
    final float holeGap;
    final float innerGap;
    float leadSize;
    float bubbleR;
    final float bubbleGap;
    float minPillW;
    float ringSize;
    final float waveBarW;
    final float waveGap;
    final float outlineW;
    final float cardRadius;
    final float cardGap;

    float cardMargin;
    float cardMaxW;
    float maxPillW;
    /** The user's widest pill in px, 0 = automatic. */
    private float userMaxW;
    int screenW;
    float cx;
    float cy;
    float holeD;
    boolean tablet;

    Metrics(float density) {
        this.density = density;
        pillH = dp(30.0f);
        padding = dp(9.0f);
        holeGap = dp(6.0f);
        innerGap = dp(8.0f);
        leadSize = dp(20.0f);
        bubbleR = dp(15.0f);
        bubbleGap = dp(6.0f);
        minPillW = dp(64.0f);
        ringSize = dp(17.0f);
        waveBarW = dp(2.6f);
        waveGap = dp(2.2f);
        outlineW = Math.max(1.5f, dp(0.7f));
        cardRadius = dp(26.0f);
        cardGap = dp(8.0f);
        cardMargin = dp(12.0f);
        cardMaxW = dp(420.0f);
    }

    /**
     * Sets the size the user asked for: the pill's height as a percent change (its icons grow with it), the narrowest
     * and the widest it gets in dp (0 = automatic). Returns false when nothing changed.
     */
    boolean applySize(int heightPercent, int minWidthDp, int maxWidthDp) {
        float scale = 1.0f + (heightPercent / 100.0f);
        float newH = dp(30.0f) * scale;
        float newMin = Math.min(Math.max(dp(64.0f), dp(minWidthDp)), Math.max(dp(64.0f), screenW * 0.96f));
        float newMax = maxWidthDp > 0 ? dp(maxWidthDp) : 0.0f;
        if (newMax > 0.0f) {
            newMin = Math.min(newMin, newMax); // the narrowest never passes the widest
        }
        if (Math.abs(pillH - newH) < 0.5f && Math.abs(minPillW - newMin) < 0.5f && Math.abs(userMaxW - newMax) < 0.5f) {
            return false;
        }
        pillH = newH;
        leadSize = dp(20.0f) * scale;
        ringSize = dp(17.0f) * scale;
        bubbleR = dp(15.0f) * scale;
        minPillW = newMin;
        userMaxW = newMax;
        recomputeMaxWidth();
        return true;
    }

    /** Room the empty pill leaves around the camera hole, in total. */
    static final float IDLE_MARGIN_DP = 16.0f;
    /** How wide the pill may get when the widest is left on automatic: a little over half the screen, at most 232 dp. */
    static final float AUTO_MAX_SCREEN_SHARE = 0.56f;
    static final float AUTO_MAX_PHONE_DP = 232.0f;
    static final float AUTO_MAX_TABLET_DP = 300.0f;
    static final float AUTO_MAX_TABLET_SHARE = 0.40f;

    private void recomputeMaxWidth() {
        float automatic = tablet ? Math.min(screenW * AUTO_MAX_TABLET_SHARE, dp(AUTO_MAX_TABLET_DP))
                : Math.min(screenW * AUTO_MAX_SCREEN_SHARE, dp(AUTO_MAX_PHONE_DP));
        maxPillW = userMaxW > 0.0f ? Math.max(minPillW, Math.min(userMaxW, screenW * 0.96f)) : Math.max(minPillW, automatic);
    }

    float dp(float v) {
        return density * v;
    }

    /** Stores a new display geometry; returns false (and changes nothing) when it equals the current one. */
    boolean update(int screenWidth, float centerX, float centerY, float holeDiameter, boolean isTablet) {
        boolean same = screenW == screenWidth && Math.abs(cx - centerX) <= 1.5f && Math.abs(cy - centerY) <= 1.5f
                && Math.abs(holeD - holeDiameter) <= 1.5f && tablet == isTablet;
        if (same) {
            return false;
        }
        screenW = screenWidth;
        cx = centerX;
        cy = centerY;
        holeD = holeDiameter;
        tablet = isTablet;
        recomputeMaxWidth();
        cardMargin = dp(isTablet ? 16.0f : 12.0f);
        cardMaxW = dp(isTablet ? 380.0f : 420.0f);
        return true;
    }

    /** Width of the resting (empty) pill: just inside the camera hole, or a small blob without one. */
    float restWidth() {
        return holeD > 0.0f ? holeD * 0.92f : pillH * 0.45f;
    }

    /** Width of the empty pill that is kept on screen when the user wants the island always visible: a little wider than the camera. */
    float idleWidth() {
        return Math.max(minPillW, holeD + dp(IDLE_MARGIN_DP));
    }

    float cardWidth() {
        if (ModuleSettings.expandAroundHole() && !tablet) {
            // the card hangs from the pill's top: keep the same gap at the sides as above it
            return screenW - (aroundHoleGap() * 2.0f);
        }
        return Math.min(screenW - (cardMargin * 2.0f), cardMaxW);
    }

    /** Gap between a card opened around the camera hole and the screen's top edge, used at its sides too. */
    float aroundHoleGap() {
        return Math.max(dp(4.0f), cy - (pillH / 2.0f));
    }

    /** Smallest band the camera hole needs above a card's content, below the hole's bottom edge. */
    static final float HOLE_BAND_GAP_DP = 6.0f;

    /**
     * Height of the black band at the top of the first card that holds the camera hole when the island opens
     * around it, from the card's top (the pill's top) to just under the hole; 0 when cards open below it.
     */
    float holeBand() {
        if (!ModuleSettings.expandAroundHole()) {
            return 0.0f;
        }
        float holeBottom = cy + (Math.max(holeD, pillH) / 2.0f);
        return holeBottom - (cy - (pillH / 2.0f)) + dp(HOLE_BAND_GAP_DP);
    }

    /** Room kept free at each side of the camera hole, and below it, by content laid out next to it. */
    static final float HOLE_SIDE_GAP_DP = 14.0f;
    static final float HOLE_BELOW_GAP_DP = 4.0f;

    /**
     * Tells the first card where the camera hole sits in its own coordinates when the island opens around it,
     * so the body can put a row next to the hole (or, if it cannot, keep a band free above its content).
     */
    void fitAroundHole(dev.android.dynamicisland.island.view.card.CardBody body) {
        if (!ModuleSettings.expandAroundHole()) {
            body.clearHole();
            return;
        }
        float top = stackTop();
        float left = centredLeft(body.w);
        float r = Math.max(holeD, dp(10.0f)) / 2.0f;
        float side = r + dp(HOLE_SIDE_GAP_DP);
        body.fitHole(cy - top, (cx - left) - side, (cx - left) + side, (cy - top) + r + dp(HOLE_BELOW_GAP_DP), pillH, holeBand());
    }

    float stackTop() {
        if (ModuleSettings.expandAroundHole()) {
            return cy - (pillH / 2.0f); // the card grows out of the pill's own top, so it wraps the camera hole
        }
        return cy + (pillH / 2.0f) + dp(8.0f);
    }

    /** Window height that fits the compact pill with some room below it for its shadow and bounce. */
    int compactWindowHeight() {
        return Math.round(cy + (pillH / 2.0f) + dp(WINDOW_MARGIN_DP));
    }

    /** Window height that fits content ending at {@code bottom}. */
    int windowHeightFor(float bottom) {
        return Math.round(dp(WINDOW_MARGIN_DP) + bottom);
    }

    /** Left edge of a content block of width {@code w} centred under the hole but kept inside the margins. */
    float centredLeft(float w) {
        float left = cx - (w / 2.0f);
        if (ModuleSettings.expandAroundHole() && !tablet && w > screenW - (cardMargin * 2.0f)) {
            return (screenW - w) / 2.0f; // wider than the usual margins allow: equal gaps on both sides
        }
        if (w < screenW) {
            left = Math.max(Math.min(left, (screenW - cardMargin) - w), cardMargin);
        }
        return left;
    }
}
