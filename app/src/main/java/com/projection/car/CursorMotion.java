package com.projection.car;

/** Visual-only interpolation. Input targets and gesture coordinates never depend on it. */
final class CursorMotion {
    static final long TRANSITION_MS = 24;
    private float x, y, startX, startY, targetX, targetY;
    private long startTime;
    private boolean moving;

    void snap(float x, float y) {
        this.x = startX = targetX = x;
        this.y = startY = targetY = y;
        moving = false;
    }

    void target(float x, float y, long now) {
        advance(now);
        if (x == targetX && y == targetY) return;
        startX = this.x;
        startY = this.y;
        targetX = x;
        targetY = y;
        startTime = now;
        moving = startX != targetX || startY != targetY;
    }

    boolean advance(long now) {
        if (!moving) return false;
        float fraction = Math.max(0f, Math.min(1f, (now - startTime) / (float) TRANSITION_MS));
        x = startX + (targetX - startX) * fraction;
        y = startY + (targetY - startY) * fraction;
        if (fraction >= 1f) {
            x = targetX;
            y = targetY;
            moving = false;
        }
        return moving;
    }

    float x() { return x; }
    float y() { return y; }
}
