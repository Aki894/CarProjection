package com.projection.car;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.projection.car.Utils.log;

public class MediaCodecTool {

    private static final String SCREENCAP_NAME = "CarProjection";

    private Context appContext;
    private volatile MediaProjection mediaProjection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean permissionPending;
    private boolean startingProjection;
    private int sessionEpoch;
    private int permissionEpoch;
    private VirtualDisplay virtualDisplay;
    private MediaCodec mediaCodec;
    private Surface inputSurface;

    private byte[] configBytes;
    private VideoDataEncodeListener encodeListener;
    private ProjectionReadyListener projectionReadyListener;

    private int density;
    private int width;
    private int height;
    private int videoFps;
    private int videoBitrate;

    public MediaProjection getMediaProjection() {
        return mediaProjection;
    }

    public void startProjection(
            Activity activity,
            VideoDataEncodeListener encodeListener,
            int requestCode,
            float width,
            float height,
            int videoFps,
            int videoBitrate
    ) {
        if (permissionPending || startingProjection || mediaProjection != null) {
            log("[VIDEO] duplicate START ignored (permission pending / projecting)");
            return;
        }
        permissionPending = true;
        permissionEpoch = ++sessionEpoch;
        this.appContext = activity.getApplicationContext();
        this.encodeListener = encodeListener;
        this.width = (int) width;
        this.height = (int) height;
        this.videoFps = videoFps;
        this.videoBitrate = videoBitrate;
        this.density = activity.getResources().getDisplayMetrics().densityDpi;

        MediaProjectionManager manager =
                (MediaProjectionManager) activity.getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                );
        try {
            activity.startActivityForResult(manager.createScreenCaptureIntent(), requestCode);
        } catch (RuntimeException e) {
            permissionPending = false;
            log("[VIDEO] permission request failed: " + e);
        }
    }

    public boolean onActivityResult(
            Activity activity,
            int resultCode,
            Intent resultData,
            ProjectionReadyListener readyListener
    ) {
        boolean currentRequest = permissionPending && permissionEpoch == sessionEpoch;
        permissionPending = false;
        if (!currentRequest || resultCode != Activity.RESULT_OK || resultData == null) {
            log("[VIDEO] permission result ignored/denied current=" + currentRequest);
            return false;
        }
        final int epoch = sessionEpoch;

        appContext = activity.getApplicationContext();
        projectionReadyListener = readyListener;
        startingProjection = true;

        ProjectionBridge.setListener(projection -> {
            ProjectionBridge.clearListener();
            if (epoch != sessionEpoch) {
                if (projection != null) projection.stop();
                return;
            }
            startingProjection = false;
            mediaProjection = projection;
            if (mediaProjection == null) {
                return;
            }

            mediaProjection.registerCallback(
                    new MediaProjectionStopCallback(projection),
                    mainHandler
            );
            if (!createVirtualDisplay()) {
                if (projectionReadyListener != null) projectionReadyListener.onProjectionStopped();
                return;
            }

            if (projectionReadyListener != null) {
                projectionReadyListener.onProjectionReady();
            }
        });

        try {
            ProjectionService.start(activity, resultCode, resultData);
            return true;
        } catch (RuntimeException e) {
            startingProjection = false;
            log("[VIDEO] projection service start failed: " + e);
            ProjectionBridge.clearListener();
            return false;
        }
    }

    public void requestKeyFrame() {
        mainHandler.post(() -> {
            if (mediaCodec == null) return;
            try {
                Bundle parameters = new Bundle();
                parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                mediaCodec.setParameters(parameters);
            } catch (RuntimeException e) { log("[VIDEO] key-frame request failed: " + e); }
        });
    }

    public void stopProjection() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stopProjection);
            return;
        }
        sessionEpoch++;
        startingProjection = false;
        ProjectionBridge.clearListener();

        MediaProjection oldProjection = mediaProjection;
        mediaProjection = null;
        releaseEncoderResources();
        if (oldProjection != null) {
            try { oldProjection.stop(); } catch (RuntimeException ignored) {}
        }

        if (appContext != null) {
            ProjectionService.stop(appContext);
        }
    }

    private boolean createVirtualDisplay() {
        log(
                "start video encoder: "
                        + width + "x" + height
                        + " @" + videoFps + "fps "
                        + videoBitrate + "bps"
        );

        try {
            mediaCodec = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
            );

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
            );
            format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps);
            format.setInteger(MediaFormat.KEY_CAPTURE_RATE, videoFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setLong(
                    MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                    100_000L
            );
            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            );

            mediaCodec.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            inputSurface = mediaCodec.createInputSurface();

            mediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(
                        @NonNull MediaCodec codec,
                        int index
                ) {
                    // Surface input mode does not use input buffers.
                }

                @Override
                public void onOutputBufferAvailable(
                        @NonNull MediaCodec codec,
                        int index,
                        @NonNull MediaCodec.BufferInfo bufferInfo
                ) {
                    try {
                        if (codec != mediaCodec) return;
                        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                        if (outputBuffer == null || bufferInfo.size <= 0) {
                            return; // The finally block releases each buffer exactly once.
                        }

                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(
                                bufferInfo.offset + bufferInfo.size
                        );

                        byte[] outData = new byte[bufferInfo.size];
                        outputBuffer.get(outData);

                        if ((bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            configBytes = Arrays.copyOf(
                                    outData,
                                    outData.length
                            );
                            log(
                                    "H264 codec config received: "
                                            + configBytes.length + " bytes"
                            );
                        } else if ((bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            if (configBytes != null
                                    && configBytes.length > 0) {
                                byte[] combined = new byte[
                                        configBytes.length + outData.length
                                ];
                                System.arraycopy(
                                        configBytes,
                                        0,
                                        combined,
                                        0,
                                        configBytes.length
                                );
                                System.arraycopy(
                                        outData,
                                        0,
                                        combined,
                                        configBytes.length,
                                        outData.length
                                );
                                outData = combined;
                            }

                            if (encodeListener != null) {
                                encodeListener.onData(outData, true);
                            }
                        } else if (encodeListener != null) {
                            encodeListener.onData(outData, false);
                        }
                    } catch (RuntimeException e) {
                        log("[VIDEO] encoder output error: " + e);
                    } finally {
                        try {
                            codec.releaseOutputBuffer(index, false);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }

                @Override
                public void onError(
                        @NonNull MediaCodec codec,
                        @NonNull MediaCodec.CodecException e
                ) {
                    if (codec != mediaCodec) return;
                    log("[VIDEO] encoder error: " + e.getMessage());
                    stopProjection();
                    if (projectionReadyListener != null) projectionReadyListener.onProjectionStopped();
                }

                @Override
                public void onOutputFormatChanged(
                        @NonNull MediaCodec codec,
                        @NonNull MediaFormat format
                ) {
                    log("video output format: " + format);
                }
            }, mainHandler);

            mediaCodec.start();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    SCREENCAP_NAME,
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    inputSurface,
                    null,
                    null
            );
            return true;
        } catch (Exception e) {
            log("[VIDEO] createVirtualDisplay failed: " + e);
            stopProjection();
            return false;
        }
    }

    private void releaseEncoderResources() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }

        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                mediaCodec.release();
            } catch (RuntimeException ignored) {
            }
            mediaCodec = null;
        }

        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (RuntimeException ignored) {
            }
            inputSurface = null;
        }

        configBytes = null;
    }

    private final class MediaProjectionStopCallback
            extends MediaProjection.Callback {

        private final MediaProjection expected;
        MediaProjectionStopCallback(MediaProjection expected) { this.expected = expected; }

        @Override
        public void onStop() {
            if (mediaProjection != expected) {
                log("[VIDEO] stale projection stop ignored");
                return;
            }
            log("[VIDEO] MediaProjection stopped (system / notification)");
            mediaProjection = null;
            releaseEncoderResources();
            if (projectionReadyListener != null) {
                projectionReadyListener.onProjectionStopped();
            }

            if (appContext != null) {
                BrightnessController.setProjectionActive(
                        appContext,
                        false
                );
                ProjectionService.stop(appContext);
            }
        }
    }

    public interface VideoDataEncodeListener {
        void onData(byte[] data, boolean keyFrame);
    }

    public interface ProjectionReadyListener {
        void onProjectionReady();
        void onProjectionStopped();
    }
}
