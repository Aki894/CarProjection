package com.projection.car;

/** Video-channel liveness is independent of audio, touch and new H.264 pictures. */
final class VideoHeartbeat {
    static final long INTERVAL_MS = 1000;
    static final int SERVICE = 0x00020002;
    private long lastActivity;
    synchronized void reset(long now) { lastActivity = now; }
    synchronized void written(long now) { lastActivity = now; }
    synchronized boolean due(long now) { return now - lastActivity >= INTERVAL_MS; }

    /** AOA envelope + 12-byte video header, with zero payload and a fresh timestamp. */
    static byte[] packet(long timestampMs) {
        byte[] packet = new byte[20];
        putInt(packet, 0, 2); // VIDEO channel
        putInt(packet, 4, 12); // inner header only
        putInt(packet, 12, (int) timestampMs);
        putInt(packet, 16, SERVICE);
        return packet;
    }
    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
