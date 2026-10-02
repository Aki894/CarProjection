package com.projection.car;

import java.util.ArrayList;
import java.util.List;

/** Bounded 48k stereo PCM16 queues. One 20ms clock mixes all CarPlay streams. */
final class BridgePcmMixer {
    static final int FRAMES = 960;
    static final int CAPACITY = FRAMES * 6;
    static final class Stream {
        final boolean media;
        final short[] samples = new short[CAPACITY * 2];
        final byte[] partial = new byte[4];
        int head, size, partialBytes;
        long droppedFrames;
        boolean ended;
        Stream(boolean media) { this.media = media; }
        synchronized void append(byte[] pcm, int length) {
            if (length < 0 || length > pcm.length) throw new IllegalArgumentException("PCM length");
            if (ended) return;
            for (int i = 0; i < length; i++) {
                partial[partialBytes++] = pcm[i];
                if (partialBytes != 4) continue;
                partialBytes = 0;
                if (size == CAPACITY) { head = (head + 1) % CAPACITY; size--; droppedFrames++; }
                int tail = (head + size) % CAPACITY * 2;
                samples[tail] = (short) ((partial[0] & 255) | partial[1] << 8);
                samples[tail + 1] = (short) ((partial[2] & 255) | partial[3] << 8);
                size++;
            }
        }
        synchronized int drain(int[] output) {
            int count = Math.min(size, FRAMES);
            for (int i = 0; i < count; i++) {
                output[i * 2] = samples[head * 2];
                output[i * 2 + 1] = samples[head * 2 + 1];
                head = (head + 1) % CAPACITY;
            }
            size -= count;
            return count;
        }
        synchronized void end(boolean discard) {
            ended = true;
            if (discard) { size = 0; partialBytes = 0; }
        }
        synchronized boolean drained() { return ended && size == 0; }
    }
    private final List<Stream> streams = new ArrayList<>();
    synchronized Stream add(boolean media) {
        if (streams.size() >= 6) return null;
        Stream stream = new Stream(media);
        streams.add(stream);
        return stream;
    }
    synchronized void clear() {
        for (Stream stream : streams) stream.end(true);
        streams.clear();
    }
    synchronized byte[] mix() {
        int[] music = new int[FRAMES * 2];
        int[] voice = new int[FRAMES * 2];
        boolean active = false, guidance = false;
        for (Stream stream : streams) {
            int[] chunk = new int[FRAMES * 2];
            int count = stream.drain(chunk);
            active |= count > 0;
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
