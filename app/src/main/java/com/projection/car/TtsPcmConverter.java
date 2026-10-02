package com.projection.car;

import java.util.Arrays;

/** Streaming conversion from 48k stereo PCM16 to a selected TTS format. */
final class TtsPcmConverter {
    static final int[] RATES = {16000, 24000, 32000, 44100, 48000};
    private final int rate;
    private final int channels;
    private final double[] filter;
    private final double[][] history = new double[2][63];
    private final double[] previous = new double[2];
    private final double[] current = new double[2];
    private final byte[] pending = new byte[4];
    private int pendingBytes;
    private int writeIndex;
    private int phase;
    private final Pcm48StereoTo16Mono baseline;

    TtsPcmConverter(int rate, int channels) {
        boolean allowed = false;
        for (int value : RATES) allowed |= rate == value;
        if (!allowed || (channels != 1 && channels != 2)) {
            throw new IllegalArgumentException("unsupported TTS format");
        }
        this.rate = rate;
        this.channels = channels;
        // Preserve the exact already-validated 16k mono implementation.
        baseline = rate == 16000 && channels == 1 ? new Pcm48StereoTo16Mono() : null;
        filter = rate == 48000 ? null : makeFilter(rate);
    }

    byte[] convert(byte[] input, int length) {
        if (length < 0 || length > input.length) throw new IllegalArgumentException("invalid PCM length");
        if (baseline != null) return baseline.convert(input, length);
        int frames = (length + pendingBytes) / 4;
        int outputs = (int) (((long) frames * rate + phase) / 48000);
        byte[] result = new byte[outputs * channels * 2];
        int offset = 0;
        for (int i = 0; i < length; i++) {
            pending[pendingBytes++] = input[i];
            if (pendingBytes != 4) continue;
            pendingBytes = 0;
            double left = (short) ((pending[0] & 0xff) | (pending[1] << 8));
            double right = (short) ((pending[2] & 0xff) | (pending[3] << 8));
            history[0][writeIndex] = channels == 1 ? (left + right) * 0.5 : left;
            history[1][writeIndex] = right;
            writeIndex = (writeIndex + 1) % 63;
            Arrays.fill(current, 0);
            for (int channel = 0; channel < channels; channel++) {
                if (filter == null) {
                    current[channel] = history[channel][(writeIndex + 62) % 63];
                } else {
                    int index = writeIndex;
                    for (double coefficient : filter) {
                        index = (index + 62) % 63;
                        current[channel] += coefficient * history[channel][index];
                    }
                }
            }
            phase += rate;
            if (phase >= 48000) {
                phase -= 48000;
                double fraction = 1 - (double) phase / rate;
                for (int channel = 0; channel < channels; channel++) {
                    double interpolated = previous[channel]
                            + (current[channel] - previous[channel]) * fraction;
                    int value = (int) Math.max(-32768, Math.min(32767, Math.round(interpolated)));
                    result[offset++] = (byte) value;
                    result[offset++] = (byte) (value >> 8);
                }
            }
            System.arraycopy(current, 0, previous, 0, channels);
        }
        return result;
    }

    private static double[] makeFilter(int rate) {
        double[] filter = new double[63];
        double cutoff = rate * 0.45 / 48000;
        double sum = 0;
        for (int i = 0; i < filter.length; i++) {
            int delta = i - 31;
            double sinc = delta == 0 ? 2 * cutoff
                    : Math.sin(2 * Math.PI * cutoff * delta) / (Math.PI * delta);
            filter[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / 62));
            sum += filter[i];
        }
        for (int i = 0; i < filter.length; i++) filter[i] /= sum;
        return filter;
    }
}
