package com.projection.car;

import android.media.projection.MediaProjection;

final class ProjectionBridge {
    interface Listener { boolean onProjectionReady(MediaProjection projection); }
    private static Listener listener;
    private static long token;
    private ProjectionBridge() {}

    static synchronized long setListener(Listener value) {
        listener = value;
        return ++token;
    }
    static synchronized void clearListener() {
        listener = null;
        token++;
    }
    static synchronized void clearListener(long expectedToken) {
        if (token == expectedToken) clearListener();
    }
    static synchronized boolean deliver(long expectedToken, MediaProjection projection) {
        if (listener != null && token == expectedToken) return listener.onProjectionReady(projection);
        if (projection != null) projection.stop();
        return false;
    }
}
