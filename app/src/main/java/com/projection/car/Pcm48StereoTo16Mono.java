package com.projection.car;

import java.util.Arrays;

/** Streaming little-endian PCM16: 48 kHz stereo -> 16 kHz mono. */
final class Pcm48StereoTo16Mono {
    // Low-pass before decimation, rather than simply dropping 2 of every 3 samples.
    // 63-tap Hamming-windowed sinc at 7 kHz, normalized to unity DC gain.
    private static final double[] FILTER = makeFilter();
    private final double[] history = new double[FILTER.length];
    private final byte[] pendingFrame = new byte[4];
    private int pendingBytes;
    private int writeIndex;
    private int phase;

    void reset() {
        Arrays.fill(history, 0);
        pendingBytes = writeIndex = phase = 0;
    }

    byte[] convert(byte[] stereo, int length) {
        if (length < 0 || length > stereo.length) {
            throw new IllegalArgumentException("invalid PCM length");
        }
        byte[] output = new byte[(((length + pendingBytes) / 4 + 2) / 3) * 2];
        int outputBytes = 0;
        for (int i = 0; i < length; i++) {
            pendingFrame[pendingBytes++] = stereo[i];
            if (pendingBytes != 4) continue;
            pendingBytes = 0;
            int left = (short) ((pendingFrame[0] & 0xff) | (pendingFrame[1] << 8));
            int right = (short) ((pendingFrame[2] & 0xff) | (pendingFrame[3] << 8));
            history[writeIndex] = (left + right) * 0.5; // No 16-bit sum overflow.
            writeIndex = (writeIndex + 1) % history.length;
            if (++phase != 3) continue;
            phase = 0;
            double filtered = 0;
            int index = writeIndex;
            for (double coefficient : FILTER) {
                index = (index + history.length - 1) % history.length;
                filtered += coefficient * history[index];
            }
            int value = (int) Math.max(-32768, Math.min(32767, Math.round(filtered)));
            output[outputBytes++] = (byte) value;
            output[outputBytes++] = (byte) (value >> 8);
        }
        return Arrays.copyOf(output, outputBytes);
    }

    private static double[] makeFilter() {
        double[] result = new double[63];
        double cutoff = 7000.0 / 48000.0;
        double sum = 0;
        for (int i = 0; i < result.length; i++) {
            int offset = i - result.length / 2;
            double sinc = offset == 0 ? 2 * cutoff
                    : Math.sin(2 * Math.PI * cutoff * offset) / (Math.PI * offset);
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (result.length - 1));
            result[i] = sinc * window;
            sum += result[i];
        }
        for (int i = 0; i < result.length; i++) result[i] /= sum;
        return result;
    }
}
