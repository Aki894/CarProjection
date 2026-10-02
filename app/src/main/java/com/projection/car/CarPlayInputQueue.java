package com.projection.car;

import java.util.ArrayDeque;

/** Small, ordered HID commands. Overflow releases contacts before starting a new chain. */
final class CarPlayInputQueue {
    static final int KNOB = 1, TOUCH = 2, MEDIA = 3, RESET = 4, WHEEL = 5;
    static final int MAX_EVENTS = 64;
    private final ArrayDeque<int[]> events = new ArrayDeque<>();
    private boolean waitingTouchDown = true;
    synchronized void add(int kind, int a, int b, int c) {
        if (events.size() >= MAX_EVENTS) reset();
        if (kind == TOUCH) {
            if (a == 0) waitingTouchDown = false;
            else if (waitingTouchDown) return;
            if (a == 1) waitingTouchDown = true;
        }
        events.addLast(new int[]{kind, a, b, c});
    }
    synchronized void reset() { events.clear(); waitingTouchDown = true; events.addLast(new int[]{RESET, 0, 0, 0}); }
    synchronized int[][] drain() { int[][] result = events.toArray(new int[0][]); events.clear(); return result; }
}
