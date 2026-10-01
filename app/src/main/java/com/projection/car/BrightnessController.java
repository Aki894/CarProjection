package com.projection.car;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

public final class BrightnessController {

    private static final String PREFS = "set";
    private static final String KEY_AUTO_DIM = "auto_dim";
    private static final long DIM_DELAY_MS = 30_000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static boolean projectionActive;
    private static boolean originalCaptured;
    private static boolean dimmed;
    private static int originalMode;
    private static int originalBrightness;

    private static final Runnable DIM_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            Context context = AppContextHolder.get();
            if (context != null) {
                dimNow(context);
            }
        }
    };

    private BrightnessController() {
    }

    public static void init(Context context) {
        AppContextHolder.set(context.getApplicationContext());
    }

    public static boolean isAutoDimEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_DIM, false);
    }

    public static void setAutoDimEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_DIM, enabled)
                .apply();

        if (!enabled) {
            restoreIfNeeded(context);
            HANDLER.removeCallbacks(DIM_RUNNABLE);
        } else if (projectionActive) {
            ensureOriginalCaptured(context);
            scheduleDim(context);
        }
    }

    public static boolean canWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    public static boolean isAccessibilityEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null || enabledServices.isEmpty()) {
            return false;
        }

        String target = new ComponentName(context, ForgroundService.class).flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (target.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    public static void setProjectionActive(Context context, boolean active) {
        init(context);
        projectionActive = active;
        HANDLER.removeCallbacks(DIM_RUNNABLE);

        if (!active) {
            restoreIfNeeded(context);
            originalCaptured = false;
            return;
        }

        if (isAutoDimEnabled(context)
                && canWriteSettings(context)
                && isAccessibilityEnabled(context)) {
            ensureOriginalCaptured(context);
            scheduleDim(context);
        }
    }

    public static void onUserActivity(Context context) {
        init(context);
        if (!projectionActive || !isAutoDimEnabled(context)) {
            return;
        }
        if (!canWriteSettings(context)) {
            return;
        }

        if (dimmed) {
            restoreBrightness(context);
        }
        scheduleDim(context);
    }

    public static void restoreIfNeeded(Context context) {
        HANDLER.removeCallbacks(DIM_RUNNABLE);
        if (originalCaptured && dimmed && canWriteSettings(context)) {
            restoreBrightness(context);
        }
    }

    private static void ensureOriginalCaptured(Context context) {
        if (originalCaptured || !canWriteSettings(context)) {
            return;
        }
        try {
            originalMode = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE
            );
            originalBrightness = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS
            );
            originalCaptured = true;
        } catch (Settings.SettingNotFoundException ignored) {
            originalCaptured = false;
        }
    }

    private static void scheduleDim(Context context) {
        if (!projectionActive
                || !isAutoDimEnabled(context)
                || !canWriteSettings(context)
                || !isAccessibilityEnabled(context)) {
            return;
        }
        ensureOriginalCaptured(context);
        HANDLER.removeCallbacks(DIM_RUNNABLE);
        HANDLER.postDelayed(DIM_RUNNABLE, DIM_DELAY_MS);
    }

    private static void dimNow(Context context) {
        if (!projectionActive
                || !isAutoDimEnabled(context)
                || !canWriteSettings(context)
                || !isAccessibilityEnabled(context)
                || !originalCaptured) {
            return;
        }

        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        );
        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                1
        );
        dimmed = true;
    }

    private static void restoreBrightness(Context context) {
        if (!originalCaptured) {
            return;
        }

        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                originalMode
        );
        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                originalBrightness
        );
        dimmed = false;
    }

    private static final class AppContextHolder {
        private static Context context;

        static void set(Context value) {
            context = value;
        }

        static Context get() {
            return context;
        }
    }
}
