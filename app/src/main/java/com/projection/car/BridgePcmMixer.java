package com.projection.car;

import java.util.ArrayList;
import java.util.List;

/** Bounded 48k stereo PCM16 queues. One 20ms clock mixes all CarPlay streams. */
final class BridgePcmMixer {
    static final int FRAMES = 960;
    static final int CAPACITY = FRAMES * 6;
    static final class Stream {
        final boolean media;
        final int capacity, prebuffer;
        final short[] samples;
        final byte[] partial = new byte[4];
        int head, size, partialBytes;
        long droppedFrames, receivedFrames, consumedFrames;
        boolean ended, primed;
        long lastAppendNanos;
        Stream(boolean media, int capacity, int prebuffer) {
            this.media = media; this.capacity = capacity; this.prebuffer = prebuffer;
            this.samples = new short[capacity * 2];
        }
        synchronized int bufferedFrames() { return size; }
        synchronized void append(byte[] pcm, int length) {
            if (length < 0 || length > pcm.length) throw new IllegalArgumentException("PCM length");
            if (ended) return;
            if (length > 0) lastAppendNanos = System.nanoTime();
            for (int i = 0; i < length; i++) {
                partial[partialBytes++] = pcm[i];
                if (partialBytes != 4) continue;
                partialBytes = 0;
                if (size == capacity) { head = (head + 1) % capacity; size--; droppedFrames++; }
                int tail = (head + size) % capacity * 2;
                samples[tail] = (short) ((partial[0] & 255) | partial[1] << 8);
                samples[tail + 1] = (short) ((partial[2] & 255) | partial[3] << 8);
                size++; receivedFrames++;
            }
        }
        /** Pipe reader waits for the sample clock instead of overwriting unread music. */
        synchronized boolean appendBlocking(byte[] pcm, int length) throws InterruptedException {
            if (length < 0 || length > pcm.length) throw new IllegalArgumentException("PCM length");
            for (int i = 0; i < length; i++) {
                if (ended) return false;
                partial[partialBytes++] = pcm[i];
                if (partialBytes != 4) continue;
                while (size == capacity && !ended) wait();
                if (ended) { partialBytes = 0; return false; }
                partialBytes = 0;
                int tail = (head + size) % capacity * 2;
                samples[tail] = (short)((partial[0] & 255) | partial[1] << 8);
                samples[tail + 1] = (short)((partial[2] & 255) | partial[3] << 8);
                size++; receivedFrames++; lastAppendNanos = System.nanoTime();
            }
            return true;
        }
        synchronized boolean keepClock() {
            return primed && !ended && System.nanoTime() - lastAppendNanos < 500_000_000L;
        }
        synchronized int drain(int[] output) {
            boolean tail = ended || System.nanoTime() - lastAppendNanos >=
                    (!primed && prebuffer > FRAMES * 2 ? 500_000_000L : 60_000_000L);
            if (!primed && size < prebuffer && !tail) return 0;
            if (size < FRAMES && !tail) return 0;
            primed = true;
            int count = Math.min(size, FRAMES);
            for (int i = 0; i < count; i++) {
                output[i * 2] = samples[head * 2];
                output[i * 2 + 1] = samples[head * 2 + 1];
                head = (head + 1) % capacity;
            }
            size -= count; consumedFrames += count;
            notifyAll();
            if (count < FRAMES && tail) primed = false;
            return count;
        }
        synchronized void end(boolean discard) {
            ended = true;
            if (discard) { size = 0; partialBytes = 0; }
            notifyAll();
        }
        synchronized boolean drained() { return ended && size == 0; }
    }
    private final List<Stream> streams = new ArrayList<>();
    synchronized Stream add(boolean media) {
        // Match DiPlay's proven wireless music buffer: 300ms start + 200ms headroom.
        return add(media, media ? FRAMES * 25 : CAPACITY, media ? FRAMES * 15 : FRAMES);
    }
    synchronized Stream add(boolean media, int capacity, int prebuffer) {
        if (streams.size() >= 6) return null;
        if (capacity < FRAMES || capacity > FRAMES * 50 || prebuffer < FRAMES || prebuffer > capacity)
            throw new IllegalArgumentException("PCM queue plan");
        Stream stream = new Stream(media, capacity, prebuffer);
        streams.add(stream);
        return stream;
    }
    synchronized void clear() {
        for (Stream stream : streams) stream.end(true);
        streams.clear();
    }
    synchronized String flowStats() {
        long received = 0, consumed = 0, dropped = 0; int buffered = 0;
        for (Stream stream : streams) synchronized (stream) {
            received += stream.receivedFrames; consumed += stream.consumedFrames;
            dropped += stream.droppedFrames; buffered += stream.size;
        }
        return "sourceFrames=" + received + " playedSourceFrames=" + consumed
                + " bufferedFrames=" + buffered + " overwrittenFrames=" + dropped;
    }
    synchronized byte[] mix() {
        int[] music = new int[FRAMES * 2];
        int[] voice = new int[FRAMES * 2];
        boolean active = false, guidance = false;
        for (Stream stream : streams) {
            int[] chunk = new int[FRAMES * 2];
            int count = stream.drain(chunk);
            active |= count > 0 || stream.keepClock();
            boolean audible = false;
            int[] target = stream.media ? music : voice;
            for (int i = 0; i < count * 2; i++) {
                target[i] += chunk[i];
                audible |= Math.abs(chunk[i]) > 64;
            }
            guidance |= !stream.media && audible;
        }
        streams.removeIf(Stream::drained);
        if (!active) return null;
        byte[] pcm = new byte[FRAMES * 4];
        for (int i = 0; i < music.length; i++) {
            int value = Math.max(-32768, Math.min(32767,
                    (guidance ? music[i] / 4 : music[i]) + voice[i]));
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }
}
