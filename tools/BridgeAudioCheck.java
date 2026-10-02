package com.projection.car;

import java.util.Arrays;

public final class BridgeAudioCheck {
    private static byte[] tone(int value, int frames) {
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < pcm.length; i += 2) { pcm[i] = (byte) value; pcm[i + 1] = (byte) (value >> 8); }
        return pcm;
    }
    private static int sample(byte[] pcm, int frame) { int i = frame * 4; return (short) ((pcm[i] & 255) | pcm[i + 1] << 8); }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        BridgePcmMixer mixer = new BridgePcmMixer();
        BridgePcmMixer.Stream media = mixer.add(true);
        BridgePcmMixer.Stream voice = mixer.add(false);
        byte[] music = tone(12000, 960);
        for (int i = 0; i < music.length; i += 3) media.append(Arrays.copyOfRange(music, i, Math.min(i + 3, music.length)), Math.min(3, music.length - i));
        voice.append(tone(4000, 960), 3840);
        check(sample(mixer.mix(), 0) == 7000, "voice + ducked music");
        check(mixer.mix() == null, "empty does not hold HU focus");
        media.append(tone(-16000, 960), 3840);
        voice.append(tone(0, 960), 3840);
        check(sample(mixer.mix(), 0) == -16000, "silent voice must not duck music");
        voice.append(tone(30000, 960), 3840);
        media.append(tone(30000, 960), 3840);
        check(sample(mixer.mix(), 0) == 32767, "saturate instead of wrap");
        media.append(tone(8000, 960 * 10), 38400);
        check(media.size == 5760 && media.droppedFrames == 3840, "120ms bound");
        mixer.clear(); check(mixer.mix() == null, "disconnect clears old PCM");
        for (int i = 0; i < 6; i++) check(mixer.add(true) != null, "six streams");
        check(mixer.add(true) == null, "stream count bound");
        mixer.clear();
        BridgePcmMixer.Stream tail = mixer.add(false);
        tail.append(tone(1000, 7), 28); tail.end(false);
        byte[] padded = mixer.mix();
        check(sample(padded, 6) == 1000 && sample(padded, 7) == 0, "short tail padded");
        check(mixer.mix() == null && mixer.add(true) != null, "EOF removes drained stream");
        System.out.println("PASS: bridge split PCM, voice ducking, silence, clipping, 120ms queue, stream bound, EOF tail and disconnect isolation");
    }
}
