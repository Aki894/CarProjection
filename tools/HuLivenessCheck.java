package com.projection.car;
public final class HuLivenessCheck {
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    public static void main(String[] args) {
        HuLiveness l = new HuLiveness(); l.reset(0);
        check(!l.expired(60000), "do not assume all head units send periodic messages");
        l.received(HuLiveness.PERIODIC_STATUS, 0); l.received(HuLiveness.PERIODIC_STATUS,1000);
        check(!l.expired(20000), "two packets are insufficient to learn cadence");
        l.received(HuLiveness.PERIODIC_STATUS,2000);
        check(!l.expired(9999) && l.expired(10000), "learned HU silence expires after 8 seconds");
        l.received(0x1005b,11000); check(!l.expired(18000), "touch traffic also proves HU is alive");
        l.reset(20000); check(!l.expired(100000), "new USB session does not inherit learned heartbeat");
        l.received(HuLiveness.PERIODIC_STATUS,20000); l.received(HuLiveness.PERIODIC_STATUS,30000);
        l.received(HuLiveness.PERIODIC_STATUS,40000); check(!l.expired(60000), "sporadic messages do not arm timeout");
        System.out.println("PASS: learned HU liveness, no-periodic fallback, traffic refresh, reconnect isolation");
    }
}
