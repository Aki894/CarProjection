package com.projection.car;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import static com.projection.car.Utils.log;

/**
 * Optional accessibility service used for:
 * 1) dispatching reverse-control gestures received from the CarLife head unit;
 * 2) observing local phone touch interaction to restore brightness after auto-dim.
 *
 * Projection itself does not depend on this service.
 */
public class ForgroundService extends AccessibilityService {

    public static volatile ForgroundService mService;

    private RemoteCursorController remoteCursorController;

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

        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            BrightnessController.onUserActivity(this);
        }
    }

    public void onCarPadMove(int dx, int dy) {
        if (remoteCursorController != null) {
            remoteCursorController.moveBy(dx, dy);
        }
    }

    public void onCarOk() {
        if (remoteCursorController != null) {
            remoteCursorController.click();
        }
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
