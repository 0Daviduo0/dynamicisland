package dev.android.dynamicisland.island.window;

import android.graphics.RectF;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Space;
import android.widget.TextView;
import dev.android.dynamicisland.xposed.XLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fades the status-bar icons (clock, battery, notification and system icons, ...) that the island
 * would cover, so they do not show through or collide with it.
 *
 * <p>The fader watches SystemUI's status bar by view id name (ids are resolved at runtime because the
 * layout belongs to SystemUI). On every global layout and island move it compares each icon's screen
 * rectangle with the island's and takes it out of the way or brings it back ({@link IconExit}: shrink,
 * then blur, then fade, as iOS does). Single views such as the clock and the battery first slide aside to
 * make room for the island's edge, up to {@link #PUSH_MAX_DP}, and only leave when that is not enough.
 * The clock that shows a date after a double space is faded only from the date onward ({@link ClockDateEraser}).
 * Main thread only.
 */
public final class StatusBarIconFader {
    /** Gap (dp) between island and icon within which the icon already counts as covered. */
    private static final float COVER_MARGIN_DP = 6.0f;
    /** Extra gap (dp) that keeps an already hidden icon hidden, so it does not flicker at the edge. */
    private static final float HYSTERESIS_DP = 4.0f;
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String PUSHED_GROUP_ID = "notificationIcons";
    /** Containers that start to the right of the notification icons; they are where the pushed row has to stop. */
    private static final String[] LIMIT_IDS = {"system_icons", "statusIcons", "battery"};
    /** How far a single view (clock, battery) slides aside for the island before it has to leave instead. */
    private static final float PUSH_MAX_DP = 12.0f;
    /** Room a pushed view keeps from the screen edge, so it never slides into the rounded corner. */
    private static final float EDGE_KEEP_DP = 6.0f;
    /** The system icons container: slid aside as one block, like iOS moves its signal / Wi-Fi / battery group. */
    private static final String CLUSTER_ID = "system_icons";
    private static final float CLUSTER_SQUEEZE_BLUR_DP = 2.5f;

    /** Single status-bar views faded as a unit. */
    private static final String[] LEAF_IDS = {"clock", "battery", "networkSpeed", "knox_status_bar_custom_text_container", "user_switcher_container", "carrier_logo_container", "operator_name_frame"};
    /** Containers whose children are faded one by one. */
    private static final String[] GROUP_IDS = {"notificationIcons", "statusIcons", "system_icons"};
    /** View id name to resource id (0 = not present), cached across instances. */
    private static final Map<String, Integer> IDS = new HashMap<>();
    /** Resource id to id name (null = unnamed), cached across instances. */
    private static final Map<Integer, String> NAMES = new HashMap<>();

    private final float density;
    private final Map<View, IconExit> fades = new WeakHashMap<>();
    private final Map<View, ClockDateEraser> dateErasers = new WeakHashMap<>();
    /** Views that may be faded; rebuilt by {@link #collect()} when the layout changed. */
    private final List<View> candidates = new ArrayList<>();
    private final int[] screenPos = new int[2];
    /** Screen rectangle covered by the island (only meaningful while it is active). */
    private final RectF island = new RectF();
    private final RectF iconBounds = new RectF();
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onStatusBarLayout;
    /** Any status bar redraw may have moved an icon (translation, scale, visibility): re-check on the next update. */
    private final ViewTreeObserver.OnPreDrawListener drawListener = () -> {
        barChanged = true;
        return true;
    };
    /** The status bar drew since the last {@link #apply()}; while false and the island is still, nothing can change. */
    private boolean barChanged = true;

    private View statusBar;
    /** View whose layout passes drive the fader: the bar's contents container if there is one. */
    private View watched;
    private boolean islandActive;
    /** The island shows the battery level itself: the status bar's battery leaves (see {@link #setHideBattery}). */
    private boolean hideBattery;
    private boolean mobileLogged;
    /** The candidate list must be rebuilt before the next {@link #apply()}. */
    private boolean dirty = true;
    /** The notification icon row: pushed aside by the island instead of being faded under it. */
    private ViewGroup pushedRow;
    private float pushTarget;
    /** Shift the row has right now (it rides the island's edge on {@link #rowNudge}, as the system icons do). */
    private float rowPush;
    private float rowBaseTx;
    private float rowBlurRadius = -1;
    private boolean rowBlurred;
    private final IconNudge rowNudge;
    /** Screen x that a pushed icon must not reach; icons past it fade out instead of running into the system icons. */
    private float overflowLimit = Float.MAX_VALUE;
    /** The system icons block and the sideways shift this class gave it (0 = untouched). */
    private View cluster;
    /** Shift the block has right now (it follows the island on {@link #clusterNudge}). */
    private float clusterPush;
    private float clusterBaseTx;
    private float clusterBlurRadius = -1;
    private boolean clusterBlurred;
    private final IconNudge clusterNudge;

    public StatusBarIconFader(float density) {
        this.density = density;
        this.clusterNudge = new IconNudge(density, this::applyClusterPush);
        this.rowNudge = new IconNudge(density, this::applyRowPush);
    }

    /** Starts watching the given PhoneStatusBarView (detaches from the previous one). */
    public void attach(View phoneStatusBarView) {
        if (statusBar == phoneStatusBarView) {
            return;
        }
        detach();
        statusBar = phoneStatusBarView;
        mobileLogged = false;
        View contents = find(phoneStatusBarView, "status_bar_contents");
        watched = contents != null ? contents : phoneStatusBarView;
        watched.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        watched.getViewTreeObserver().addOnPreDrawListener(drawListener);
        dirty = true;
        barChanged = true;
    }

    public void detach() {
        if (watched != null) {
            try {
                watched.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                watched.getViewTreeObserver().removeOnPreDrawListener(drawListener);
            } catch (Throwable ignored) {
                // observer already dead
            }
        }
        resetAll();
        statusBar = null;
        watched = null;
        candidates.clear();
    }

    /**
     * Takes the battery view out with the same exit as a covered icon while the island itself shows the battery
     * (charging), so the level is not shown twice; it comes back when that activity ends.
     */
    public void setHideBattery(boolean hide) {
        if (hide == hideBattery) return;
        hideBattery = hide;
        barChanged = true;
    }

    /**
     * Updates which area the island covers.
     *
     * @param coverage screen rectangle of the island; only used when {@code active}
     * @param active   whether the island is currently shown (inactive = restore all icons)
     */
    public void update(RectF coverage, boolean active) {
        if (!active && !islandActive && !dirty) {
            return;
        }
        // called every island frame: skip while neither the island area nor the status bar changed
        if (!dirty && !barChanged && active == islandActive && (!active || island.equals(coverage))) {
            return;
        }
        islandActive = active;
        if (active) {
            island.set(coverage);
        }
        apply();
    }

    /** Cancels every running fade and shows all icons again. */
    void resetAll() {
        restoreRow();
        for (IconExit exit : fades.values()) {
            exit.restore();
        }
        fades.clear();
        for (ClockDateEraser eraser : dateErasers.values()) {
            eraser.reset();
        }
        dateErasers.clear();
    }

    private void restoreRow() {
        resetCluster();
        rowNudge.snap(0.0f);
        if (pushedRow != null && (rowPush != 0.0f || rowBlurred)) {
            applyRowPush(0.0f, 0.0f);
        }
        rowPush = 0.0f;
        pushTarget = 0.0f;
        overflowLimit = Float.MAX_VALUE;
    }

    private void onStatusBarLayout() {
        dirty = true;
        apply();
    }

    private void apply() {
        try {
            if (statusBar == null) {
                return;
            }
            barChanged = false;
            if (dirty) {
                collect();
            }
            updatePush();
            updateClusterPush();
            for (View candidate : candidates) {
                updateCandidate(candidate);
            }
        } catch (Throwable t) {
            // runs from a global layout listener: an exception here would take System UI down
            XLog.once("StatusBarIconFader.apply", t);
        }
    }

    // ---- pushing the notification icons aside ---------------------------------------------------

    /**
     * The island over the notification icons moves the whole row out of its way (it slides to the island's
     * far side) instead of hiding the icons; what no longer fits before the system icons fades.
     */
    private void updatePush() {
        ViewGroup row = pushedRow;
        if (row == null || !row.isAttachedToWindow() || row.getWidth() == 0) {
            overflowLimit = Float.MAX_VALUE;
            return;
        }
        row.getLocationOnScreen(screenPos);
        float margin = density * COVER_MARGIN_DP;
        float baseLeft = screenPos[0] - rowPush;
        float baseRight = baseLeft + contentWidth(row);
        float target = 0.0f;
        boolean sharesRows = islandActive && screenPos[1] < island.bottom && screenPos[1] + row.getHeight() > island.top;
        if (sharesRows && island.right + margin > baseLeft && island.left - margin < baseRight && baseRight > baseLeft) {
            target = island.right + margin - baseLeft;
        }
        if (target != pushTarget) {
            pushTarget = target;
            rowNudge.setTarget(target);
        }
        overflowLimit = target > 0.0f ? limitX() : Float.MAX_VALUE;
    }

    /**
     * Moves the notification icon row to {@code px} the way {@link #applyClusterPush} moves the system icons:
     * on the same spring, squeezed and blurred for a moment towards its far end by how hard the island hit it.
     */
    private void applyRowPush(float px, float squeeze) {
        ViewGroup row = pushedRow;
        if (row == null) {
            return;
        }
        boolean moved = px != 0.0f || squeeze > 0.0f;
        if (moved && rowPush == 0.0f && !rowBlurred) {
            rowBaseTx = row.getTranslationX();
        }
        rowPush = px;
        row.setTranslationX(rowBaseTx + px);
        float s = IconExit.squeezeScale(squeeze);
        row.setPivotX(contentWidth(row));
        row.setPivotY(row.getHeight() / 2.0f);
        row.setScaleX(s);
        row.setScaleY(s);
        float radius = IconExit.squeezeBlur(squeeze) * CLUSTER_SQUEEZE_BLUR_DP * density;
        if (radius >= 0.5f) {
            float stepped = IconExit.quantizeBlur(radius);
            if (!rowBlurred || stepped != rowBlurRadius) {
                row.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(stepped, stepped,
                        android.graphics.Shader.TileMode.DECAL));
                rowBlurRadius = stepped;
            }
            rowBlurred = true;
        } else if (rowBlurred) {
            row.setRenderEffect(null);
            rowBlurred = false;
        }
        if (!moved) {
            row.resetPivot();
        }
    }

    // ---- sliding the system icons aside --------------------------------------------------------

    /**
     * Slides the whole system icons block away from the island's edge, following it frame by frame (so it
     * rides the island's spring), up to {@link #PUSH_MAX_DP} and never into the screen's corner. Icons the
     * island still covers after that leave one by one in {@link #updateCandidate}.
     */
    private void updateClusterPush() {
        float base = clusterPushFor();
        // the battery left: the other icons slide over into its place (and back when it returns)
        setClusterPush(base + batteryGapShift());
    }

    /** How far the block moves aside for the island's edge. */
    private float clusterPushFor() {
        View block = cluster;
        if (block == null || !islandActive || !block.isAttachedToWindow() || block.getWidth() == 0
                || block.getVisibility() != View.VISIBLE || !(block instanceof ViewGroup)) {
            return 0.0f;
        }
        block.getLocationOnScreen(screenPos);
        float restingLeft = screenPos[0] - clusterPush; // where it sits without our shift
        float firstIcon = Float.MAX_VALUE;
        ViewGroup group = (ViewGroup) block;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) {
                firstIcon = Math.min(firstIcon, restingLeft + child.getLeft());
            }
        }
        boolean sharesRows = screenPos[1] < island.bottom && screenPos[1] + block.getHeight() > island.top;
        if (firstIcon == Float.MAX_VALUE || !sharesRows || firstIcon < island.centerX()) {
            return 0.0f;
        }
        float overlap = island.right + density * COVER_MARGIN_DP - firstIcon;
        float restingRight = restingLeft + block.getWidth();
        float room = statusBar.getRootView().getWidth() - density * EDGE_KEEP_DP - restingRight;
        float max = Math.max(0.0f, Math.min(density * PUSH_MAX_DP, room));
        return Math.max(0.0f, Math.min(overlap, max));
    }

    /** Width the hidden battery leaves free at the end of the system icons (with the gap before it), else 0. */
    private float batteryGapShift() {
        if (!hideBattery || !islandActive || !(cluster instanceof ViewGroup)) return 0.0f;
        ViewGroup group = (ViewGroup) cluster;
        View last = group.getChildCount() == 0 ? null : group.getChildAt(group.getChildCount() - 1);
        if (last == null || last.getVisibility() != View.VISIBLE || last.getWidth() == 0 || !isBattery(last)) return 0.0f;
        float prevRight = last.getLeft();
        for (int i = group.getChildCount() - 2; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) {
                prevRight = child.getRight();
                break;
            }
        }
        return last.getRight() - prevRight;
    }

    /** Where the block should be shifted to; it gets there on a bouncy spring, see {@link #applyClusterPush}. */
    private void setClusterPush(float px) {
        if (cluster == null) {
            return;
        }
        clusterNudge.setTarget(px);
    }

    /**
     * Moves the block to {@code px}, shrunk towards its outer edge and blurred for a moment by how hard the
     * island's edge hit it ({@code squeeze}), as each single view is in {@link IconExit}.
     */
    private void applyClusterPush(float px, float squeeze) {
        View block = cluster;
        if (block == null) {
            return;
        }
        boolean moved = px != 0.0f || squeeze > 0.0f;
        if (moved && clusterPush == 0.0f && !clusterBlurred && unclipped.isEmpty()) {
            clusterBaseTx = block.getTranslationX(); // whatever System UI had set stays the base
            unclip(block);
        }
        clusterPush = px;
        block.setTranslationX(clusterBaseTx + px);
        float s = IconExit.squeezeScale(squeeze);
        block.setPivotX(block.getWidth());
        block.setPivotY(block.getHeight() / 2.0f);
        block.setScaleX(s);
        block.setScaleY(s);
        float radius = IconExit.squeezeBlur(squeeze) * CLUSTER_SQUEEZE_BLUR_DP * density;
        if (radius >= 0.5f) {
            float stepped = IconExit.quantizeBlur(radius);
            if (!clusterBlurred || stepped != clusterBlurRadius) {
                block.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(stepped, stepped,
                        android.graphics.Shader.TileMode.DECAL));
                clusterBlurRadius = stepped;
            }
            clusterBlurred = true;
        } else if (clusterBlurred) {
            block.setRenderEffect(null);
            clusterBlurred = false;
        }
        if (!moved) {
            block.resetPivot();
            restoreClips();
        }
    }

    /** Puts the block back at once, as System UI left it. */
    private void resetCluster() {
        clusterNudge.snap(0.0f);
        if (cluster != null && (clusterPush != 0.0f || clusterBlurred || !unclipped.isEmpty())) {
            applyClusterPush(0.0f, 0.0f);
        }
        clusterPush = 0.0f;
    }

    /** Containers whose clipping was turned off, with what they had: bit 0 clipChildren, bit 1 clipToPadding. */
    private final Map<ViewGroup, Integer> unclipped = new java.util.HashMap<>();

    /**
     * Lets the block slide past its containers' bounds: each one up to the status bar would cut off the part
     * that moved out (the battery at the far right), so their clipping is off while the block is pushed.
     */
    private void unclip(View block) {
        if (statusBar == null) {
            return;
        }
        for (android.view.ViewParent p = block.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (!unclipped.containsKey(g)) {
                unclipped.put(g, (g.getClipChildren() ? 1 : 0) | (g.getClipToPadding() ? 2 : 0));
                g.setClipChildren(false);
                g.setClipToPadding(false);
            }
            if (g == statusBar) {
                break;
            }
        }
    }

    private void restoreClips() {
        for (Map.Entry<ViewGroup, Integer> e : unclipped.entrySet()) {
            e.getKey().setClipChildren((e.getValue() & 1) != 0);
            e.getKey().setClipToPadding((e.getValue() & 2) != 0);
        }
        unclipped.clear();
    }

    /** Right end of the visible icons, relative to the row. */
    private static float contentWidth(ViewGroup row) {
        float right = 0.0f;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                right = Math.max(right, child.getLeft() + child.getWidth());
            }
        }
        return right;
    }

    private float limitX() {
        float limit = Float.MAX_VALUE;
        for (String id : LIMIT_IDS) {
            View view = find(statusBar, id);
            if (view != null && view.isAttachedToWindow() && view.getWidth() > 0) {
                view.getLocationOnScreen(screenPos);
                limit = Math.min(limit, screenPos[0] - density * COVER_MARGIN_DP);
            }
        }
        return limit;
    }

    // ---- per-icon decision ---------------------------------------------------------------------

    private void updateCandidate(View icon) {
        IconExit fade = fades.get(icon);
        boolean wasHidden = fade != null && fade.isHiding();
        float margin = density * COVER_MARGIN_DP;
        float stickyMargin = margin + density * HYSTERESIS_DP;
        boolean leaf = isLeaf(icon);

        boolean covered = false;
        boolean dateCovered = false;
        float dateX = -1.0f;
        float push = 0.0f;
        int outward = 0;
        if (islandActive && icon.isAttachedToWindow() && icon.getWidth() > 0 && icon.getVisibility() == View.VISIBLE) {
            restingBounds(icon, fade);
            float wholeMargin = wasHidden ? stickyMargin : margin;
            boolean inPushedRow = icon.getParent() == pushedRow;
            boolean sharesRows = iconBounds.bottom > island.top && iconBounds.top < island.bottom;
            dateX = dateStart(icon);
            if (dateX > 0.0f) {
                // clock with a date: either the date part is covered, or the island sits over the time part
                float splitScreenX = iconBounds.left + icon.getScaleX() * dateX;
                ClockDateEraser eraser = dateErasers.get(icon);
                float dateMargin = (eraser != null && eraser.isHiding()) ? stickyMargin : margin;
                dateCovered = sharesRows && iconBounds.right > island.left - dateMargin && splitScreenX < island.right + dateMargin;
                covered = !sharesRows && splitScreenX > island.left - wholeMargin && iconBounds.left < island.right + wholeMargin;
            } else if (inPushedRow && pushTarget > 0.0f) {
                // pushed past the island: only what runs into the system icons goes, judged where the row is
                // heading (its spring overshoots on the way), and it leaves towards them like the other icons
                covered = iconBounds.right + (pushTarget - rowPush) > overflowLimit;
                outward = 1;
            } else {
                outward = iconBounds.centerX() < island.centerX() ? -1 : 1;
                // how far the view would have to move away from the island to clear it
                float overlap = !sharesRows ? 0.0f : outward < 0
                        ? iconBounds.right + wholeMargin - island.left
                        : island.right + wholeMargin - iconBounds.left;
                if (overlap > 0.0f) {
                    float maxPush = leaf ? leafRoom(icon, outward) : 0.0f;
                    if (leaf && overlap <= maxPush) {
                        push = outward * overlap; // slides aside, riding the island's edge (and its bounce)
                    } else {
                        covered = true;
                        push = leaf ? outward * maxPush : 0.0f; // leaves from where it was pushed to
                    }
                }
            }
        }

        if (hideBattery && islandActive && !covered && icon.getVisibility() == View.VISIBLE && isBattery(icon)) {
            covered = true;
            if (outward == 0) outward = 1;
        }

        if (dateX > 0.0f || dateErasers.containsKey(icon)) {
            ClockDateEraser eraser = dateErasers.get(icon);
            if (eraser == null) {
                eraser = new ClockDateEraser((TextView) icon);
                dateErasers.put(icon, eraser);
            }
            if (dateX > 0.0f) {
                eraser.setSplit(dateX);
            }
            eraser.animateTo(dateCovered && !covered ? 0.0f : 1.0f);
        }

        if (fade == null) {
            if (!covered && push == 0.0f) {
                // never touched by the island: whatever transition alpha it has is System UI's own (it fades the
                // icons in and out with the shade), and taking it over would fight that animation
                return;
            }
            // the whole block already slid aside: its own children still shrink, and the ones System UI does not
            // position (the battery) also slide on as they leave
            boolean inCluster = insideCluster(icon);
            // every icon shrinks as it leaves (notification ones included), so they all go the same way
            fade = new IconExit(icon, true, leaf || (inCluster && icon.getParent() == cluster), density);
            fades.put(icon, fade);
        }
        fade.setPush(push);
        fade.animateTo(covered, outward);
    }

    /** How far a single view may slide outward: {@link #PUSH_MAX_DP}, less what would take it into the corner. */
    private float leafRoom(View icon, int outward) {
        float keep = density * EDGE_KEEP_DP;
        float room = outward < 0 ? iconBounds.left - keep : statusBar.getRootView().getWidth() - keep - iconBounds.right;
        return Math.max(0.0f, Math.min(density * PUSH_MAX_DP, room));
    }

    /**
     * Single views (clock ...) that System UI does not position itself, so they may be moved and scaled.
     * Views inside the system icons block move with the block instead.
     */
    private boolean isLeaf(View icon) {
        String name = idName(icon);
        if (name == null || icon.getParent() == pushedRow || insideCluster(icon)) {
            return false;
        }
        for (String id : LEAF_IDS) {
            if (id.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean insideCluster(View view) {
        if (cluster == null) {
            return false;
        }
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p == cluster) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets {@link #iconBounds} to where the view rests on screen without anything this class did to it, so
     * pushing a view aside never changes the decision that pushed it.
     */
    private void restingBounds(View icon, IconExit exit) {
        // any view we shrink or slide is measured where it rests: measuring it mid-exit would find it clear of
        // the island, bring it back, find it covered again, and so on
        if (exit == null || !(icon.getParent() instanceof View)) {
            icon.getLocationOnScreen(screenPos);
            iconBounds.set(screenPos[0], screenPos[1],
                    screenPos[0] + icon.getWidth() * icon.getScaleX(),
                    screenPos[1] + icon.getHeight() * icon.getScaleY());
            return;
        }
        ((View) icon.getParent()).getLocationOnScreen(screenPos);
        float scale = exit.baseScaleX();
        float cx = screenPos[0] + icon.getLeft() + exit.baseTranslationX() + icon.getWidth() / 2.0f;
        float half = icon.getWidth() * scale / 2.0f;
        float top = screenPos[1] + icon.getTop();
        iconBounds.set(cx - half, top, cx + half, top + icon.getHeight());
    }

    /**
     * X offset inside the clock where its date part starts (the text after a double space), or -1
     * when the clock shows no date or the layout is not usable.
     */
    private static float dateStart(View view) {
        if (!(view instanceof TextView) || !"clock".equals(idName(view))) {
            return -1.0f;
        }
        TextView clock = (TextView) view;
        CharSequence text = clock.getText();
        Layout layout = clock.getLayout();
        if (text == null || layout == null || clock.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return -1.0f;
        }
        String string = text.toString();
        int gap = string.indexOf("  ");
        if (gap <= 0 || string.trim().length() <= gap) {
            return -1.0f;
        }
        float x = layout.getPrimaryHorizontal(gap) + clock.getCompoundPaddingLeft() - clock.getScrollX();
        return (x > 0.0f && x < clock.getWidth()) ? x : -1.0f;
    }

    // ---- candidate discovery -------------------------------------------------------------------

    /** Rebuilds {@link #candidates} and restores icons that dropped out of the list. */
    private void collect() {
        dirty = false;
        Set<View> found = new LinkedHashSet<>();
        for (String id : LEAF_IDS) {
            View leaf = find(statusBar, id);
            if (leaf != null) {
                found.add(leaf);
            }
        }
        View row = find(statusBar, PUSHED_GROUP_ID);
        if (row != pushedRow) {
            restoreRow();
            pushedRow = row instanceof ViewGroup ? (ViewGroup) row : null;
        }
        View block = find(statusBar, CLUSTER_ID);
        if (block != cluster) {
            resetCluster();
            cluster = block;
        }
        for (String id : GROUP_IDS) {
            View group = find(statusBar, id);
            if (group instanceof ViewGroup) {
                collectGroupChildren(id, (ViewGroup) group, found);
            }
        }
        for (View previous : candidates) {
            if (!found.contains(previous)) {
                IconExit stale = fades.remove(previous);
                if (stale != null) {
                    stale.restore();
                } else {
                    previous.setTransitionAlpha(1.0f);
                }
            }
        }
        candidates.clear();
        candidates.addAll(found);
    }

    private void collectGroupChildren(String groupId, ViewGroup group, Set<View> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String childName = idName(child);
            // statusIcons and battery are handled on their own (leaf ids or nested group)
            if ("statusIcons".equals(childName) || "battery".equals(childName)) {
                continue;
            }
            if (isMobileGroup(child)) {
                // fade each mobile signal/type/roaming glyph on its own instead of the whole combo
                List<View> glyphs = new ArrayList<>();
                collectGlyphs(child, glyphs);
                out.addAll(glyphs);
                logMobileGlyphsOnce(glyphs);
            } else if (!"system_icons".equals(groupId) || !(child instanceof ViewGroup) || childName == null) {
                out.add(child);
            }
        }
    }

    private void logMobileGlyphsOnce(List<View> glyphs) {
        if (mobileLogged) {
            return;
        }
        mobileLogged = true;
        StringBuilder names = new StringBuilder();
        for (View glyph : glyphs) {
            names.append(idName(glyph)).append(' ');
        }
        XLog.i("independent mobile icons: " + names);
    }

    private static boolean isMobileGroup(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        String id = idName(view);
        return view.getClass().getSimpleName().contains("StatusBarMobileView")
                || "mobile_group".equals(id) || "mobile_combo".equals(id)
                || (find(view, "mobile_signal") != null && find(view, "mobile_type") != null);
    }

    private static void collectGlyphs(View view, List<View> out) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectGlyphs(group.getChildAt(i), out);
            }
        } else if (!(view instanceof Space) && !out.contains(view)) {
            out.add(view);
        }
    }

    // ---- SystemUI id lookup --------------------------------------------------------------------

    /**
     * The battery meter: the "battery" id on AOSP; on One UI 9 an unnamed Compose view, the last one of the
     * system icons block.
     */
    private static boolean isBattery(View view) {
        if ("battery".equals(idName(view)) || view.getClass().getName().contains("Battery")) return true;
        if (!(view.getParent() instanceof ViewGroup) || !view.getClass().getSimpleName().equals("ComposeView")) return false;
        ViewGroup parent = (ViewGroup) view.getParent();
        return CLUSTER_ID.equals(idName(parent)) && parent.getChildAt(parent.getChildCount() - 1) == view;
    }

    /** Finds a SystemUI view by its id name; null when SystemUI has no such id. */
    private static View find(View root, String idName) {
        Integer id = IDS.get(idName);
        if (id == null) {
            id = root.getResources().getIdentifier(idName, "id", SYSTEM_UI);
            IDS.put(idName, id);
        }
        return id == 0 ? null : root.findViewById(id);
    }

    private static String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return null;
        }
        if (NAMES.containsKey(id)) {
            return NAMES.get(id);
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            name = null;
        }
        NAMES.put(id, name);
        return name;
    }
}
