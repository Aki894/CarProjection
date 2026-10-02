package com.projection.car;

import android.media.projection.MediaProjection;

public final class ProjectionBridgeCheck {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int[] delivered = {0};
        long old = ProjectionBridge.setListener(p -> { throw new AssertionError("old listener invoked"); });
        long current = ProjectionBridge.setListener(p -> { delivered[0]++; ProjectionBridge.clearListener(); return true; });
        MediaProjection stale = new MediaProjection();
        require(!ProjectionBridge.deliver(old, stale) && stale.stopped, "stale service delivery accepted");
        ProjectionBridge.clearListener(old);
        MediaProjection live = new MediaProjection();
        require(ProjectionBridge.deliver(current, live) && !live.stopped && delivered[0] == 1,
                "old service destroy cleared new listener");
        MediaProjection duplicate = new MediaProjection();
        require(!ProjectionBridge.deliver(current, duplicate) && duplicate.stopped, "duplicate grant delivered");
        current = ProjectionBridge.setListener(p -> true);
        ProjectionBridge.clearListener();
        MediaProjection late = new MediaProjection();
        require(!ProjectionBridge.deliver(current, late) && late.stopped, "grant delivered after disconnect");
        System.out.println("PASS: projection service tokens, stale/duplicate grants, old destroy isolation, disconnect-before-delivery");
    }
}
