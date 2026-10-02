package com.projection.car;

/** Bound encoded video backlog without resuming on dependent P frames after a drop. */
final class VideoQueueBudget {
    static final int MAX_FRAMES = 8;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private int frames, bytes;
    private boolean waitingForKeyFrame;
    private long lastKeyRequest = -1;
    private long dropped;

    synchronized boolean admit(int length, boolean keyFrame) {
        if (length <= 0 || length > MAX_BYTES || frames >= MAX_FRAMES
                || bytes > MAX_BYTES - length || (waitingForKeyFrame && !keyFrame)) {
            waitingForKeyFrame = true;
            dropped++;
            return false;
        }
        if (keyFrame) waitingForKeyFrame = false;
        frames++;
        bytes += length;
        return true;
    }

    synchronized boolean shouldRequestKeyFrame(long now) {
        if (!waitingForKeyFrame || (lastKeyRequest >= 0 && now - lastKeyRequest < 500)) return false;
        lastKeyRequest = now;
        return true;
    }

    synchronized void complete(int length) {
        frames = Math.max(0, frames - 1);
        bytes = Math.max(0, bytes - length);
    }

    synchronized void reset() {
        frames = bytes = 0;
        dropped = 0;
        waitingForKeyFrame = false;
        lastKeyRequest = -1;
    }
    synchronized int frames() { return frames; }
    synchronized int bytes() { return bytes; }
    synchronized long dropped() { return dropped; }
}
