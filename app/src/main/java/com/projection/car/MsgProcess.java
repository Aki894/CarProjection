package com.projection.car;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Path;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.baidu.carlife.protobuf.CarlifeCarHardKeyCodeProto;
import com.baidu.carlife.protobuf.CarlifeMusicInitProto;
import com.baidu.carlife.protobuf.CarlifeTouchActionProto;
import com.example.car.CarlifeAuthenResultProto;
import com.example.car.CarlifeDeviceInfoProto;
import com.example.car.CarlifeProtocolVersionMatchStatusProto;
import com.example.car.CarlifeStatisticsInfoProto;
import com.example.car.CarlifeVideoEncoderInfoProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.yftech.CarLifeTouchPadActionProto;
import com.baidu.carlife.protobuf.CarlifeFeatureConfigProto;
import com.baidu.carlife.protobuf.CarlifeFeatureConfigListProto;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static android.content.Context.MODE_PRIVATE;
import static com.projection.car.Utils.ACTION_DOWN;
import static com.projection.car.Utils.ACTION_MOVE;
import static com.projection.car.Utils.ACTION_UP;
import static com.projection.car.Utils.CMD;
import static com.projection.car.Utils.KEYCODE_BACK;
import static com.projection.car.Utils.KEYCODE_OK;
import static com.projection.car.Utils.KEYCODE_SEEK_ADD;
import static com.projection.car.Utils.KEYCODE_SEEK_SUB;
import static com.projection.car.Utils.MEDIA;
import static com.projection.car.Utils.MSG_CMD_FOREGROUND;
import static com.projection.car.Utils.MSG_CMD_FOCUS_CHANGE;
import static com.projection.car.Utils.MSG_CMD_MD_FEATURE_CONFIG_REQUEST;
import static com.projection.car.Utils.MSG_CMD_HU_FEATURE_CONFIG_RESPONSE;
import static com.projection.car.Utils.MSG_CMD_HU_INFO;
import static com.projection.car.Utils.MSG_CMD_HU_PROTOCOL_VERSION;
import static com.projection.car.Utils.MSG_CMD_MD_AUTHEN_RESULT;
import static com.projection.car.Utils.MSG_CMD_MD_INFO;
import static com.projection.car.Utils.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS;
import static com.projection.car.Utils.MSG_CMD_STATISTIC_INFO;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_INIT;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_INIT_DONE;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_START;
import static com.projection.car.Utils.MSG_MEDIA_DATA;
import static com.projection.car.Utils.MSG_MEDIA_INIT;
import static com.projection.car.Utils.MSG_TOUCH_ACTION;
import static com.projection.car.Utils.MSG_TOUCH_CAR_HARD_KEY_CODE;
import static com.projection.car.Utils.MSG_TOUCH_PAD_DOWN;
import static com.projection.car.Utils.MSG_TOUCH_PAD_MOVE;
import static com.projection.car.Utils.MSG_TOUCH_PAD_UP;
import static com.projection.car.Utils.MSG_TOUCH_PAD_PINCH;
import static com.projection.car.Utils.MSG_VIDEO_DATA;
import static com.projection.car.Utils.MSG_WRITE_AUDIO;
import static com.projection.car.Utils.MSG_WRITE_VIDEO;
import static com.projection.car.Utils.REQUEST_CODE;
import static com.projection.car.Utils.TOUCH;
import static com.projection.car.Utils.VIDEO;
import static com.projection.car.Utils.bytesToInt2;
import static com.projection.car.Utils.bytesToShort2;
import static com.projection.car.Utils.exportCMDMsg;
import static com.projection.car.Utils.exportVideoMsg;
import static com.projection.car.Utils.intToBytes2;
import static com.projection.car.Utils.log;
import static com.projection.car.Utils.nextSong;
import static com.projection.car.Utils.previosSong;

public class MsgProcess {

    private boolean testAduio = false;


    private volatile boolean usbOk;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;
    private Activity mContext;


    private Handler mUsbReadHandler;
    private Handler mUsbWriteHandler;


    private AudioHandler mAudioReadHandler;

    private MediaCodecTool mMediaCodecTool;

    private Path mGesturePath = new Path();
    private int mGestureMoveCount = 0;
    private ArrayList<Float> mGestureMoveArray = new ArrayList<>();
    private long mGestureStartTime = 0;

    private long mPadStartTimestamp = 0;
    private int mPadMoveCount = 0;
    private long mPadSumDx = 0;
    private long mPadSumDy = 0;
    private long mPadAbsDx = 0;
    private long mPadAbsDy = 0;

    private float mVISWidth = 1280;
    private float mVISHeight = 720;
    private float mMobileWidth = 1920;
    private float mMobileHeight = 1080;
    private float mPortraitScreenVISGestureFactorW = 1.0f;
    private float mPortraitScreenVISGestureFactorH = 1.0f;
    private float mLandscapeScreenVISGestureFactorW = 1.0f;
    private float mLandscapeScreenVISGestureFactorH = 1.0f;

    private float mLeft_x;
    private Handler mMainHandler = new Handler();

    private int mVideoBit = 0;
    private int mVideoFrame = 0;
    private boolean mCarLifeMediaAudioEnabled = true;
    private InfoListener mInfoListener;

    MsgProcess(Activity context, int bit, int frame, InfoListener infoListener) {

        mContext = context;
        mInfoListener = infoListener;
        mVideoBit = bit;
        mVideoFrame = frame;
        mCarLifeMediaAudioEnabled = context.getSharedPreferences("set", MODE_PRIVATE)
                .getBoolean("carlife_media_audio", true);

        refreshSize();

        mMediaCodecTool = new MediaCodecTool();

        HandlerThread audioThread = new HandlerThread("audio");
        audioThread.start();
        mAudioReadHandler = new AudioHandler(audioThread.getLooper());


        startUsbTransferThread();

        if (testAduio) {
            mMediaCodecTool.startProjection(mContext, videoDataEncodeListener, REQUEST_CODE, mVISWidth, mVISHeight, mVideoBit, mVideoFrame);
            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
        }
    }

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {

            mInputStream = null;
            mOutputStream = null;
        }
    };

    private Runnable runnable_toast = new Runnable() {
        @Override
        public void run() {

            Toast.makeText(mContext, "当前版本未授权,稍后自动断连", Toast.LENGTH_LONG).show();
        }
    };

    public void startProjection(FileInputStream in, FileOutputStream out) {
        log("startProjection");
        usbOk = true;
        mInputStream = in;
        mOutputStream = out;
        mUsbReadHandler.sendEmptyMessage(0);

    }

    public void mediaPermissionOk(Activity activity, int resultCode, Intent resultData) {
        mMediaCodecTool.onActivityResult(
                activity,
                resultCode,
                resultData,
                new MediaCodecTool.ProjectionReadyListener() {
                    @Override
                    public void onProjectionReady() {
                        if (mCarLifeMediaAudioEnabled) {
                            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
                        } else {
                            log("[AUDIO] CarLife media audio disabled");
                        }
                    }
                }
        );
    }

    public synchronized void resetUsb() {
        if (usbOk) {
            log("resetUsb");
            usbOk = false;
            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_STOP);
            mMediaCodecTool.stopProjection();
            mUsbWriteHandler.removeCallbacksAndMessages(null);
            if (ForgroundService.mService != null) {
                ForgroundService.mService.hideCarCursor();
            }
        }

    }

    public void startReadAudio() {
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
    }

    public void updateVideoConfig(int videoFps, int videoBitrate) {
        mVideoBit = videoFps;
        mVideoFrame = videoBitrate;
        log("video config updated: " + videoFps + " fps, " + videoBitrate + " bps");
    }

    public void updateCarLifeMediaAudioEnabled(boolean enabled) {
        mCarLifeMediaAudioEnabled = enabled;
        log("[AUDIO] CarLife media audio setting=" + enabled + " (reconnect to renegotiate)");
        if (!enabled) {
            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_STOP);
        }
    }


    private MediaCodecTool.VideoDataEncodeListener videoDataEncodeListener = new MediaCodecTool.VideoDataEncodeListener() {
        @Override
        public void onData(byte[] data) {
            try {
//                                        log("data len = " + data.length);
                byte[] carLifeMsg = exportVideoMsg(MSG_VIDEO_DATA, data);
                byte[] headmsg = new byte[8];
                headmsg[3] = VIDEO;
                intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                CarMsg carMsg = new CarMsg(headmsg, carLifeMsg);
                mUsbWriteHandler.obtainMessage(MSG_WRITE_VIDEO, carMsg).sendToTarget();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    private void refreshSize() {

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mInfoListener.onVISSize((int) mVISWidth, (int) mVISHeight);

            }
        });


        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        manager.getDefaultDisplay().getRealMetrics(metrics);
        log("www = " + metrics.widthPixels + "  hhh = " + metrics.heightPixels);
        mMobileWidth = metrics.widthPixels;
        mMobileHeight = metrics.heightPixels;
        final SharedPreferences sharedPreferences = mContext.getSharedPreferences("set", MODE_PRIVATE);
        mMobileWidth = sharedPreferences.getFloat("mobile_w", (float) mMobileWidth);
        mMobileHeight = sharedPreferences.getFloat("mobile_h", (float) mMobileHeight);
        mLandscapeScreenVISGestureFactorW = mMobileWidth / mVISWidth;
        mLandscapeScreenVISGestureFactorH = mMobileHeight / mVISHeight;

        float portrixScrennWidth = mVISWidth * mVISHeight / mMobileWidth;// 车机竖屏的实际宽 用车机的高做投屏的高，保持比例
        mPortraitScreenVISGestureFactorW = mMobileHeight / (portrixScrennWidth);//竖屏下宽带除车机投屏实际屏幕宽度
        mPortraitScreenVISGestureFactorH = mMobileWidth / mVISHeight;

        mLeft_x = (mVISWidth - portrixScrennWidth) / 2.0f; //界面偏移值
        log("refreshSize w " + mMobileWidth + " h = " + mMobileHeight + ", mVISWidth " + mVISWidth + "mVISHeight" + mVISHeight + ", mirror = " + mPortraitScreenVISGestureFactorW + ", " + mPortraitScreenVISGestureFactorH +
                mLandscapeScreenVISGestureFactorW + ", " + mLandscapeScreenVISGestureFactorH + ", leftx " + mLeft_x);
    }

    private void genarateGesture(int type, float g_x, float g_y) {
        float x = 0;
        float y = 0;
        if (type == ACTION_DOWN) {
            mGestureStartTime = SystemClock.elapsedRealtime();
            mGesturePath.reset();
            mGestureMoveArray.clear();
            mGestureMoveCount = 0;
            int angle = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();

            if (angle == Surface.ROTATION_0) {
                x = (g_x - mLeft_x) * mPortraitScreenVISGestureFactorW;
                y = g_y * mPortraitScreenVISGestureFactorH;

            } else if (angle == Surface.ROTATION_90) {
                x = g_x * mLandscapeScreenVISGestureFactorW;
                y = g_y * mLandscapeScreenVISGestureFactorH;
            }
            log("now moveTo x = " + x + ", " + y);
            mGesturePath.moveTo(x, y);
            mGestureMoveArray.add(x);
            mGestureMoveArray.add(y);

        } else if (type == ACTION_UP) {

            long gestureTime = 30;
            if (mGestureMoveCount > 2) {
                gestureTime = SystemClock.elapsedRealtime() - mGestureStartTime;
                gestureTime = gestureTime > 300 ? 300 : gestureTime;
            }

            log("now dispatchGesture time is " + gestureTime);

            GestureDescription.StrokeDescription sd = new GestureDescription.StrokeDescription(mGesturePath, 0, gestureTime);

            if (ForgroundService.mService != null) {
                ForgroundService.mService.dispatchGesture(new GestureDescription.Builder().addStroke(sd).build(), new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                        log("now dispatchGesture ok");
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        super.onCancelled(gestureDescription);
                        log("now dispatchGesture cancle");
                    }
                }, null);
            }
        } else if (type == ACTION_MOVE) {
            int angle = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            if (angle == Surface.ROTATION_0 || angle == Surface.ROTATION_180) {
                x = (g_x - mLeft_x) * mPortraitScreenVISGestureFactorW;
                y = g_y * mPortraitScreenVISGestureFactorH;

            } else if (angle == Surface.ROTATION_90 || angle == Surface.ROTATION_270) {
                x = g_x * mLandscapeScreenVISGestureFactorW;
                y = g_y * mLandscapeScreenVISGestureFactorH;
            }
            log("now lineTo x = " + x + ", " + y);
            mGesturePath.lineTo(x, y);
            mGestureMoveArray.add(x);
            mGestureMoveArray.add(y);
            mGestureMoveCount++;
        }

    }


    class AudioHandler extends Handler {

        public static final int AUDIO_START = 0;
        public static final int AUDIO_READ = 1;
        public static final int AUDIO_STOP = 3;

        private AudioRecord mAudioRecord;
        private boolean mAudioStart;
        private int mAudioReadCount;
        private boolean mAudioSignalSeen;
        private FileOutputStream fileOutputStream;


        public AudioHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AUDIO_START:
                    try {
                        if (!mCarLifeMediaAudioEnabled) {
                            log("[AUDIO] capture skipped because CarLife media audio is disabled");
                            break;
                        }
                        if (mAudioStart) {
                            break;
                        }
                        if (testAduio) {
                            try {
                                fileOutputStream = new FileOutputStream("/sdcard/remix.pcm");
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                            }
                        }
                        int sample = 48000;

//                        if(!(isSystemApp(mContext) || isSystemUpdateApp(mContext)) && Build.VERSION.SDK_INT >= 29) {

                        if (Build.VERSION.SDK_INT >= 29) {

                            try {
                                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaCodecTool.getMediaProjection())
                                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                        .build();
                                int minBufferSize = AudioRecord.getMinBufferSize(sample, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                                mAudioRecord = new AudioRecord.Builder()
                                        .setAudioPlaybackCaptureConfig(config)
                                        .setAudioFormat(new AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(sample)
                                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                                .build())
                                        .setBufferSizeInBytes(minBufferSize)
                                        .build();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                        } else {
                            int minBufferSize = AudioRecord.getMinBufferSize(sample, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX, sample, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);

                        }

                        if (mAudioRecord == null) {
                            log("[AUDIO] AudioRecord creation failed");
                            break;
                        }
                        mAudioRecord.startRecording();
                        mAudioStart = true;
                        mAudioReadCount = 0;
                        mAudioSignalSeen = false;
                        log("[AUDIO] capture started 48000Hz stereo PCM16");
                        mAudioReadHandler.sendEmptyMessage(AUDIO_READ);
                    } catch (Exception e) {
                        e.printStackTrace();
                        mAudioStart = false;
                    }

                    break;
                case AUDIO_READ:
                    if (mAudioStart) {
                        byte[] data = new byte[2560];
                        int len = 0;
                        try {
                            len = mAudioRecord.read(data, 0, data.length);
                            if (len > 0) {
                                mAudioReadCount++;

                                if (!mAudioSignalSeen && mAudioReadCount <= 150) {
                                    int peak = 0;
                                    for (int i = 0; i + 1 < len; i += 4) {
                                        int sampleValue = (short) (
                                                (data[i] & 0xFF)
                                                        | (data[i + 1] << 8)
                                        );
                                        peak = Math.max(peak, Math.abs(sampleValue));
                                    }
                                    if (peak > 64) {
                                        mAudioSignalSeen = true;
                                        log(
                                                "[AUDIO] PCM signal detected peak="
                                                        + peak
                                                        + " after "
                                                        + mAudioReadCount
                                                        + " packets"
                                        );
                                    } else if (mAudioReadCount == 150) {
                                        log(
                                                "[AUDIO] PCM is still silent after ~2s; "
                                                        + "source app may block AudioPlaybackCapture"
                                        );
                                    }
                                }

                                if (!testAduio && mCarLifeMediaAudioEnabled) {
                                    byte[] carLifeMsg = exportVideoMsg(MSG_MEDIA_DATA, data, len);
                                    byte[] headmsg = new byte[8];
                                    headmsg[3] = MEDIA;
                                    intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                                    CarMsg carMsg = new CarMsg(headmsg, carLifeMsg);
                                    mUsbWriteHandler.obtainMessage(MSG_WRITE_AUDIO, carMsg).sendToTarget();
                                }
                            }

                            if (mAudioStart) {
                                mAudioReadHandler.sendEmptyMessage(AUDIO_READ);
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            mAudioStart = false;
                        }

                        if (testAduio) {
                            try {
                                fileOutputStream.write(data, 0, len);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    break;
                case AUDIO_STOP:
                    try {
                        if (mAudioStart) {
                            mAudioRecord.stop();
                            mAudioStart = false;
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        mAudioStart = false;
                    }
                    break;
            }

        }
    }

    private void startUsbTransferThread() {
        HandlerThread inthread = new HandlerThread("read");
        inthread.start();
        mUsbReadHandler = new Handler(inthread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 0: {
//                        mMainHandler.postDelayed(runnable_toast,300000 - 3000);
//                        mMainHandler.postDelayed(runnable,300000);
                        while (usbOk) {
                            try {
                                byte[] data = new byte[8];
                                int len = mInputStream.read(data);

                                if (len == 8) {
                                    int msg_type = data[3];
                                    log("msg_type = " + msg_type + ", read data = " + Arrays.toString(data));
                                    int msgLen = bytesToInt2(data, 4);
                                    log("msgLen = " + msgLen);
                                    byte[] msgdata = new byte[msgLen];
                                    len = mInputStream.read(msgdata);
                                    log("read data = " + Arrays.toString(msgdata));
                                    log("read msg data = " + len + " msgLen " + msgLen);
                                    short carmsgLen = bytesToShort2(msgdata, 0);
                                    int type = bytesToInt2(msgdata, 4);
                                    log("read carmsgLen data = " + carmsgLen + " type " + type);
                                    byte[] carmsg = new byte[carmsgLen];
                                    System.arraycopy(msgdata, 8, carmsg, 0, carmsgLen);
                                    msgdata = carmsg;
                                    if (msg_type == CMD) {
                                        switch (type) {
                                            case MSG_CMD_HU_PROTOCOL_VERSION: {
                                                CarlifeProtocolVersionMatchStatusProto.CarlifeProtocolVersionMatchStatus.Builder builder = CarlifeProtocolVersionMatchStatusProto.CarlifeProtocolVersionMatchStatus.newBuilder();
                                                builder.setMatchStatus(1);
                                                byte[] result = builder.build().toByteArray();
                                                log(" match = " + Arrays.toString(result));
                                                mUsbWriteHandler.obtainMessage(MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, exportCMDMsg(MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, result)).sendToTarget();
                                            }
                                            break;
                                            case MSG_CMD_HU_INFO: {
                                                try {
                                                    final CarlifeDeviceInfoProto.CarlifeDeviceInfo deviceInfo = CarlifeDeviceInfoProto.CarlifeDeviceInfo.parseFrom(msgdata);
                                                    log("os =" + deviceInfo.getOs() + ", cid =" + deviceInfo.getCid() + ", serial =" + deviceInfo.getSerial());


                                                } catch (InvalidProtocolBufferException e) {
                                                    e.printStackTrace();
                                                }

                                                CarlifeDeviceInfoProto.CarlifeDeviceInfo.Builder builder = CarlifeDeviceInfoProto.CarlifeDeviceInfo.newBuilder();
                                                builder.setSdkInt(29);
                                                builder.setSdk("29");
                                                builder.setSerial("unknown");
                                                builder.setCid("QKQ1.190828.002");
                                                builder.setBoard("sdm845");
                                                builder.setOs("Android");
                                                builder.setRelease("10");
                                                builder.setHost("c4-miui-ota-bd47.bj");
                                                mUsbWriteHandler.obtainMessage(MSG_CMD_MD_INFO, exportCMDMsg(MSG_CMD_MD_INFO, builder.build().toByteArray())).sendToTarget();

                                                CarlifeFeatureConfigProto.CarlifeFeatureConfig focusUi =
                                                        CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                                                                .setKey("FOCUS_UI")
                                                                .setValue(1)
                                                                .build();
                                                CarlifeFeatureConfigProto.CarlifeFeatureConfig audioMode =
                                                        CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                                                                .setKey("AUDIO_TRANSMISSION_MODE")
                                                                .setValue(mCarLifeMediaAudioEnabled ? 0 : 1)
                                                                .build();
                                                CarlifeFeatureConfigProto.CarlifeFeatureConfig mediaSampleRate =
                                                        CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                                                                .setKey("MEDIA_SAMPLE_RATE")
                                                                .setValue(mCarLifeMediaAudioEnabled ? 1 : 0)
                                                                .build();
                                                CarlifeFeatureConfigListProto.CarlifeFeatureConfigList featureRequest =
                                                        CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.newBuilder()
                                                                .setCnt(3)
                                                                .addFeatureConfig(focusUi)
                                                                .addFeatureConfig(audioMode)
                                                                .addFeatureConfig(mediaSampleRate)
                                                                .build();
                                                log(
                                                        "[FEATURE] request FOCUS_UI=1"
                                                                + " AUDIO_TRANSMISSION_MODE="
                                                                + (mCarLifeMediaAudioEnabled ? 0 : 1)
                                                                + " MEDIA_SAMPLE_RATE="
                                                                + (mCarLifeMediaAudioEnabled ? 1 : 0)
                                                );
                                                mUsbWriteHandler.obtainMessage(
                                                        MSG_CMD_MD_FEATURE_CONFIG_REQUEST,
                                                        exportCMDMsg(
                                                                MSG_CMD_MD_FEATURE_CONFIG_REQUEST,
                                                                featureRequest.toByteArray()
                                                        )
                                                ).sendToTarget();
                                            }
                                            break;
                                            case MSG_CMD_VIDEO_ENCODER_INIT: {
                                                try {
                                                    CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo encoderInfo = CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo.parseFrom(msgdata);
                                                    log("encoderInfo = " + encoderInfo.getWidth() + ", " + encoderInfo.getHeight() + ", " + encoderInfo.getFrameRate());
                                                    if (encoderInfo.getWidth() > 10 && encoderInfo.getHeight() > 10) {
                                                        mVISWidth = encoderInfo.getWidth();
                                                        mVISHeight = encoderInfo.getHeight();
                                                        refreshSize();
                                                        log("get cheji MirrorWidth = " + mVISWidth + ", MirrorHeight" + mVISHeight);
                                                    }

                                                } catch (InvalidProtocolBufferException e) {
                                                    e.printStackTrace();
                                                }

                                                CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo.Builder builder = CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo.newBuilder();
                                                builder.setFrameRate(mVideoBit);
                                                builder.setWidth((int) mVISWidth);
                                                builder.setHeight((int) mVISHeight);
                                                mUsbWriteHandler.obtainMessage(MSG_CMD_VIDEO_ENCODER_INIT_DONE, exportCMDMsg(MSG_CMD_VIDEO_ENCODER_INIT_DONE, msgdata)).sendToTarget();


                                            }
                                            break;
                                            case MSG_CMD_VIDEO_ENCODER_START: {
                                                mUsbWriteHandler.obtainMessage(MSG_CMD_VIDEO_ENCODER_START).sendToTarget();
                                            }
                                            break;
                                            case MSG_CMD_HU_FEATURE_CONFIG_RESPONSE: {
                                                try {
                                                    CarlifeFeatureConfigListProto.CarlifeFeatureConfigList response =
                                                            CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.parseFrom(msgdata);
                                                    log("[FEATURE] HU response cnt=" + response.getCnt());
                                                    for (CarlifeFeatureConfigProto.CarlifeFeatureConfig feature
                                                            : response.getFeatureConfigList()) {
                                                        log(
                                                                "[FEATURE] HU "
                                                                        + feature.getKey()
                                                                        + "="
                                                                        + feature.getValue()
                                                        );
                                                    }
                                                } catch (Exception e) {
                                                    log("[FEATURE] response parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_CMD_FOCUS_CHANGE: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadFocus focus =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadFocus.parseFrom(msgdata);
                                                    log("[FOCUS] CHANGE ts=" + focus.getTimestamp() + " channel=CMD");
                                                } catch (Exception e) {
                                                    log("[FOCUS] CHANGE rawLen=" + msgdata.length + " channel=CMD parseError=" + e);
                                                }
                                            }
                                            break;
                                            case MSG_CMD_STATISTIC_INFO: {

                                                try {
                                                    final CarlifeStatisticsInfoProto.CarlifeStatisticsInfo statisticsInfo = CarlifeStatisticsInfoProto.CarlifeStatisticsInfo.parseFrom(msgdata);
                                                    log("getCuid = " + statisticsInfo.getCuid() + "" + statisticsInfo.getVersionName() + statisticsInfo.getConnectTime() + statisticsInfo.getCrashLog());
                                                    mMainHandler.post(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            mInfoListener.onVISID(statisticsInfo.getCuid());
                                                        }
                                                    });
                                                } catch (InvalidProtocolBufferException e) {
                                                    e.printStackTrace();
                                                }
                                                CarlifeAuthenResultProto.CarlifeAuthenResult.Builder builder = CarlifeAuthenResultProto.CarlifeAuthenResult.newBuilder();
                                                builder.setResult(true);
                                                mUsbWriteHandler.obtainMessage(MSG_CMD_MD_AUTHEN_RESULT, exportCMDMsg(MSG_CMD_MD_AUTHEN_RESULT, builder.build().toByteArray())).sendToTarget();
                                            }
                                            break;


                                        }
                                    } else if (msg_type == TOUCH) {
                                        switch (type) {
                                            case MSG_TOUCH_CAR_HARD_KEY_CODE: {
                                                CarlifeCarHardKeyCodeProto.CarlifeCarHardKeyCode keyCode = CarlifeCarHardKeyCodeProto.CarlifeCarHardKeyCode.parseFrom(msgdata);
                                                int carKeyCode = keyCode.getKeycode();
                                                log("[KEY] " + Utils.carKeyName(carKeyCode) + " (" + carKeyCode + ")");
                                                switch (carKeyCode) {
                                                    case KEYCODE_OK: {
                                                        if (ForgroundService.mService != null) {
                                                            ForgroundService.mService.onCarOk();
                                                        }
                                                    }
                                                    break;
                                                    case KEYCODE_BACK: {
                                                        if (ForgroundService.mService != null) {
                                                            ForgroundService.mService.onCarBack();
                                                        }
                                                    }
                                                    break;
                                                    case KEYCODE_SEEK_SUB: {
                                                        previosSong();
                                                    }
                                                    break;
                                                    case KEYCODE_SEEK_ADD: {
                                                        nextSong();
                                                    }
                                                    break;
                                                }

                                            }
                                            break;
                                            case MSG_TOUCH_ACTION: {
                                                try {
                                                    CarlifeTouchActionProto.CarlifeTouchAction action = CarlifeTouchActionProto.CarlifeTouchAction.parseFrom(msgdata);
                                                    log(
                                                            "[TOUCH] "
                                                                    + Utils.touchActionName(action.getAction())
                                                                    + " x=" + action.getX()
                                                                    + " y=" + action.getY()
                                                    );
                                                    genarateGesture(action.getAction(), action.getX(), action.getY());
                                                } catch (Exception e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                            break;
                                            case MSG_CMD_FOCUS_CHANGE: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadFocus focus =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadFocus.parseFrom(msgdata);
                                                    log("[FOCUS] CHANGE ts=" + focus.getTimestamp() + " channel=TOUCH");
                                                } catch (Exception e) {
                                                    log("[FOCUS] CHANGE rawLen=" + msgdata.length + " channel=TOUCH parseError=" + e);
                                                }
                                            }
                                            break;
                                            case MSG_TOUCH_PAD_DOWN: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadDown down =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadDown.parseFrom(msgdata);
                                                    mPadStartTimestamp = down.getTimestamp();
                                                    mPadMoveCount = 0;
                                                    mPadSumDx = 0;
                                                    mPadSumDy = 0;
                                                    mPadAbsDx = 0;
                                                    mPadAbsDy = 0;
                                                    log("[PAD] DOWN ts=" + down.getTimestamp());
                                                    if (ForgroundService.mService != null) {
                                                        ForgroundService.mService.onCarPadDown();
                                                    }
                                                } catch (Exception e) {
                                                    log("[PAD] DOWN parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_TOUCH_PAD_MOVE: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadMove move =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadMove.parseFrom(msgdata);
                                                    int dx = move.getDeltaX();
                                                    int dy = move.getDeltaY();
                                                    mPadMoveCount++;
                                                    mPadSumDx += dx;
                                                    mPadSumDy += dy;
                                                    mPadAbsDx += Math.abs((long) dx);
                                                    mPadAbsDy += Math.abs((long) dy);
                                                    log(
                                                            "[PAD] MOVE dx=" + dx
                                                                    + " dy=" + dy
                                                                    + " ts=" + move.getTimestamp()
                                                    );
                                                    if (ForgroundService.mService != null) {
                                                        ForgroundService.mService.onCarPadMove(dx, dy);
                                                    }
                                                } catch (Exception e) {
                                                    log("[PAD] MOVE parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_TOUCH_PAD_UP: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadUp up =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadUp.parseFrom(msgdata);
                                                    log("[PAD] UP ts=" + up.getTimestamp());
                                                    if (ForgroundService.mService != null) {
                                                        ForgroundService.mService.onCarPadUp();
                                                    }
                                                    long duration = mPadStartTimestamp == 0
                                                            ? 0
                                                            : up.getTimestamp() - mPadStartTimestamp;
                                                    String candidate = mPadMoveCount == 0
                                                            ? " TAP_CANDIDATE"
                                                            : "";
                                                    log(
                                                            "[PAD-GESTURE]"
                                                                    + candidate
                                                                    + " duration=" + duration + "ms"
                                                                    + " moves=" + mPadMoveCount
                                                                    + " sumDx=" + mPadSumDx
                                                                    + " sumDy=" + mPadSumDy
                                                                    + " absDx=" + mPadAbsDx
                                                                    + " absDy=" + mPadAbsDy
                                                    );
                                                } catch (Exception e) {
                                                    log("[PAD] UP parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_TOUCH_PAD_PINCH: {
                                                try {
                                                    CarLifeTouchPadActionProto.CarlifeTouchPadPinch pinch =
                                                            CarLifeTouchPadActionProto.CarlifeTouchPadPinch.parseFrom(msgdata);
                                                    log("[PAD] PINCH scale=" + pinch.getScale());
                                                } catch (Exception e) {
                                                    log("[PAD] PINCH parse error: " + e);
                                                }
                                            }
                                            break;
                                        }


                                    }


                                } else {
                                    log("read data = " + len + "  " + data.length);
                                }

                            } catch (Exception e) {
                                e.printStackTrace();

                                resetUsb();
                                break;
                            }

                            //SystemClock.sleep(10);
                        }
                    }
                }
            }
        };

        HandlerThread outthread = new HandlerThread("write");
        outthread.start();
        mUsbWriteHandler = new Handler(outthread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);

                try {
                    switch (msg.what) {
                        case MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS:
                        case MSG_CMD_MD_INFO:
                        case MSG_CMD_MD_AUTHEN_RESULT:
                        case MSG_CMD_MD_FEATURE_CONFIG_REQUEST: {
                            byte[] carLifeMsg = (byte[]) msg.obj;
                            byte[] headmsg = new byte[8];
                            headmsg[3] = CMD;
                            intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                            mOutputStream.write(headmsg);
                            log("msg=" + msg.what + "write data =" + Arrays.toString(headmsg));
                            mOutputStream.write(carLifeMsg);
                            log("msg=" + msg.what + "write data =" + Arrays.toString(carLifeMsg));
                            log("write data ok");
                        }
                        break;
                        case MSG_CMD_VIDEO_ENCODER_INIT_DONE: {

                            {
                                byte[] carLifeMsg = (byte[]) msg.obj;
                                byte[] headmsg = new byte[8];
                                headmsg[3] = CMD;
                                intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                                mOutputStream.write(headmsg);
                                log("msg=" + msg.what + "write data =" + Arrays.toString(headmsg));
                                mOutputStream.write(carLifeMsg);
                                log("msg=" + msg.what + "write data =" + Arrays.toString(carLifeMsg));
                                log("write data ok");
                            }

                            {
                                byte[] carLifeMsg = exportCMDMsg(MSG_CMD_FOREGROUND, null);
                                byte[] headmsg = new byte[8];
                                headmsg[3] = CMD;
                                intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                                mOutputStream.write(headmsg);
                                log("msg=" + MSG_CMD_FOREGROUND + "write data =" + Arrays.toString(headmsg));
                                mOutputStream.write(carLifeMsg);
                                log("msg=" + MSG_CMD_FOREGROUND + "write data =" + Arrays.toString(carLifeMsg));
                                log("write data ok");
                            }


                        }
                        break;
                        case MSG_CMD_VIDEO_ENCODER_START: {
                            log("now start MSG_CMD_VIDEO_ENCODER_START");

                            if (mCarLifeMediaAudioEnabled) {
                                CarlifeMusicInitProto.CarlifeMusicInit.Builder builder =
                                        CarlifeMusicInitProto.CarlifeMusicInit.newBuilder();
                                builder.setSampleRate(48000);
                                builder.setChannelConfig(2);
                                builder.setSampleFormat(16);
                                byte[] carLifeMsg = exportVideoMsg(
                                        MSG_MEDIA_INIT,
                                        builder.build().toByteArray()
                                );
                                byte[] headmsg = new byte[8];
                                headmsg[3] = MEDIA;
                                intToBytes2(carLifeMsg.length, headmsg, 4);
                                mOutputStream.write(headmsg);
                                mOutputStream.write(carLifeMsg);
                                log(
                                        "[AUDIO] MEDIA_INIT sent 48000Hz"
                                                + " channelConfig=2 sampleFormat=16"
                                );
                            } else {
                                log("[AUDIO] MEDIA_INIT skipped; Bluetooth media mode requested");
                            }

                            mMediaCodecTool.startProjection(
                                    mContext,
                                    videoDataEncodeListener,
                                    REQUEST_CODE,
                                    mVISWidth,
                                    mVISHeight,
                                    mVideoBit,
                                    mVideoFrame
                            );
                        }
                        break;
                        case MSG_WRITE_AUDIO:
                        case MSG_WRITE_VIDEO: {
                            //log("write audio or video ..................." + msg.what);
                            CarMsg carMsg = (CarMsg) msg.obj;
                            mOutputStream.write(carMsg.head);
                            mOutputStream.write(carMsg.msg);
                        }
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    resetUsb();
                }
            }
        };
    }

    public boolean isSystemApp(Context context) {
        return ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0);
    }

    public boolean isSystemUpdateApp(Context context) {
        return ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
    }

    public interface InfoListener {
        void onVISSize(int x, int y);

        void onVISID(String id);
    }

    static class CarMsg {
        byte[] head;
        byte[] msg;

        CarMsg(byte[] b1, byte[] b3) {
            head = b1;
            msg = b3;
        }
    }
}
