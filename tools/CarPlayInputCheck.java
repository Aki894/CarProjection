package com.projection.car;
public final class CarPlayInputCheck {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        CarPlayInputQueue q = new CarPlayInputQueue(); CarPlayPadInput p = new CarPlayPadInput(q);
        p.down(0); p.move(2, 1); p.up(80); check(q.drain().length == 0, "single tap/jitter must not select");
        p.down(180); p.up(240);
        int[][] events = q.drain(); check(events.length == 1 && events[0][0] == 1 && events[0][1] == 1, "double tap selects once");
        p.click(300); check(q.drain().length == 0, "mechanical confirmation after double tap is deduplicated");
        p.reset(); p.down(1000); p.move(110, 1); p.up(1100);
        events = q.drain(); check(events.length == 2 && events[0][2] == 1 && events[1][2] == 1, "horizontal motion emits bounded native focus steps");
        p.down(1200); p.up(1220); p.down(1300); p.move(0, -100); p.up(1400);
        events = q.drain(); check(events.length == 2 && events[0][0] == 5 && events[0][1] == -1, "double-tap slide scrolls instead of clicking");
        p.reset(); p.click(2000); events = q.drain(); check(events.length == 1 && events[0][1] == 1, "mechanical press remains available");
        for (int i=0;i<100;i++) q.add(1,0,1,0);
        events = q.drain(); check(events.length <= 65 && events[0][0] == 4, "overflow releases contacts before fresh events");
        q.add(2,0,10,20); q.reset(); events = q.drain(); check(events.length == 1 && events[0][0] == 4, "disconnect discards old presses and releases touch");
        q.add(2,2,11,22); check(q.drain().length == 0, "do not resume dropped touch chain on MOVE");
        System.out.println("PASS: native focus, double-tap select/scroll, mechanical deduplication, bounded input and contact reset");
    }
}
