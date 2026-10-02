package com.projection.car;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.os.Handler;
import android.os.Looper;

import static com.projection.car.Utils.log;

/**
 * Optional accessibility service used for:
 * 1) dispatching reverse-control gestures received from the CarLife head unit;
 * 2) observing local phone interactions to restore brightness after auto-dim.
 *
 * Projection itself does not depend on this service.
 */
public class ForgroundService extends AccessibilityService {

    public static volatile ForgroundService mService;

    private RemoteCursorController remoteCursorController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mService = this;
        remoteCursorController = new RemoteCursorController(this);
        log("car control accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_SELECTED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            BrightnessController.onUserActivity(this);
        }
    }

    public void onCarPadDown() {
        if (remoteCursorController != null) {
            remoteCursorController.onPadDown();
        }
    }

    public void onCarPadMove(int dx, int dy) {
        if (remoteCursorController != null) {
            remoteCursorController.moveBy(dx, dy);
        }
    }

    public void onCarPadUp() {
        if (remoteCursorController != null) {
            remoteCursorController.onPadUp();
        }
    }

    public void onCarOk() {
        if (remoteCursorController != null) {
            remoteCursorController.click();
        }
    }

    public void onCarBack() {
        mainHandler.post(() -> {
            if (mService != this) return;
            BrightnessController.suppressAccessibilityActivityFor(500);
            boolean accepted = performGlobalAction(GLOBAL_ACTION_BACK);
            log("[CONTROL] BACK accepted=" + accepted);
        });
    }

    public void hideCarCursor() {
        if (remoteCursorController != null) {
            remoteCursorController.hide();
        }
    }

    @Override
    public void onInterrupt() {
        log("car control accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        mService = null;
        if (remoteCursorController != null) {
            remoteCursorController.destroy();
            remoteCursorController = null;
        }
        BrightnessController.restoreIfNeeded(this);
        log("car control accessibility service destroyed");
        super.onDestroy();
    }
}
