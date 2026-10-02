package com.projection.car;

import java.io.InputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.Arrays;

public final class ConnectionStabilityCheck {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    /** Emulate a packet transport that discards bytes that don't fit the caller's read buffer. */
    private static final class PacketInput extends InputStream {
        private final byte[][] packets;
        private int index;
        int minimumRead = Integer.MAX_VALUE;
        PacketInput(byte[]... packets) { this.packets = packets; }
        @Override public int read(byte[] output, int offset, int length) {
            if (index == packets.length) return -1;
            minimumRead = Math.min(minimumRead, length);
            byte[] packet = packets[index++];
            int count = Math.min(length, packet.length);
            System.arraycopy(packet, 0, output, offset, count);
            return count;
        }
        @Override public int read() { throw new AssertionError("single byte USB read"); }
    }
    public static void main(String[] args) throws Exception {
        byte[] combined = new byte[60];
        for (int i = 0; i < combined.length; i++) combined[i] = (byte) i;
        PacketInput packets = new PacketInput(Arrays.copyOfRange(combined, 0, 17),
                Arrays.copyOfRange(combined, 17, 51), Arrays.copyOfRange(combined, 51, 60));
        AccessoryInputStream input = new AccessoryInputStream(packets);
        byte[] result = new byte[60];
        for (int offset = 0; offset < 60; offset += 10) {
            byte[] header = new byte[8], payload = new byte[2];
            CarLifeFrameReader.readFully(input, header);
            CarLifeFrameReader.readFully(input, payload);
            System.arraycopy(header, 0, result, offset, 8);
            System.arraycopy(payload, 0, result, offset + 8, 2);
        }
        require(Arrays.equals(result, combined), "coalesced packets / split logical headers lost bytes");
        require(packets.minimumRead >= 16384, "small underlying USB read");
        try { CarLifeFrameReader.readFully(input, new byte[8]); throw new AssertionError("missing EOF"); }
        catch (EOFException expected) {}

        byte[] large = new byte[65543];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 13);
        byte[][] transfers = new byte[(large.length + 16383) / 16384][];
        for (int i = 0; i < transfers.length; i++)
            transfers[i] = Arrays.copyOfRange(large, i * 16384, Math.min(large.length, (i + 1) * 16384));
        packets = new PacketInput(transfers);
        input = new AccessoryInputStream(packets);
        result = new byte[large.length];
        CarLifeFrameReader.readFully(input, result);
        require(Arrays.equals(result, large), "large frame bypass lost bytes");
        require(packets.minimumRead >= 16384, "large frame tail used short physical read");

        VideoQueueBudget q = new VideoQueueBudget();
        for (int i = 0; i < 8; i++) require(q.admit(100, i == 0), "initial bounded chain");
        require(!q.admit(100, false), "frame budget unbounded");
        require(q.shouldRequestKeyFrame(1000), "missing key request");
        require(!q.shouldRequestKeyFrame(1200), "key requests spammed");
        for (int i = 0; i < 8; i++) q.complete(100);
        require(!q.admit(100, false), "resumed on dependent P frame");
        require(q.shouldRequestKeyFrame(1500), "retry missing");
        require(q.admit(100, true), "key frame did not recover");
        require(q.admit(100, false), "chain failed after key recovery");
        q.reset();
        require(q.frames() == 0 && q.bytes() == 0 && q.dropped() == 0, "session budget not reset");
        require(q.admit(VideoQueueBudget.MAX_BYTES, true), "boundary bytes rejected");
        require(!q.admit(1, true), "byte budget unbounded");
        q.complete(VideoQueueBudget.MAX_BYTES);
        require(!q.admit(VideoQueueBudget.MAX_BYTES + 1, true), "oversized frame accepted");
        require(q.admit(256, true), "did not recover from oversized key");
        System.out.println("PASS: 16KB USB packet reads, merged/split frames, large payload tails, EOF, bounded video bytes/frames, P-frame recovery guard, key-frame retry, reset");
    }
}
