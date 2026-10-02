package com.projection.car;

/** Learn a periodic HU message before applying a silent-session timeout. */
final class HuLiveness {
    static final int PERIODIC_STATUS = 0x00018010;
    static final long TIMEOUT_MS = 8000;
    private long lastPeriodic = -1, lastRead = -1;
    private int periodicCount;
    synchronized void reset(long now) { lastPeriodic = -1; lastRead = now; periodicCount = 0; }
    synchronized void received(int type, long now) {
        lastRead = now;
        if (type == PERIODIC_STATUS) {
            periodicCount = lastPeriodic >= 0 && now - lastPeriodic <= 5000 ? periodicCount + 1 : 1;
            lastPeriodic = now;
        }
    }
    synchronized boolean expired(long now) { return periodicCount >= 3 && now - lastRead >= TIMEOUT_MS; }
    synchronized long idle(long now) { return Math.max(0, now - lastRead); }
}
