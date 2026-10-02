package com.projection.car;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

public final class TtsFormatsCheck {
    public static void main(String[] args) {
        byte[] pcm = new byte[48000 * 4];
        for (int i = 0; i < 48000; i++) {
            int sample = (int) Math.round(12000 * Math.sin(2 * Math.PI * 1000 * i / 48000));
            pcm[i * 4] = (byte) sample;
            pcm[i * 4 + 1] = (byte) (sample >> 8);
            // Right channel deliberately silent; verify stereo independence.
        }
        for (int rate : TtsPcmConverter.RATES) {
            for (int channels = 1; channels <= 2; channels++) {
                byte[] full = new TtsPcmConverter(rate, channels).convert(pcm, pcm.length);
                require(full.length == rate * channels * 2, "one-second output count " + rate);
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                TtsPcmConverter streaming = new TtsPcmConverter(rate, channels);
                Random random = new Random(36);
                for (int offset = 0; offset < pcm.length;) {
                    int count = Math.min(1 + random.nextInt(3840), pcm.length - offset);
                    byte[] block = Arrays.copyOfRange(pcm, offset, offset + count);
                    joined.writeBytes(streaming.convert(block, count));
                    offset += count;
                }
                require(Arrays.equals(full, joined.toByteArray()), "chunk continuity " + rate + "/" + channels);
                double squares = 0;
                int count = 0;
                for (int offset = channels * 2 * 200; offset < full.length; offset += channels * 2) {
                    int left = sample(full, offset);
                    squares += (double) left * left;
                    count++;
                    if (channels == 2) require(sample(full, offset + 2) == 0, "right-channel crosstalk");
                }
                double expected = 12000 / Math.sqrt(2) / (channels == 1 ? 2 : 1);
                require(Math.abs(Math.sqrt(squares / count) / expected - 1) < 0.05, "1 kHz gain " + rate);
                if (rate == 48000 && channels == 2) require(Arrays.equals(pcm, full), "native stereo exact PCM");
                if (rate == 16000 && channels == 1) {
                    require(Arrays.equals(full, new Pcm48StereoTo16Mono().convert(pcm, pcm.length)),
                            "known-good baseline unchanged");
                }
            }
        }
        // 44.1k is a rational 147/160 conversion, not integer sample dropping.
        TtsPcmConverter rational = new TtsPcmConverter(44100, 2);
        long bytes = 0;
        byte[] block = Arrays.copyOf(pcm, 3840);
        for (int i = 0; i < 3000; i++) bytes += rational.convert(block, block.length).length;
        require(bytes == 60L * 44100 * 4, "44.1k minute sample count");
        System.out.println("PASS: all ten TTS formats, stereo independence, 1 kHz gain, "
                + "arbitrary byte boundaries, native 48k identity, exact 16k baseline, 44.1k clock count");
    }
    private static int sample(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
