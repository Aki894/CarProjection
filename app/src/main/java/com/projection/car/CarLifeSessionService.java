package com.projection.car;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/** USB-session foreground lifetime without claiming MediaProjection permission. */
public final class CarLifeSessionService extends Service {
    private static volatile CarLifeSessionService instance;
    private static volatile MsgProcess session;
    private static android.os.ParcelFileDescriptor descriptor;
    private static android.hardware.usb.UsbAccessory accessory;
    static synchronized MsgProcess acquire(android.app.Activity activity, int fps, int bitrate, MsgProcess.InfoListener listener) {
        if (session == null || session.isReleased()) session = new MsgProcess(activity, fps, bitrate, listener);
        else session.attachUi(activity, listener);
        return session;
    }
    static synchronized boolean isOpen(android.hardware.usb.UsbAccessory next) {
        return descriptor != null && next != null && next.equals(accessory) && session != null && session.isUsbConnected();
    }
    static synchronized void takeDescriptor(android.os.ParcelFileDescriptor next, android.hardware.usb.UsbAccessory device) {
        descriptor = next; accessory = device;
    }
    static synchronized void closeDescriptor() {
        android.os.ParcelFileDescriptor old = descriptor; descriptor = null; accessory = null;
        if (old != null) try { old.close(); } catch (java.io.IOException ignored) {}
    }
    private final android.content.BroadcastReceiver detachReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction()) && session != null) {
                android.hardware.usb.UsbAccessory detached = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY);
                if (detached != null && detached.equals(accessory)) session.resetUsb("USB_ACCESSORY_DETACHED service");
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        ContextCompat.registerReceiver(this, detachReceiver, new android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    private PowerManager.WakeLock wake;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable renew = new Runnable() {
        @Override public void run() {
            if (wake == null) return;
            wake.acquire(30 * 60 * 1000L); handler.postDelayed(this, 10 * 60 * 1000L);
        }
    };
    static void start(Context context) { ContextCompat.startForegroundService(context, new Intent(context, CarLifeSessionService.class)); }
    static void stop(Context context) {
        CarLifeSessionService service = instance;
        if (service != null) service.handler.post(() -> {
            if (session == null || !session.isUsbConnected()) service.stopSelf();
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("carlife_session", "CarLife 连接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(10011, new NotificationCompat.Builder(this, "carlife_session")
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("CarLife 桥接已连接")
                .setContentText("音视频与车机输入直接桥接").setContentIntent(open).setOngoing(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarProjection:CarLifeSession");
            wake.setReferenceCounted(false); renew.run();
        }
        if (session == null || !session.isUsbConnected()) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        instance = null;
        unregisterReceiver(detachReceiver);
        MsgProcess old = session;
        if (old != null && old.isUsbConnected()) old.resetUsb("SESSION_SERVICE_DESTROYED");
        if (old != null && !old.hasUi()) { session = null; old.release(); }
        closeDescriptor();
        handler.removeCallbacksAndMessages(null);
        if (wake != null && wake.isHeld()) wake.release(); wake = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
