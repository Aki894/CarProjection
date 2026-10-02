package com.projection.car;

import java.util.Arrays;

public final class PcmVolumeCheck {
    public static void main(String[] args) {
        byte[] input = pcm(-32768, -20000, -1, 0, 1, 20000, 32767, 5000);
        byte[] original = input.clone();
        require(Arrays.equals(input, new PcmVolume(1).apply(input, input.length, 1, 1)),
                "100% must preserve exact signed PCM");
        byte[] muted = new PcmVolume(0).apply(input, input.length, 1, 0);
        require(Arrays.equals(muted, new byte[input.length]), "0% steady gain mutes all PCM");
        byte[] half = new PcmVolume(0.5).apply(input, input.length, 1, 0.5);
        for (int i = 0; i < input.length; i += 2) {
            require(value(half, i) == (int) Math.round(value(input, i) * 0.5), "50% signed amplitude");
        }
        require(Arrays.equals(input, original), "source capture buffer must not change");
        require(Arrays.equals(input, new PcmVolume(3).apply(input, input.length, 1, 3)),
                "cannot amplify above original");
        require(Arrays.equals(new byte[input.length], new PcmVolume(-1).apply(input, input.length, 1, -1)),
                "negative levels clamp to mute");
        byte[] stereo = pcm(20000, -20000, 20000, -20000, 20000, -20000, 20000, -20000);
        PcmVolume fader = new PcmVolume(1);
        byte[] faded = fader.apply(stereo, stereo.length, 2, 0);
        int previous = 20000;
        for (int i = 0; i < faded.length; i += 4) {
            int left = value(faded, i);
            int right = value(faded, i + 2);
            require(left <= previous && left >= 0, "ramp down must be monotonic");
            require(left == -right, "both stereo channels use the same gain per frame");
            previous = left;
        }
        require(previous == 0, "ramp reaches target at packet end");
        require(Arrays.equals(new byte[stereo.length], fader.apply(stereo, stereo.length, 2, 0)),
                "mute remains stable after ramp");
        byte[] restored = fader.apply(stereo, stereo.length, 2, 1);
        require(value(restored, restored.length - 4) == 20000, "ramp back reaches full output");
        require(new PcmVolume(0.3).apply(input, 4, 1, 0.3).length == 4, "honor valid buffer length");
        System.out.println("PASS: mute, exact 100%, signed attenuation, stereo ramp, level clamps, "
                + "capture buffer preservation, valid length");
    }
    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) samples[i];
            bytes[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        return bytes;
    }
    private static int value(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
