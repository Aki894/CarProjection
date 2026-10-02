package com.projection.car;

/** Native CarPlay focus navigation; the phone cursor is not part of direct H.264. */
final class CarPlayPadInput {
    private final TouchPadTapTracker taps = new TouchPadTapTracker();
    private final CarPlayInputQueue queue;
    private boolean scrolling;
    private double x, y;
    CarPlayPadInput(CarPlayInputQueue queue) { this.queue = queue; }
    void reset() { taps.reset(); x = y = 0; scrolling = false; }
    void down(long now) { scrolling = taps.down(now); x = y = 0; }
    void move(int dx, int dy) {
        if (!taps.move(dx, dy)) return;
        x += dx; y += dy;
        double axis = Math.abs(x) >= Math.abs(y) ? x : y;
        int steps = Math.min(4, (int) (Math.abs(axis) / 48));
        if (steps == 0) return;
        int sign = axis > 0 ? 1 : -1;
        boolean horizontal = Math.abs(x) >= Math.abs(y);
        if (horizontal) x -= sign * steps * 48; else y -= sign * steps * 48;
        for (int i = 0; i < steps; i++) {
            if (scrolling) queue.add(CarPlayInputQueue.WHEEL, sign, 0, 0);
            else queue.add(CarPlayInputQueue.KNOB, 0, horizontal ? sign : 0, horizontal ? 0 : sign);
        }
    }
    void up(long now) {
        if (taps.up(now) == TouchPadTapTracker.Release.DOUBLE_TAP) {
            queue.add(CarPlayInputQueue.KNOB, 1, 0, 0); taps.doubleClickDispatched(now);
        }
        scrolling = false; x = y = 0;
    }
    void click(long now) { if (taps.mechanicalClick(now)) queue.add(CarPlayInputQueue.KNOB, 1, 0, 0); }
}
