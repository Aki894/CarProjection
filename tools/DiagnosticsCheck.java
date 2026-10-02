package com.projection.car;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

public final class DiagnosticsCheck {
    public static void main(String[] args) throws Exception {
        byte[] expected = new byte[513];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        InputStream fragments = new ByteArrayInputStream(expected) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        };
        byte[] actual = new byte[513];
        CarLifeFrameReader.readFully(fragments, actual);
        require(Arrays.equals(expected, actual), "fragmented frame bytes");
        try {
            CarLifeFrameReader.readFully(new ByteArrayInputStream(new byte[7]), new byte[8]);
            throw new AssertionError("truncated frame accepted");
        } catch (EOFException correct) {}
        InputStream zeroRead = new ByteArrayInputStream(expected) {
            boolean first = true;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (first) { first = false; return 0; }
                return super.read(bytes, offset, length);
            }
        };
        CarLifeFrameReader.readFully(zeroRead, actual);
        require(Arrays.equals(expected, actual), "zero-byte read recovery");
        for (int invalid : new int[]{-1, 0, 7, 1024 * 1024 + 1, Integer.MAX_VALUE}) {
            try {
                CarLifeFrameReader.checkPayloadLength(invalid);
                throw new AssertionError("invalid payload length accepted");
            } catch (IOException correct) {}
        }
        CarLifeFrameReader.checkPayloadLength(8);
        CarLifeFrameReader.checkPayloadLength(65543);

        AppLogger.clear();
        AppLogger.append("[TTS-AUDIO] important INIT");
        AppLogger.append("[USB] CLOSE reason=READ EOF");
        AppLogger.append("[LIFECYCLE] DESTROY");
        for (int i = 0; i < 6000; i++) AppLogger.append("[PAD] MOVE " + i);
        List<String> exported = AppLogger.exportSnapshot();
        require(exported.size() == 5003, "bounded general history plus older diagnostic");
        require(exported.get(0).contains("important INIT"), "diagnostic retained after input flood");
        require(exported.stream().anyMatch(x -> x.contains("reason=READ EOF")), "USB error lost after input flood");
        require(exported.stream().anyMatch(x -> x.contains("[LIFECYCLE] DESTROY")), "lifecycle lost after input flood");
        require(AppLogger.snapshot(false).size() == 300, "bounded display");
        require(AppLogger.snapshot(true).size() == 300, "input filter display bound");
        AppLogger.clear();
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            final int worker = i;
            workers[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) AppLogger.append("[AUDIO] worker=" + worker + " i=" + j);
            });
            workers[i].start();
        }
        for (Thread worker : workers) worker.join();
        for (String line : AppLogger.exportSnapshot()) {
            require(line.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}  \\[AUDIO].*"),
                    "concurrent timestamp corruption");
        }
        require(AppLogger.exportSnapshot().size() <= 6000, "export history bound");
        AppLogger.clear();
        require(AppLogger.exportSnapshot().isEmpty(), "clear both histories");
        System.out.println("PASS: split frames, EOF, zero reads, invalid lengths, log retention, "
                + "concurrent timestamps, display/export limits, clear");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
