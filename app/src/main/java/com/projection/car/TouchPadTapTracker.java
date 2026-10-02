package com.projection.car;

/** Main-thread tap recognition, independent of Android gesture injection. */
final class TouchPadTapTracker {
    enum Release { NONE, FIRST_TAP, DOUBLE_TAP, DRAG_END }
    static final long DOUBLE_TAP_WINDOW_MS = 450;
    static final long TAP_MAX_DURATION_MS = 500;
    static final double MOVE_SLOP = 6; // Raw Remote Touch delta units, before sensitivity gain.
    private static final long OK_DEDUP_MS = 150;

    private long lastTapUp = -1;
    private long downTime = -1;
    private long lastDoubleClick = -1;
    private double travel;
    private boolean secondTap;
    private boolean moved;
    private boolean mechanicalClick;

    boolean down(long now) {
        secondTap = lastTapUp >= 0 && now >= lastTapUp
                && now - lastTapUp <= DOUBLE_TAP_WINDOW_MS;
        lastTapUp = -1;
        downTime = now;
        travel = 0;
        moved = mechanicalClick = false;
        return secondTap;
    }

    boolean move(int dx, int dy) {
        if (downTime >= 0) {
            travel += Math.hypot((double) dx, (double) dy);
            moved |= travel >= MOVE_SLOP;
        }
        return moved;
    }

    Release up(long now) {
        if (downTime < 0) return Release.NONE;
        Release result;
        if (secondTap && moved && !mechanicalClick) {
            result = Release.DRAG_END;
        } else if (!moved && !mechanicalClick && now >= downTime
                && now - downTime <= TAP_MAX_DURATION_MS) {
            result = secondTap ? Release.DOUBLE_TAP : Release.FIRST_TAP;
        } else {
            result = Release.NONE;
        }
        lastTapUp = result == Release.FIRST_TAP ? now : -1;
        downTime = -1;
        secondTap = moved = mechanicalClick = false;
        travel = 0;
        return result;
    }

    boolean mechanicalClick(long now) {
        mechanicalClick = true;
        lastTapUp = -1;
        secondTap = false;
        return lastDoubleClick < 0 || now < lastDoubleClick || now - lastDoubleClick > OK_DEDUP_MS;
    }

    void doubleClickDispatched(long now) { lastDoubleClick = now; }

    void reset() {
        lastTapUp = downTime = lastDoubleClick = -1;
        travel = 0;
        secondTap = moved = mechanicalClick = false;
    }
}
