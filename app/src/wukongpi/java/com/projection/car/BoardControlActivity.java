package com.projection.car;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

/** ADB-only control for the board test build. Not included in ordinary phone builds. */
public final class BoardControlActivity extends Activity {
    private static final String TAG = "CarProjectionBoard";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String command = getIntent().getStringExtra("command");
            if (command == null) command = "status";
            SharedPreferences settings = getSharedPreferences("set", MODE_PRIVATE);
            if ("start".equals(command) || "profile".equals(command)) {
                // Explicit opt-in. Preserve all existing car-specific audio and video tuning.
                boolean saved = settings.edit()
                        .putBoolean("direct_carplay_video", true)
                        .putBoolean("direct_carplay_audio", true)
                        .putBoolean("direct_carplay_input", true)
                        .putInt("wukongpi_profile_version", 1).commit();
                if (!saved) {
                    Log.e(TAG, "Profile save failed; session was not started");
                    return;
                }
                Log.i(TAG, "Board direct video/audio/input profile saved");
            } else if (!"status".equals(command)) {
                Log.e(TAG, "Unknown command; use start, profile or status");
                return;
            }
            Log.i(TAG, "directVideo=" + settings.getBoolean("direct_carplay_video", false)
                    + " directAudio=" + settings.getBoolean("direct_carplay_audio", false)
                    + " directInput=" + settings.getBoolean("direct_carplay_input", false)
                    + " " + CarLifeSessionService.boardState());
            UsbManager usb = getSystemService(UsbManager.class);
            UsbAccessory[] devices = usb == null ? null : usb.getAccessoryList();
            Log.i(TAG, "accessoryCount=" + (devices == null ? 0 : devices.length));
            if (devices != null) for (UsbAccessory device : devices) {
                Log.i(TAG, "accessory manufacturer=" + device.getManufacturer()
                        + " model=" + device.getModel() + " permission=" + usb.hasPermission(device));
            }
            if ("start".equals(command)) {
                startForegroundService(new Intent(this, BoardSessionService.class).putExtra("command", "start"));
                Log.i(TAG, "Board service requested without Activity; provisioning supplies USB permission");
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Board command failed", error);
        } finally {
            finish();
        }
    }
}
