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
    public static void main(String[] args) throws Exception {
        BridgePcmMixer mixer = new BridgePcmMixer();
        BridgePcmMixer.Stream media = mixer.add(true, 5760, 1920);
        BridgePcmMixer.Stream voice = mixer.add(false);
        byte[] music = tone(12000, 1920);
        for (int i = 0; i < music.length; i += 3) media.append(Arrays.copyOfRange(music, i, Math.min(i + 3, music.length)), Math.min(3, music.length - i));
        voice.append(tone(4000, 960), 3840);
        check(sample(mixer.mix(), 0) == 7000, "voice + ducked music");
        check(sample(mixer.mix(), 0) == 12000, "voice ends independently");
        check(mixer.mix() != null, "keep continuous PCM clock during a short source gap");
        media.append(tone(-16000, 960), 3840);
        voice.append(tone(0, 960), 3840);
        check(sample(mixer.mix(), 0) == -16000, "silent voice must not duck music");
        voice.append(tone(30000, 960), 3840);
        media.append(tone(30000, 960), 3840);
        check(sample(mixer.mix(), 0) == 32767, "saturate instead of wrap");
        media.append(tone(8000, 960 * 10), 38400);
        check(media.size == 5760 && media.droppedFrames == 3840, "120ms bound");
        mixer.clear(); check(mixer.mix() == null, "disconnect clears old PCM");
        for (int i = 0; i < 6; i++) check(mixer.add(true, 5760, 1920) != null, "six streams");
        check(mixer.add(true, 5760, 1920) == null, "stream count bound");
        mixer.clear();
        BridgePcmMixer.Stream tail = mixer.add(false);
        tail.append(tone(1000, 7), 28); tail.end(false);
        byte[] padded = mixer.mix();
        check(sample(padded, 6) == 1000 && sample(padded, 7) == 0, "short tail padded");
        check(mixer.mix() == null && mixer.add(true, 5760, 1920) != null, "EOF removes drained stream");
        mixer.clear();
        BridgePcmMixer.Stream aac = mixer.add(true, 5760, 1920);
        aac.append(tone(1000, 1024), 4096);
        check(mixer.mix() == null, "music prebuffer");
        aac.append(tone(1000, 1024), 4096);
        check(sample(mixer.mix(), 959) == 1000, "first AAC block");
        check(sample(mixer.mix(), 959) == 1000, "second AAC block");
        check(aac.size == 128 && mixer.mix() != null && aac.size == 128,
                "1024-frame AAC tail must wait, not expand to a new 960-frame block");
        aac.end(false);
        check(sample(mixer.mix(), 127) == 1000, "flush ended AAC tail");
        mixer.clear();
        BridgePcmMixer.Stream paced = mixer.add(false);
        final int frames = 960 * 20;
        byte[] sequence = new byte[frames * 4];
        for (int f=0;f<frames;f++) for(int ch=0;ch<2;ch++) {
            int i=f*4+ch*2; sequence[i]=(byte)(f+1); sequence[i+1]=(byte)((f+1)>>8);
        }
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { paced.appendBlocking(sequence, sequence.length); paced.end(false); }
            catch(Throwable e) { failure.set(e); }
        });
        producer.start();
        long deadline=System.nanoTime()+2_000_000_000L;
        while(paced.bufferedFrames() < BridgePcmMixer.CAPACITY && System.nanoTime()<deadline) Thread.yield();
        check(producer.isAlive(), "ahead-of-clock producer waits instead of dropping samples");
        int seen=0; int[] chunk=new int[1920];
        while(seen<frames && System.nanoTime()<deadline) {
            int count=paced.drain(chunk);
            for(int i=0;i<count;i++) check(chunk[i*2]==++seen, "PCM burst remains sample-contiguous");
            if(count==0) Thread.yield();
        }
        producer.join(1000);
        check(!producer.isAlive() && failure.get()==null && seen==frames && paced.droppedFrames==0,
                "bounded pipe backpressure preserves all source samples");
        BridgePcmMixer.Stream cancelled=mixer.add(false);
        Thread blocked=new Thread(() -> {
            try { cancelled.appendBlocking(sequence, sequence.length); }
            catch(InterruptedException e) { failure.set(e); }
        });
        blocked.start();
        deadline=System.nanoTime()+1_000_000_000L;
        while(cancelled.bufferedFrames()<BridgePcmMixer.CAPACITY && System.nanoTime()<deadline) Thread.yield();
        cancelled.end(true); blocked.join(1000);
        check(!blocked.isAlive(), "disconnect wakes a blocked producer");
        mixer.clear();
        BridgePcmMixer.Stream wireless = mixer.add(true);
        wireless.append(tone(2000, 960 * 10), 38400);
        check(mixer.mix() == null, "wireless music waits for its 300ms jitter buffer");
        wireless.append(tone(2000, 960 * 5), 19200);
        check(sample(mixer.mix(), 0) == 2000 && wireless.capacity == 24000,
                "wireless music starts at 300ms with 500ms capacity");
        System.out.println("PASS: bridge split PCM, voice ducking, silence, clipping, 500ms music / 120ms voice queues, stream bound, EOF tail and disconnect isolation");
    }
}
