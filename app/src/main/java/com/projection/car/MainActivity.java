package com.projection.car;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.projection.car.databinding.ActivityMainBinding;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static com.projection.car.Utils.REQUEST_CODE;
import static com.projection.car.Utils.log;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.projection.car.action.USB_PERMISSION";
    private static final int REQUEST_AUDIO_PERMISSION = 101;
    private static final int REQUEST_EXPORT_LOG = 102;
    private String pendingLogExport;

    private ActivityMainBinding binding;
    private UsbManager usbManager;
    private UsbAccessory usbAccessory;
    private ParcelFileDescriptor fileDescriptor;
    private MsgProcess msgProcess;
    private SharedPreferences preferences;
    private boolean receiverRegistered;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean logRenderScheduled;

    private final Runnable logRenderRunnable = new Runnable() {
        @Override
        public void run() {
            logRenderScheduled = false;
            renderLog();
        }
    };

    private final AppLogger.Listener logListener = new AppLogger.Listener() {
        @Override
        public void onLogUpdated() {
            uiHandler.post(() -> scheduleLogRender());
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("USB broadcast: " + action);

            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbAccessory accessory = getAccessory(intent);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && accessory != null) {
                    usbAccessory = accessory;
                    openAccessory(accessory);
                } else {
                    log("USB accessory permission denied");
                }
                return;
            }

            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                UsbAccessory accessory = getAccessory(intent);
                if (accessory != null) {
                    usbAccessory = accessory;
                    openOrRequestPermission(accessory);
                }
                return;
            }

            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = getAccessory(intent);
                log("USB accessory detached: " + accessory);
                handleAccessoryDetached();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        AppLogger.setListener(logListener);
        binding.inputOnlySwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> renderLog()
        );
        binding.clearLogButton.setOnClickListener(v -> AppLogger.clear());
        binding.exportLogButton.setOnClickListener(v -> exportLog());
        binding.toggleLogButton.setOnClickListener(v -> {
            boolean show = binding.logCard.getVisibility() != View.VISIBLE;
            binding.logCard.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.toggleLogButton.setText(
                    show ? R.string.hide_log : R.string.show_log
            );
            if (show) {
                renderLog();
            }
        });

        preferences = getSharedPreferences("set", MODE_PRIVATE);
        binding.reverseControlSwitch.setChecked(
                preferences.getBoolean("reverse_control_enabled", true)
        );
        binding.lockCursorDuringDragSwitch.setChecked(
                preferences.getBoolean("lock_cursor_during_drag", false)
        );
        binding.carLifeMediaAudioSwitch.setChecked(
                preferences.getBoolean("carlife_media_audio", true)
        );
        binding.ttsAudioCompatibilitySwitch.setChecked(
                preferences.getBoolean("tts_audio_compatibility", false)
        );
        updateAudioModeControls();
        binding.ttsAudioCompatibilitySwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> {
                    preferences.edit().putBoolean("tts_audio_compatibility", checked).apply();
                    updateAudioModeControls();
                    if (msgProcess != null) {
                        msgProcess.updateTtsAudioCompatibilityEnabled(checked);
                    }
                }
        );
        binding.reverseControlSwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> {
                    preferences.edit()
                            .putBoolean("reverse_control_enabled", checked)
                            .apply();
                    if (!checked && ForgroundService.mService != null) {
                        ForgroundService.mService.hideCarCursor();
                    }
                }
        );

        binding.lockCursorDuringDragSwitch.setOnCheckedChangeListener(
                (buttonView, checked) ->
                        preferences.edit()
                                .putBoolean("lock_cursor_during_drag", checked)
                                .apply()
        );

        binding.carLifeMediaAudioSwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> {
                    preferences.edit()
                            .putBoolean("carlife_media_audio", checked)
                            .apply();
                    if (msgProcess != null) {
                        msgProcess.updateCarLifeMediaAudioEnabled(checked);
                    }
                }
        );

        float pointerSensitivity = preferences.getFloat(
                "pointer_sensitivity",
                1.8f
        );
        float pointerAcceleration = preferences.getFloat(
                "pointer_acceleration",
                0.6f
        );

        binding.pointerSensitivitySlider.setValue(pointerSensitivity);
        binding.pointerAccelerationSlider.setValue(pointerAcceleration);
        updatePointerLabels(pointerSensitivity, pointerAcceleration);

        binding.pointerSensitivitySlider.addOnChangeListener(
                (slider, value, fromUser) -> {
                    preferences.edit()
                            .putFloat("pointer_sensitivity", value)
                            .apply();
                    updatePointerLabels(
                            value,
                            binding.pointerAccelerationSlider.getValue()
                    );
                }
        );
        binding.pointerAccelerationSlider.addOnChangeListener(
                (slider, value, fromUser) -> {
                    preferences.edit()
                            .putFloat("pointer_acceleration", value)
                            .apply();
                    updatePointerLabels(
                            binding.pointerSensitivitySlider.getValue(),
                            value
                    );
                }
        );

        int dimDelaySeconds = BrightnessController.getDimDelaySeconds(this);
        binding.dimDelaySlider.setValue(dimDelaySeconds);
        binding.dimDelayValue.setText(
                getString(R.string.dim_delay_format, dimDelaySeconds)
        );
        binding.dimDelaySlider.addOnChangeListener(
                (slider, value, fromUser) -> {
                    int seconds = Math.round(value);
                    binding.dimDelayValue.setText(
                            getString(R.string.dim_delay_format, seconds)
                    );
                    BrightnessController.setDimDelaySeconds(this, seconds);
                }
        );

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        BrightnessController.init(this);

        int videoFps = preferences.getInt(
                "video_fps",
                preferences.getInt("bit", 30)
        );
        int videoBitrate = preferences.getInt(
                "video_bitrate",
                preferences.getInt("frame", 3_000_000)
        );

        binding.fpsInput.setText(String.valueOf(videoFps));
        binding.bitrateInput.setText(String.valueOf(videoBitrate));
        binding.keepScreenAwakeSwitch.setChecked(
                preferences.getBoolean("keep_screen_awake", true)
        );
        binding.autoDimSwitch.setChecked(
                BrightnessController.isAutoDimEnabled(this)
        );

        binding.versionValue.setText(
                getString(R.string.version_format, getVersionName())
        );

        msgProcess = new MsgProcess(
                this,
                videoFps,
                videoBitrate,
                new MsgProcess.InfoListener() {
                    @Override
                    public void onVISSize(int x, int y) {
                        binding.resolutionValue.setText(x + " × " + y);
                    }

                    @Override
                    public void onVISID(String id) {
                        binding.headUnitIdValue.setText(
                                id == null || id.isEmpty()
                                        ? getString(R.string.head_unit_unknown)
                                        : id
                        );
                    }

                    @Override
                    public void onAudioFeatures(
                            Integer audioTransmissionMode,
                            Integer mediaSampleRate,
                            Integer contentEncryption
                    ) {
                        if (audioTransmissionMode == null
                                && mediaSampleRate == null) {
                            binding.audioHuStatusValue.setText(
                                    R.string.audio_hu_waiting
                            );
                            return;
                        }

                        String modeText;
                        if (audioTransmissionMode == null) {
                            modeText = "AudioPath=?";
                        } else if (audioTransmissionMode == 0) {
                            modeText = "AudioPath=CarLife USB (0)";
                        } else if (audioTransmissionMode == 1) {
                            modeText = "AudioPath=Bluetooth (1)";
                        } else {
                            modeText = "AudioPath="
                                    + audioTransmissionMode;
                        }

                        String sampleText;
                        if (mediaSampleRate == null) {
                            sampleText = "MediaRate=?";
                        } else if (mediaSampleRate == 1) {
                            sampleText = "MediaRate=48 kHz (1)";
                        } else if (mediaSampleRate == 0) {
                            sampleText = "MediaRate=system (0)";
                        } else {
                            sampleText = "MediaRate="
                                    + mediaSampleRate;
                        }

                        String encryptionText;
                        if (contentEncryption == null) {
                            encryptionText = "Encrypt=?";
                        } else if (contentEncryption == 0) {
                            encryptionText = "Encrypt=OFF (0)";
                        } else if (contentEncryption == 1) {
                            encryptionText = "Encrypt=ON (1)";
                        } else {
                            encryptionText = "Encrypt=" + contentEncryption;
                        }

                        binding.audioHuStatusValue.setText(
                                modeText
                                        + " · "
                                        + sampleText
                                        + " · "
                                        + encryptionText
                        );
                    }

                    @Override
                    public void onEncryptionProbe(int state, int keyLength) {
                        if (state == 0) {
                            binding.audioEncryptionProbeStatus.setText(
                                    R.string.audio_encryption_probe_running
                            );
                        } else if (state == 1) {
                            binding.audioEncryptionProbeStatus.setText(
                                    getString(R.string.audio_encryption_probe_supported)
                                            + " · keyLen="
                                            + keyLength
                            );
                        } else if (state == -1) {
                            binding.audioEncryptionProbeStatus.setText(
                                    R.string.audio_encryption_probe_no_response
                            );
                        } else {
                            binding.audioEncryptionProbeStatus.setText(
                                    "RSA：HU 响应解析失败"
                            );
                        }
                    }

                    @Override
                    public void onModuleControl(int moduleId, int statusId) {
                        String moduleName;
                        String statusName;

                        if (moduleId == 3) {
                            moduleName = "Music";
                            statusName = statusId == 0
                                    ? "IDLE"
                                    : statusId == 1
                                            ? "RUNNING"
                                            : String.valueOf(statusId);
                        } else if (moduleId == 6) {
                            moduleName = "MIC";
                            if (statusId == 0) {
                                statusName = "USE_VEHICLE_MIC";
                            } else if (statusId == 1) {
                                statusName = "USE_MOBILE_MIC";
                            } else if (statusId == 2) {
                                statusName = "NOT_SUPPORTED";
                            } else {
                                statusName = String.valueOf(statusId);
                            }
                        } else {
                            moduleName = "Module " + moduleId;
                            statusName = String.valueOf(statusId);
                        }

                        binding.audioModuleStatusValue.setText(
                                "HU → MD: "
                                        + moduleName
                                        + " / "
                                        + statusName
                                        + " ("
                                        + moduleId
                                        + ", "
                                        + statusId
                                        + ")"
                        );
                    }
                }
        );

        binding.saveButton.setOnClickListener(v -> saveVideoSettings());
        binding.audioEncryptionProbeButton.setOnClickListener(v -> {
            if (msgProcess != null && msgProcess.requestEncryptionProbe()) {
                binding.audioEncryptionProbeStatus.setText(
                        R.string.audio_encryption_probe_running
                );
            } else {
                Snackbar.make(
                        binding.getRoot(),
                        R.string.audio_test_not_connected,
                        Snackbar.LENGTH_LONG
                ).show();
            }
        });
        binding.audioTestToneButton.setOnClickListener(v ->
                runAudioTest(
                        msgProcess != null && msgProcess.playAudioTestTone()
                )
        );
        binding.audioTest44kButton.setOnClickListener(v ->
                runAudioTest(
                        msgProcess != null && msgProcess.playAudioTestTone44k()
                )
        );
        binding.audioTestTtsButton.setOnClickListener(v ->
                runAudioTest(
                        msgProcess != null && msgProcess.playTtsTestTone()
                )
        );
        binding.accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        binding.writeSettingsButton.setOnClickListener(v ->
                requestWriteSettingsPermission()
        );
        binding.autoDimSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!checked) {
                BrightnessController.setAutoDimEnabled(this, false);
                return;
            }

            if (!BrightnessController.isAccessibilityEnabled(this)) {
                buttonView.setChecked(false);
                Snackbar.make(
                        binding.getRoot(),
                        R.string.enable_accessibility_first,
                        Snackbar.LENGTH_LONG
                ).show();
                return;
            }

            if (!BrightnessController.canWriteSettings(this)) {
                buttonView.setChecked(false);
                Snackbar.make(
                        binding.getRoot(),
                        R.string.grant_write_settings_first,
                        Snackbar.LENGTH_LONG
                ).show();
                return;
            }

            BrightnessController.setAutoDimEnabled(this, true);
        });

        registerUsbReceiver();
        requestAudioPermissionIfNeeded();
        checkUsbAccessory();
        refreshOptionalFeatureStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            refreshOptionalFeatureStatus();
            BrightnessController.onUserActivity(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_EXPORT_LOG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingLogExport != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IOException("cannot open log destination");
                    output.write(pendingLogExport.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, R.string.log_export_saved, Toast.LENGTH_SHORT).show();
                } catch (IOException | RuntimeException e) {
                    log("[LOG] export failed: " + e);
                    Toast.makeText(this, R.string.log_export_failed, Toast.LENGTH_LONG).show();
                }
            }
            pendingLogExport = null;
            return;
        }

        if (requestCode != REQUEST_CODE) {
            return;
        }

        if (resultCode == RESULT_OK && data != null) {
            binding.statusTitle.setText(R.string.status_projecting);
            binding.statusDetail.setText("屏幕录制已授权，正在建立视频流。");
            msgProcess.mediaPermissionOk(this, resultCode, data);
        } else {
            binding.statusTitle.setText(R.string.status_projection_denied);
            binding.statusDetail.setText(R.string.auto_connect_hint);
            BrightnessController.setProjectionActive(this, false);
        }
    }

    @Override
    protected void onDestroy() {
        AppLogger.clearListener(logListener);
        uiHandler.removeCallbacksAndMessages(null);

        if (receiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (msgProcess != null) {
            msgProcess.release();
            msgProcess = null;
        }
        closeAccessory(); // Closing the descriptor also unblocks a pending USB read.
        super.onDestroy();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_AUDIO_PERMISSION
        );
    }

    private void checkUsbAccessory() {
        UsbAccessory[] accessories = usbManager.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            binding.statusTitle.setText(R.string.status_waiting);
            return;
        }

        usbAccessory = accessories[0];
        openOrRequestPermission(usbAccessory);
    }

    private void openOrRequestPermission(UsbAccessory accessory) {
        if (usbManager.hasPermission(accessory)) {
            openAccessory(accessory);
            return;
        }

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                permissionIntent,
                flags
        );
        usbManager.requestPermission(accessory, pendingIntent);
    }

    private void openAccessory(UsbAccessory accessory) {
        if (accessory == null) {
            return;
        }

        msgProcess.resetUsb();
        closeAccessory();

        fileDescriptor = usbManager.openAccessory(accessory);
        if (fileDescriptor == null) {
            log("openAccessory failed");
            binding.statusTitle.setText(R.string.status_waiting);
            binding.statusDetail.setText("无法打开 CarLife USB 配件。");
            return;
        }

        FileDescriptor fd = fileDescriptor.getFileDescriptor();
        FileInputStream inputStream = new FileInputStream(fd);
        FileOutputStream outputStream = new FileOutputStream(fd);
        msgProcess.startProjection(inputStream, outputStream);

        binding.statusTitle.setText(R.string.status_connected);
        binding.statusDetail.setText(
                "CarLife 协议已连接，等待车机请求视频流。"
        );
        log("USB accessory opened; CarLife transport ready");
    }

    private void handleAccessoryDetached() {
        usbAccessory = null;
        if (msgProcess != null) {
            msgProcess.resetUsb();
        }
        ProjectionService.stop(this);
        BrightnessController.setProjectionActive(this, false);
        closeAccessory();

        binding.statusTitle.setText(R.string.status_waiting);
        binding.statusDetail.setText(R.string.auto_connect_hint);
        binding.resolutionValue.setText(R.string.resolution_unknown);
        binding.headUnitIdValue.setText(R.string.head_unit_unknown);
        binding.audioHuStatusValue.setText(R.string.audio_hu_waiting);
        binding.audioModuleStatusValue.setText(R.string.audio_module_waiting);
        binding.audioEncryptionProbeStatus.setText(
                R.string.audio_encryption_probe_idle
        );
    }

    private void closeAccessory() {
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException ignored) {
            }
            fileDescriptor = null;
        }
    }

    private void saveVideoSettings() {
        try {
            int fps = Integer.parseInt(
                    String.valueOf(binding.fpsInput.getText()).trim()
            );
            int bitrate = Integer.parseInt(
                    String.valueOf(binding.bitrateInput.getText()).trim()
            );

            fps = Math.max(5, Math.min(60, fps));
            bitrate = Math.max(500_000, Math.min(20_000_000, bitrate));

            boolean keepAwake = binding.keepScreenAwakeSwitch.isChecked();

            preferences.edit()
                    .putInt("video_fps", fps)
                    .putInt("video_bitrate", bitrate)
                    .putBoolean("keep_screen_awake", keepAwake)
                    .apply();

            msgProcess.updateVideoConfig(fps, bitrate);
            binding.fpsInput.setText(String.valueOf(fps));
            binding.bitrateInput.setText(String.valueOf(bitrate));
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Snackbar.make(
                    binding.getRoot(),
                    "请输入有效的帧率和视频码率。",
                    Snackbar.LENGTH_LONG
            ).show();
        }
    }

    private void refreshOptionalFeatureStatus() {
        boolean accessibilityEnabled =
                BrightnessController.isAccessibilityEnabled(this);
        boolean writeSettings =
                BrightnessController.canWriteSettings(this);

        binding.accessibilityStatus.setText(
                accessibilityEnabled
                        ? R.string.accessibility_enabled
                        : R.string.accessibility_disabled
        );
        binding.writeSettingsStatus.setText(
                writeSettings
                        ? R.string.write_settings_granted
                        : R.string.write_settings_missing
        );

        boolean autoDimEnabled =
                BrightnessController.isAutoDimEnabled(this)
                        && accessibilityEnabled
                        && writeSettings;

        if (binding.autoDimSwitch.isChecked() != autoDimEnabled) {
            binding.autoDimSwitch.setChecked(autoDimEnabled);
        }
    }

    private void scheduleLogRender() {
        if (binding == null
                || binding.logCard.getVisibility() != View.VISIBLE) {
            return;
        }
        if (logRenderScheduled) {
            return;
        }
        logRenderScheduled = true;
        uiHandler.postDelayed(logRenderRunnable, 100);
    }

    private void renderLog() {
        if (binding == null
                || binding.logCard.getVisibility() != View.VISIBLE) {
            return;
        }

        final int pageScrollY = binding.pageScroll.getScrollY();
        final int logScrollY = binding.logScroll.getScrollY();
        final int oldLogMax = Math.max(
                0,
                binding.logText.getHeight() - binding.logScroll.getHeight()
        );
        final boolean stickLogToBottom =
                oldLogMax == 0 || logScrollY >= oldLogMax - dp(24);

        boolean inputOnly = binding.inputOnlySwitch.isChecked();
        java.util.List<String> lines = AppLogger.snapshot(inputOnly);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }

        binding.logText.setText(builder.toString());
        binding.logText.post(new Runnable() {
            @Override
            public void run() {
                if (binding == null) {
                    return;
                }

                int newLogMax = Math.max(
                        0,
                        binding.logText.getHeight() - binding.logScroll.getHeight()
                );
                binding.logScroll.scrollTo(
                        0,
                        stickLogToBottom
                                ? newLogMax
                                : Math.min(logScrollY, newLogMax)
                );

                binding.pageScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        if (binding != null) {
                            binding.pageScroll.scrollTo(0, pageScrollY);
                        }
                    }
                });
            }
        });
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void updatePointerLabels(
            float sensitivity,
            float acceleration
    ) {
        binding.pointerSensitivityValue.setText(
                getString(R.string.pointer_value_format, sensitivity)
        );
        binding.pointerAccelerationValue.setText(
                getString(R.string.pointer_value_format, acceleration)
        );
    }

    private void exportLog() {
        StringBuilder report = new StringBuilder();
        report.append("CarProjection ").append(getVersionName()).append(" diagnostics\n")
                .append("Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT)
                .append(" / ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                .append("TTS compatibility=").append(binding.ttsAudioCompatibilitySwitch.isChecked())
                .append(" USB media=").append(binding.carLifeMediaAudioSwitch.isChecked()).append('\n')
                .append("RECORD_AUDIO granted=").append(ContextCompat.checkSelfPermission(this,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED).append('\n')
                .append(binding.audioHuStatusValue.getText()).append('\n')
                .append("Log includes all categories, regardless of the input-only filter.\n\n");
        for (String line : AppLogger.exportSnapshot()) report.append(line).append('\n');
        pendingLogExport = report.toString();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "CarProjection-" + getVersionName() + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_LOG);
        } catch (RuntimeException e) {
            pendingLogExport = null;
            Toast.makeText(this, R.string.log_export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void updateAudioModeControls() {
        boolean tts = binding.ttsAudioCompatibilitySwitch.isChecked();
        binding.carLifeMediaAudioSwitch.setEnabled(!tts);
        binding.audioTestToneButton.setEnabled(!tts);
        binding.audioTest44kButton.setEnabled(!tts);
    }

    private void runAudioTest(boolean started) {
        if (started) {
            Toast.makeText(
                    this,
                    R.string.audio_test_started,
                    Toast.LENGTH_SHORT
            ).show();
        } else {
            Snackbar.make(
                    binding.getRoot(),
                    R.string.audio_test_not_connected,
                    Snackbar.LENGTH_LONG
            ).show();
        }
    }

    private void requestWriteSettingsPermission() {
        if (BrightnessController.canWriteSettings(this)) {
            return;
        }

        Intent intent = new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private String getVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.3.3";
        }
    }

    @SuppressWarnings("deprecation")
    private UsbAccessory getAccessory(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY,
                    UsbAccessory.class
            );
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }
}
