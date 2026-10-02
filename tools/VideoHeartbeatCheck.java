package com.projection.car;
import java.nio.ByteBuffer;

public final class VideoHeartbeatCheck {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        VideoHeartbeat h = new VideoHeartbeat(); h.reset(100);
        check(!h.due(1099) && h.due(1100), "static video needs heartbeat after one second");
        h.written(1100); check(!h.due(1101), "heartbeat must not busy-loop");
        for (long now = 1120; now < 20000; now += 20) {
            check(!h.due(now), "continuous video already keeps channel alive"); h.written(now);
        }
        check(h.due(21000), "audio/touch activity must not suppress video heartbeat");
        h.reset(30000); check(!h.due(30999), "reconnect resets cadence");
        for (long time : new long[]{0, 1, 0x7fffffffL, 0x80000000L, 0x100000001L}) {
            byte[] packet = VideoHeartbeat.packet(time);
            ByteBuffer b = ByteBuffer.wrap(packet);
            check(packet.length == 20 && b.getInt() == 2 && b.getInt() == 12,
                    "AOA video envelope must include just the inner header");
            check(b.getInt() == 0 && b.getInt() == (int) time && b.getInt() == 0x20002,
                    "empty heartbeat body, timestamp and service must use network order");
            check(!b.hasRemaining(), "heartbeat must not contain H264 or protobuf payload");
        }
        System.out.println("PASS: static/continuous video liveness, reconnect, AOA envelope, empty heartbeat and timestamp wrap");
    }
}
