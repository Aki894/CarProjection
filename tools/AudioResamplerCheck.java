package com.projection.car;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/** Dependency-free checks runnable on JDK 17, without an Android SDK. */
public final class AudioResamplerCheck {
    public static void main(String[] args) {
        byte[] pcm = tone(48000, 1000, 12000, false);
        byte[] reference = new Pcm48StereoTo16Mono().convert(pcm, pcm.length);
        require(reference.length == 32000, "one second must yield 16000 mono samples");
        double passRms = rms(reference, 200);
        require(Math.abs(passRms - 12000 / Math.sqrt(2)) < 100, "1 kHz gain");

        Pcm48StereoTo16Mono streaming = new Pcm48StereoTo16Mono();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        Random random = new Random(31);
        for (int offset = 0; offset < pcm.length;) {
            int count = Math.min(1 + random.nextInt(2560), pcm.length - offset);
            byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + count);
            joined.writeBytes(streaming.convert(chunk, count));
            offset += count;
        }
        require(Arrays.equals(reference, joined.toByteArray()),
                "arbitrary byte/chunk boundaries must match continuous conversion");
        streaming.reset();
        require(Arrays.equals(reference, streaming.convert(pcm, pcm.length)), "reset clears history");

        byte[] rightOnly = tone(48000, 1000, 12000, true);
        double rightRms = rms(new Pcm48StereoTo16Mono().convert(rightOnly, rightOnly.length), 200);
        require(Math.abs(rightRms / passRms - 0.5) < 0.01, "right channel must be included");

        byte[] highTone = tone(48000, 12000, 12000, false);
        double stopRms = rms(new Pcm48StereoTo16Mono().convert(highTone, highTone.length), 200);
        require(stopRms / passRms < 0.01, "12 kHz must not alias into 16 kHz audio");
        for (int value : new int[]{32767, -32768}) {
            byte[] dc = constant(4800, value, value);
            byte[] mono = new Pcm48StereoTo16Mono().convert(dc, dc.length);
            require(Math.abs(sample(mono, mono.length - 2) - value) <= 1,
                    "full-scale signed PCM must not overflow");
        }
        byte[] cancel = constant(4800, 20000, -20000);
        require(rms(new Pcm48StereoTo16Mono().convert(cancel, cancel.length), 0) == 0,
                "opposite-phase stereo downmix cancels");
        require(rms(new Pcm48StereoTo16Mono().convert(new byte[3840], 3840), 0) == 0,
                "silence stays silent");

        // Ten minutes at the old 2560-byte read size exercises the 3:1 phase across packets.
        Pcm48StereoTo16Mono longStream = new Pcm48StereoTo16Mono();
        byte[] block = tone(640, 1000, 12000, false);
        long outputBytes = 0;
        for (int i = 0; i < 45000; i++) {
            outputBytes += longStream.convert(block, block.length).length;
        }
        require(outputBytes == 600L * 16000 * 2, "ten-minute stream must have no sample-count drift");
        System.out.printf("PASS: chunk boundaries, reset, stereo mix, signed PCM, silence, "
                + "1 kHz gain, 12 kHz alias rejection (%.1f dB), ten-minute sample count%n",
                20 * Math.log10(Math.max(stopRms, 0.001) / passRms));
    }

    private static byte[] tone(int frames, int frequency, int amplitude, boolean rightOnly) {
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            int value = (int) Math.round(amplitude * Math.sin(2 * Math.PI * frequency * i / 48000));
            put(pcm, i * 4, rightOnly ? 0 : value);
            put(pcm, i * 4 + 2, value);
        }
        return pcm;
    }

    private static byte[] constant(int frames, int left, int right) {
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            put(pcm, i * 4, left);
            put(pcm, i * 4 + 2, right);
        }
        return pcm;
    }

    private static void put(byte[] pcm, int offset, int value) {
        pcm[offset] = (byte) value;
        pcm[offset + 1] = (byte) (value >> 8);
    }

    private static int sample(byte[] pcm, int offset) {
        return (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
    }

    private static double rms(byte[] pcm, int skipSamples) {
        double squares = 0;
        int count = 0;
        for (int i = skipSamples * 2; i + 1 < pcm.length; i += 2) {
            double value = sample(pcm, i);
            squares += value * value;
            count++;
        }
        return Math.sqrt(squares / count);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
