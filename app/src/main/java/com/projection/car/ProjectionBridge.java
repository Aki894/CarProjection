package com.projection.car;

import android.media.projection.MediaProjection;

final class ProjectionBridge {

    interface Listener {
        void onProjectionReady(MediaProjection projection);
    }

    private static Listener listener;

    private ProjectionBridge() {
    }

    static synchronized void setListener(Listener value) {
        listener = value;
    }

    static synchronized void clearListener() {
        listener = null;
    }

    static synchronized void deliver(MediaProjection projection) {
        if (listener != null) {
            listener.onProjectionReady(projection);
        } else if (projection != null) {
            projection.stop();
        }
    }
}
