package dev.android.dynamicisland.island.window;

import android.view.Choreographer;
import dev.android.dynamicisland.island.graphics.Spring;

/**
 * Makes a status-bar view react to the island's edge like something soft that was bumped, instead of being
 * glued to it: the view's sideways shift chases the shift the island asks for on a bouncy spring, so it
 * lags a touch behind a fast edge, overshoots when the edge stops or bounces back, and settles. How fast
 * it is moving becomes {@code squeeze} (0..1), which the owner turns into a brief shrink and blur.
 *
 * <p>Runs its own frames on the main thread's {@link Choreographer} until the spring rests, because the
 * island stops sending frames as soon as it settles while the view may still be swinging. Main thread only.
 */
final class IconNudge implements Choreographer.FrameCallback {
    /** Receives the shift (px) and the squeeze (0..1) every frame the spring moves. */
    interface Sink {
        void apply(float offset, float squeeze);
    }

    /** Quick enough to stay close to the island's edge, loose enough to bounce once when it stops. */
    static final float RESPONSE = 0.3f;
    static final float DAMPING = 0.55f;
    /** Speed (dp/s) that counts as a full squeeze. */
    private static final float FULL_SQUEEZE_DP_S = 700.0f;

    private final Spring spring = new Spring(0.0f, RESPONSE, DAMPING).setValueThreshold(0.25f);
    private final Sink sink;
    private final float fullSqueeze;
    private boolean scheduled;
    private long lastFrameNanos;

    IconNudge(float density, Sink sink) {
        this.sink = sink;
        this.fullSqueeze = FULL_SQUEEZE_DP_S * density;
    }

    float value() {
        return spring.value();
    }

    float target() {
        return spring.target();
    }

    /** How hard the view is moving right now, 0..1. */
    float squeeze() {
        return Math.min(1.0f, Math.abs(spring.velocity()) / fullSqueeze);
    }

    void setTarget(float px) {
        if (px == spring.target() && spring.isAtRest()) {
            return;
        }
        spring.animateTo(px);
        schedule();
    }

    /** Stops at {@code px} with no motion and without telling the sink (used when putting the view back). */
    void snap(float px) {
        spring.snapTo(px);
        if (scheduled) {
            Choreographer.getInstance().removeFrameCallback(this);
            scheduled = false;
        }
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            lastFrameNanos = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        scheduled = false;
        float dt = lastFrameNanos == 0L ? 1.0f / 60.0f : (frameTimeNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameTimeNanos;
        boolean moving = spring.step(dt);
        sink.apply(spring.value(), moving ? squeeze() : 0.0f);
        if (moving) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
