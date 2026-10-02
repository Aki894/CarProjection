package com.projection.car;

public final class TouchPadTapCheck {
    public static void main(String[] args) {
        TouchPadTapTracker t = new TouchPadTapTracker();
        require(!t.down(0), "first contact at zero timestamp");
        require(!t.move(0, 0), "zero MOVE is not a drag");
        expect(t.up(80), TouchPadTapTracker.Release.FIRST_TAP);
        require(t.down(200), "second contact armed");
        expect(t.up(260), TouchPadTapTracker.Release.DOUBLE_TAP);
        require(!t.down(330), "third tap is a new sequence");
        expect(t.up(390), TouchPadTapTracker.Release.FIRST_TAP);

        t.reset();
        firstTap(t, 1000);
        require(t.down(1200), "jitter candidate");
        require(!t.move(1, 1), "small movement tolerance");
        require(!t.move(-1, -1), "small return movement tolerance");
        expect(t.up(1280), TouchPadTapTracker.Release.DOUBLE_TAP);

        t.reset();
        firstTap(t, 2000);
        require(t.down(2200), "drag candidate");
        require(t.move(0, 6), "threshold starts drag");
        expect(t.up(2500), TouchPadTapTracker.Release.DRAG_END);
        require(!t.down(2600), "drag must not arm another double tap");
        require(!t.move(4, 4), "subthreshold travel so far");
        require(t.move(-4, -4), "back-and-forth travel is still movement");
        expect(t.up(2650), TouchPadTapTracker.Release.NONE);

        t.reset();
        firstTap(t, 3000);
        require(!t.down(3531), "450 ms window expired");
        expect(t.up(3580), TouchPadTapTracker.Release.FIRST_TAP);
        require(t.down(3600), "long hold second contact armed");
        expect(t.up(4101), TouchPadTapTracker.Release.NONE);

        t.reset();
        firstTap(t, 5000);
        require(t.down(5200), "mechanical click on second contact");
        require(t.mechanicalClick(5220), "mechanical click accepted");
        expect(t.up(5260), TouchPadTapTracker.Release.NONE);
        require(!t.down(5400), "OK must consume previous tap");
        t.reset();
        firstTap(t, 6000);
        require(t.down(6200), "HU OK after second up");
        expect(t.up(6260), TouchPadTapTracker.Release.DOUBLE_TAP);
        t.doubleClickDispatched(6260);
        require(!t.mechanicalClick(6300), "HU OK deduplicated after successful double tap");
        require(t.mechanicalClick(6411), "later mechanical press accepted");

        t.reset();
        expect(t.up(7000), TouchPadTapTracker.Release.NONE);
        require(!t.move(9, 9), "stray movement cannot start contact");
        require(!t.down(7100), "reset clears tap history");
        t.move(30, 0);
        expect(t.up(7150), TouchPadTapTracker.Release.NONE);
        require(!t.down(7200), "ordinary cursor movement must not arm tap");
        expect(t.up(7701), TouchPadTapTracker.Release.NONE);
        System.out.println("PASS: double-tap click vs drag, jitter/zero MOVE, cumulative travel, "
                + "timing, long holds, triple taps, mechanical OK deduplication, reset and stray events");
    }
    private static void firstTap(TouchPadTapTracker t, long now) {
        require(!t.down(now), "fresh first tap");
        expect(t.up(now + 80), TouchPadTapTracker.Release.FIRST_TAP);
    }
    private static void expect(TouchPadTapTracker.Release actual, TouchPadTapTracker.Release expected) {
        require(actual == expected, "expected " + expected + ", got " + actual);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
