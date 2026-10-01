package com.projection.car;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

final class RemoteCursorController {

    private static final String PREFS = "set";
    private static final String KEY_ENABLED = "reverse_control_enabled";
    private static final String KEY_SENSITIVITY = "pointer_sensitivity";
    private static final String KEY_ACCELERATION = "pointer_acceleration";
    private static final String KEY_LOCK_CURSOR_DURING_DRAG = "lock_cursor_during_drag";

    private static final float DEFAULT_SENSITIVITY = 1.8f;
    private static final float DEFAULT_ACCELERATION = 0.6f;

    private static final long DOUBLE_TAP_WINDOW_MS = 450L;
    private static final long TAP_MAX_DURATION_MS = 500L;

    // Short gesture segments keep the drag responsive while avoiding
    // dispatching a new gesture before Android finishes the previous one.
    private static final long DRAG_SEGMENT_MIN_MS = 16L;
    private static final long DRAG_SEGMENT_MAX_MS = 42L;

    private final ForgroundService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;

    private CursorView cursorView;
    private WindowManager.LayoutParams params;

    private float cursorX;
    private float cursorY;
    private int screenWidth;
    private int screenHeight;
    private int cursorSize;

    private long padDownTime;
    private long lastTapUpTime;
    private boolean padMoved;
    private boolean sawOkDuringContact;
    private boolean dragArmed;

    private boolean liveDragActive;
    private boolean dragFingerDown;
    private boolean dragDispatchInFlight;
    private float dragOriginX;
    private float dragOriginY;
    private float dragInjectedX;
    private float dragInjectedY;
    private float dragPendingX;
    private float dragPendingY;
    private GestureDescription.StrokeDescription continuedStroke;
    private int dragGeneration;

    RemoteCursorController(ForgroundService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(
                Context.WINDOW_SERVICE
        );
    }

    void onPadDown() {
        if (!isEnabled()) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ensureCursor();

                long now = SystemClock.uptimeMillis();
                padDownTime = now;
                padMoved = false;
                sawOkDuringContact = false;

                dragArmed = lastTapUpTime > 0
                        && now - lastTapUpTime <= DOUBLE_TAP_WINDOW_MS;

                if (dragArmed) {
                    dragOriginX = cursorX;
                    dragOriginY = cursorY;
                    dragInjectedX = cursorX;
                    dragInjectedY = cursorY;
                    dragPendingX = cursorX;
                    dragPendingY = cursorY;
                    dragFingerDown = true;
                    dragDispatchInFlight = false;
                    liveDragActive = false;
                    continuedStroke = null;
                    dragGeneration++;

                    Utils.log(
                            "[CONTROL] LIVE_DRAG armed x="
                                    + Math.round(cursorX)
                                    + " y="
                                    + Math.round(cursorY)
                    );
                }
            }
        });
    }

    void moveBy(final int dx, final int dy) {
        if (!isEnabled()) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ensureCursor();
                refreshBounds();

                float gain = calculateGain(dx, dy);
                boolean lockCursor = dragArmed && isCursorLockedDuringDrag();

                if (lockCursor) {
                    dragPendingX = clamp(
                            dragPendingX + dx * gain,
                            cursorSize / 2f,
                            screenWidth - cursorSize / 2f
                    );
                    dragPendingY = clamp(
                            dragPendingY + dy * gain,
                            cursorSize / 2f,
                            screenHeight - cursorSize / 2f
                    );
                } else {
                    cursorX = clamp(
                            cursorX + dx * gain,
                            cursorSize / 2f,
                            screenWidth - cursorSize / 2f
                    );
                    cursorY = clamp(
                            cursorY + dy * gain,
                            cursorSize / 2f,
                            screenHeight - cursorSize / 2f
                    );
                    updateCursorPosition();

                    if (dragArmed) {
                        dragPendingX = cursorX;
                        dragPendingY = cursorY;
                    }
                }

                padMoved = true;

                if (dragArmed) {
                    if (!liveDragActive) {
                        liveDragActive = true;
                        dragInjectedX = dragOriginX;
                        dragInjectedY = dragOriginY;
                        continuedStroke = null;
                        Utils.log(
                                "[CONTROL] LIVE_DRAG start lockCursor="
                                        + lockCursor
                        );
                    }

                    dispatchNextDragSegment();
                }
            }
        });
    }

    void onPadUp() {
        if (!isEnabled()) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                long duration = padDownTime > 0 ? now - padDownTime : 0;

                if (dragArmed && liveDragActive && padMoved) {
                    dragFingerDown = false;
                    dragPendingX = cursorX;
                    dragPendingY = cursorY;
                    lastTapUpTime = 0;

                    // If a segment is still running, its callback will send
                    // the final continuation and release the synthetic finger.
                    if (!dragDispatchInFlight) {
                        dispatchNextDragSegment();
                    }
                } else if (!padMoved
                        && !sawOkDuringContact
                        && duration <= TAP_MAX_DURATION_MS) {
                    lastTapUpTime = now;
                    Utils.log("[CONTROL] TAP registered for live drag");
                } else {
                    lastTapUpTime = 0;
                    cancelLiveDragState(false);
                }

                dragArmed = false;
                padDownTime = 0;
                padMoved = false;
                sawOkDuringContact = false;
            }
        });
    }

    void click() {
        if (!isEnabled()) {
            return;
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ensureCursor();

                if (liveDragActive) {
                    Utils.log("[CONTROL] CLICK ignored while live drag is active");
                    return;
                }

                sawOkDuringContact = true;
                lastTapUpTime = 0;

                Path path = new Path();
                path.moveTo(cursorX, cursorY);

                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(
                                path,
                                0,
                                60
                        );

                BrightnessController.suppressAccessibilityActivityFor(500);
                boolean accepted = service.dispatchGesture(
                        new GestureDescription.Builder()
                                .addStroke(stroke)
                                .build(),
                        null,
                        null
                );

                if (cursorView != null) {
                    cursorView.flash();
                }

                Utils.log(
                        "[CONTROL] CLICK x="
                                + Math.round(cursorX)
                                + " y="
                                + Math.round(cursorY)
                                + " accepted="
                                + accepted
                );
            }
        });
    }

    void hide() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                removeCursor();
            }
        });
    }

    void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        removeCursor();
    }

    private void dispatchNextDragSegment() {
        if (!liveDragActive || dragDispatchInFlight) {
            return;
        }

        final boolean finalSegment = !dragFingerDown;
        final float startX = dragInjectedX;
        final float startY = dragInjectedY;
        final float targetX = dragPendingX;
        final float targetY = dragPendingY;

        float distance = (float) Math.hypot(
                targetX - startX,
                targetY - startY
        );

        if (!finalSegment && distance < 0.5f) {
            return;
        }

        Path segmentPath = new Path();
        segmentPath.moveTo(startX, startY);
        if (distance >= 0.5f) {
            segmentPath.lineTo(targetX, targetY);
        }

        long duration;
        if (finalSegment && distance < 0.5f) {
            duration = 1L;
        } else {
            duration = Math.max(
                    DRAG_SEGMENT_MIN_MS,
                    Math.min(
                            DRAG_SEGMENT_MAX_MS,
                            Math.round(14f + distance / 10f)
                    )
            );
        }

        final GestureDescription.StrokeDescription stroke;
        try {
            if (continuedStroke == null) {
                stroke = new GestureDescription.StrokeDescription(
                        segmentPath,
                        0,
                        duration,
                        !finalSegment
                );
            } else {
                stroke = continuedStroke.continueStroke(
                        segmentPath,
                        0,
                        duration,
                        !finalSegment
                );
            }
        } catch (RuntimeException e) {
            Utils.log("[CONTROL] LIVE_DRAG stroke error: " + e);
            cancelLiveDragState(false);
            return;
        }

        final int generation = dragGeneration;
        dragDispatchInFlight = true;

        BrightnessController.suppressAccessibilityActivityFor(300);
        boolean accepted = service.dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (generation != dragGeneration) {
                            return;
                        }

                        dragInjectedX = targetX;
                        dragInjectedY = targetY;
                        continuedStroke = stroke;
                        dragDispatchInFlight = false;

                        if (!stroke.willContinue()) {
                            Utils.log("[CONTROL] LIVE_DRAG end");
                            cancelLiveDragState(true);
                            return;
                        }

                        // Coalesce every Lexus MOVE received while this segment
                        // was being dispatched, then immediately chase the
                        // newest target. If the finger has been lifted, this
                        // call emits the final continuation with willContinue=false.
                        dispatchNextDragSegment();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (generation != dragGeneration) {
                            return;
                        }

                        Utils.log("[CONTROL] LIVE_DRAG cancelled");
                        dragDispatchInFlight = false;
                        cancelLiveDragState(false);
                    }
                },
                mainHandler
        );

        if (!accepted) {
            dragDispatchInFlight = false;
            Utils.log("[CONTROL] LIVE_DRAG dispatch rejected");
            cancelLiveDragState(false);
        }
    }

    private void cancelLiveDragState(boolean completed) {
        liveDragActive = false;
        dragFingerDown = false;
        dragDispatchInFlight = false;
        continuedStroke = null;
        dragOriginX = 0;
        dragOriginY = 0;
        dragInjectedX = 0;
        dragInjectedY = 0;
        dragPendingX = 0;
        dragPendingY = 0;

        if (!completed) {
            // Invalidate callbacks from any older gesture chain.
            dragGeneration++;
        }
    }

    private float calculateGain(int dx, int dy) {
        SharedPreferences prefs = service.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        float sensitivity = prefs.getFloat(
                KEY_SENSITIVITY,
                DEFAULT_SENSITIVITY
        );
        float acceleration = prefs.getFloat(
                KEY_ACCELERATION,
                DEFAULT_ACCELERATION
        );

        float magnitude = (float) Math.hypot(dx, dy);
        float normalized = Math.min(1.5f, magnitude / 70f);

        return sensitivity * (1f + acceleration * normalized);
    }

    private boolean isEnabled() {
        return service.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        ).getBoolean(KEY_ENABLED, true);
    }

    private boolean isCursorLockedDuringDrag() {
        return service.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        ).getBoolean(KEY_LOCK_CURSOR_DURING_DRAG, false);
    }

    @SuppressWarnings("deprecation")
    private void refreshBounds() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = Math.max(1, metrics.widthPixels);
        screenHeight = Math.max(1, metrics.heightPixels);

        if (cursorX <= 0 || cursorY <= 0) {
            cursorX = screenWidth / 2f;
            cursorY = screenHeight / 2f;
        } else {
            cursorX = clamp(
                    cursorX,
                    cursorSize / 2f,
                    screenWidth - cursorSize / 2f
            );
            cursorY = clamp(
                    cursorY,
                    cursorSize / 2f,
                    screenHeight - cursorSize / 2f
            );
        }
    }

    private void ensureCursor() {
        if (cursorView != null) {
            return;
        }

        cursorSize = dp(30);
        refreshBounds();

        cursorView = new CursorView(service);

        params = new WindowManager.LayoutParams(
                cursorSize,
                cursorSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        updateCursorPosition();
        try {
            windowManager.addView(cursorView, params);
            Utils.log(
                    "[CONTROL] cursor shown x="
                            + Math.round(cursorX)
                            + " y="
                            + Math.round(cursorY)
            );
        } catch (RuntimeException e) {
            Utils.log("[CONTROL] cursor overlay failed: " + e);
            cursorView = null;
            params = null;
        }
    }

    private void updateCursorPosition() {
        if (params == null) {
            return;
        }

        params.x = Math.round(cursorX - cursorSize / 2f);
        params.y = Math.round(cursorY - cursorSize / 2f);

        if (cursorView != null && cursorView.isAttachedToWindow()) {
            try {
                windowManager.updateViewLayout(cursorView, params);
            } catch (RuntimeException e) {
                Utils.log("[CONTROL] cursor move failed: " + e);
            }
        }
    }

    private void removeCursor() {
        if (cursorView != null) {
            try {
                windowManager.removeView(cursorView);
            } catch (RuntimeException ignored) {
            }
        }
        cursorView = null;
        params = null;
        cursorX = 0;
        cursorY = 0;

        padDownTime = 0;
        lastTapUpTime = 0;
        padMoved = false;
        sawOkDuringContact = false;
        dragArmed = false;
        cancelLiveDragState(false);
    }

    private int dp(int value) {
        return Math.round(
                value * service.getResources().getDisplayMetrics().density
        );
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static final class CursorView extends View {

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());

        private float scale = 1f;

        CursorView(Context context) {
            super(context);
            fillPaint.setColor(Color.WHITE);
            fillPaint.setStyle(Paint.Style.FILL);

            strokePaint.setColor(Color.argb(220, 20, 20, 20));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(
                    2f * context.getResources().getDisplayMetrics().density
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.28f * scale;

            canvas.drawCircle(cx, cy, radius, fillPaint);
            canvas.drawCircle(cx, cy, radius, strokePaint);
        }

        void flash() {
            scale = 1.35f;
            invalidate();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scale = 1f;
                    invalidate();
                }
            }, 100);
        }
    }
}
