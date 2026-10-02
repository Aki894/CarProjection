package com.projection.car;

public final class CursorMotionCheck {
    static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.001f) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        CursorMotion m = new CursorMotion();
        m.snap(10, 20);
        m.target(34, -4, 100);
        if (!m.advance(112)) throw new AssertionError("must interpolate");
        near(m.x(), 22); near(m.y(), 8);
        if (m.advance(124)) throw new AssertionError("must settle in 24 ms");
        near(m.x(), 34); near(m.y(), -4);
        m.target(58, 20, 200);
        m.target(82, 44, 212); // Retarget from current visual position, keep latest total input.
        near(m.x(), 46); near(m.y(), 8);
        m.advance(224); near(m.x(), 64); near(m.y(), 26);
        m.advance(236); near(m.x(), 82); near(m.y(), 44);
        m.target(106, 68, 300);
        m.target(106, 68, 312); // Duplicate packets must not delay settling.
        if (m.advance(324)) throw new AssertionError("duplicate extended interpolation");
        near(m.x(), 106);
        m.target(-100, -100, 400);
        m.snap(15, 25); // Click/drag/hide boundaries cancel visual tail.
        if (m.advance(500)) throw new AssertionError("snap left animation running");
        near(m.x(), 15); near(m.y(), 25);
        m.target(100, 100, 1000);
        m.advance(999); near(m.x(), 15); near(m.y(), 25);
        m.advance(5000); near(m.x(), 100); near(m.y(), 100);
        m.snap(0, 0);
        // Sustained rapid input: no lost delta or overshoot; final target settles exactly.
        for (int i = 1; i <= 1000; i++) {
            m.target(i, -i, i * 5L);
            m.advance(i * 5L + 2);
            if (m.x() < 0 || m.x() > i || m.y() > 0 || m.y() < -i)
                throw new AssertionError("overshoot");
        }
        if (m.advance(5024)) throw new AssertionError("tail did not stop");
        near(m.x(), 1000); near(m.y(), -1000);
        System.out.println("PASS: cursor interpolation, 24ms settle, retargeting, duplicate packets, click/drag snap, stalled frame, rapid input conservation");
    }
}
