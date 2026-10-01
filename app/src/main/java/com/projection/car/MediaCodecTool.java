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
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.projection.car.Utils.log;

public class MediaCodecTool {

    private static final String SCREENCAP_NAME = "CarProjection";

    private Context appContext;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec mediaCodec;
    private Surface inputSurface;

    private boolean firstConfigFrame;
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
        activity.startActivityForResult(
                manager.createScreenCaptureIntent(),
                requestCode
        );
    }

    public void onActivityResult(
            Activity activity,
            int resultCode,
            Intent resultData,
            ProjectionReadyListener readyListener
    ) {
        if (resultData == null) {
            return;
        }

        appContext = activity.getApplicationContext();
        projectionReadyListener = readyListener;

        ProjectionBridge.setListener(projection -> {
            ProjectionBridge.clearListener();
            mediaProjection = projection;
            if (mediaProjection == null) {
                return;
            }

            mediaProjection.registerCallback(
                    new MediaProjectionStopCallback(),
                    null
            );
            createVirtualDisplay();

            if (projectionReadyListener != null) {
                projectionReadyListener.onProjectionReady();
            }
        });

        ProjectionService.start(activity, resultCode, resultData);
    }

    public void stopProjection() {
        ProjectionBridge.clearListener();

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (RuntimeException ignored) {
            }
            mediaProjection = null;
        } else {
            releaseEncoderResources();
        }

        if (appContext != null) {
            ProjectionService.stop(appContext);
        }
    }

    private void createVirtualDisplay() {
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
                        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                        if (outputBuffer == null || bufferInfo.size <= 0) {
                            codec.releaseOutputBuffer(index, false);
                            return;
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
                            firstConfigFrame = true;
                            log(
                                    "H264 codec config received: "
                                            + configBytes.length + " bytes"
                            );
                        } else if ((bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            if (firstConfigFrame
                                    && configBytes != null
                                    && configBytes.length > 0) {
                                firstConfigFrame = false;
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
                                encodeListener.onData(outData);
                            }
                        } else if (encodeListener != null) {
                            encodeListener.onData(outData);
                        }
                    } catch (RuntimeException e) {
                        log("encoder output error: " + e);
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
                    log("video encoder error: " + e.getMessage());
                }

                @Override
                public void onOutputFormatChanged(
                        @NonNull MediaCodec codec,
                        @NonNull MediaFormat format
                ) {
                    log("video output format: " + format);
                }
            });

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
        } catch (Exception e) {
            log("createVirtualDisplay failed: " + e);
            stopProjection();
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
        firstConfigFrame = false;
    }

    private final class MediaProjectionStopCallback
            extends MediaProjection.Callback {

        @Override
        public void onStop() {
            log("MediaProjection stopped");
            mediaProjection = null;
            releaseEncoderResources();

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
        void onData(byte[] data);
    }

    public interface ProjectionReadyListener {
        void onProjectionReady();
    }
}
