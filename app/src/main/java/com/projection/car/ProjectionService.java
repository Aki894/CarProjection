package com.projection.car;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class ProjectionService extends Service {

    private static final String CHANNEL_ID = "projection";
    private static final int NOTIFICATION_ID = 10010;
    private static final String ACTION_START = "com.projection.car.action.START_PROJECTION";
    private static final String ACTION_STOP = "com.projection.car.action.STOP_PROJECTION";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection mediaProjection;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context, int resultCode, Intent resultData) {
        Intent intent = new Intent(context, ProjectionService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, resultData);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ProjectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        BrightnessController.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopProjectionAndSelf();
            return START_NOT_STICKY;
        }

        startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        );
        acquireScreenWakeLockIfEnabled();

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != 0 && resultData != null) {
                MediaProjectionManager manager =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                try {
                    mediaProjection = manager.getMediaProjection(resultCode, resultData);
                    ProjectionBridge.deliver(mediaProjection);
                    BrightnessController.setProjectionActive(this, true);
                } catch (RuntimeException e) {
                    Utils.log("failed to create MediaProjection: " + e);
                    stopProjectionAndSelf();
                }
            } else {
                stopProjectionAndSelf();
            }
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "CarProjection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("CarLife screen projection");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, ProjectionService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("CarProjection")
                .setContentText("正在向 CarLife 车机投屏")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "停止投屏", stopPendingIntent)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLockIfEnabled() {
        SharedPreferences preferences = getSharedPreferences("set", MODE_PRIVATE);
        if (!preferences.getBoolean("keep_screen_awake", true)) {
            return;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "CarProjection:ProjectionScreen"
        );
        wakeLock.acquire();
    }

    private void stopProjectionAndSelf() {
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
            }
            mediaProjection = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        BrightnessController.setProjectionActive(this, false);
        ProjectionBridge.clearListener();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
            }
            mediaProjection = null;
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
