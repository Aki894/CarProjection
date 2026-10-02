package com.projection.car;

import java.util.Arrays;

/** PCM16 attenuation, with a one-packet ramp when the target changes. */
final class PcmVolume {
    private double gain;

    PcmVolume(double initialGain) { gain = clamp(initialGain); }

    byte[] apply(byte[] input, int length, int channels, double targetGain) {
        if (length < 0 || length > input.length || channels < 1 || channels > 2
                || length % (channels * 2) != 0) {
            throw new IllegalArgumentException("invalid PCM16 frame buffer");
        }
        double target = clamp(targetGain);
        byte[] output = Arrays.copyOf(input, length);
        int frames = length / (channels * 2);
        double start = gain;
        for (int frame = 0; frame < frames; frame++) {
            double level = start + (target - start) * (frame + 1.0) / frames;
            for (int channel = 0; channel < channels; channel++) {
                int offset = (frame * channels + channel) * 2;
                int value = (short) ((input[offset] & 0xff) | (input[offset + 1] << 8));
                int scaled = (int) Math.round(value * level);
                output[offset] = (byte) scaled;
                output[offset + 1] = (byte) (scaled >> 8);
            }
        }
        if (frames > 0) gain = target;
        return output;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite gain");
        return Math.max(0, Math.min(1, value));
    }
}
