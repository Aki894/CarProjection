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
import com.baidu.carlife.protobuf.CarlifeModuleStatusProto;
import com.baidu.carlife.protobuf.CarlifeTouchActionProto;
import com.baidu.carlife.protobuf.CarlifeTTSInitProto;
import com.example.car.CarlifeAuthenResultProto;
import com.example.car.CarlifeDeviceInfoProto;
import com.example.car.CarlifeProtocolVersionMatchStatusProto;
import com.example.car.CarlifeStatisticsInfoProto;
import com.example.car.CarlifeVideoEncoderInfoProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.yftech.CarLifeTouchPadActionProto;
import com.baidu.carlife.protobuf.CarlifeFeatureConfigProto;
import com.baidu.carlife.protobuf.CarlifeFeatureConfigListProto;
import com.baidu.carlife.protobuf.CarlifeHuRsaPublicKeyResponseProto;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
import static com.projection.car.Utils.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST;
import static com.projection.car.Utils.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE;
import static com.projection.car.Utils.MSG_CMD_MODULE_CONTROL;
import static com.projection.car.Utils.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS;
import static com.projection.car.Utils.MSG_CMD_SCREEN_ON;
import static com.projection.car.Utils.MSG_CMD_STATISTIC_INFO;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_INIT;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_INIT_DONE;
import static com.projection.car.Utils.MSG_CMD_VIDEO_ENCODER_START;
import static com.projection.car.Utils.MSG_MEDIA_DATA;
import static com.projection.car.Utils.MSG_MEDIA_INIT;
import static com.projection.car.Utils.MSG_NAVI_TTS_INIT;
import static com.projection.car.Utils.MSG_NAVI_TTS_DATA;
import static com.projection.car.Utils.MSG_NAVI_TTS_END;
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
import static com.projection.car.Utils.TTS;
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
    private volatile int mUsbGeneration;
    private volatile boolean mReleased;
    private HandlerThread mAudioThread;
    private HandlerThread mUsbReadThread;
    private HandlerThread mUsbWriteThread;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;
    private Activity mContext;


    private Handler mUsbReadHandler;
    private Handler mUsbWriteHandler;
    private final AtomicInteger mPendingAudioPackets = new AtomicInteger();


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
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    private int mVideoBit = 0;
    private int mVideoFrame = 0;
    private volatile boolean mCarLifeMediaAudioEnabled = true;
    private volatile boolean mTtsAudioCompatibilityEnabled;
    private volatile int mTtsSampleRate = 48000;
    private final int mTtsChannels = 1;
    private int mTtsWireChannels = 1;
    private volatile int mCarAudioVolumePercent;
    private final PcmVolume mPcmVolume;
    private volatile boolean mAudioTestToneActive;
    private volatile boolean mCancelAudioTest;
    private Integer mHuAudioTransmissionMode;
    private Integer mHuMediaSampleRate;
    private Integer mHuContentEncryption;
    private int mEncryptionProbeGeneration;
    private InfoListener mInfoListener;

    MsgProcess(Activity context, int bit, int frame, InfoListener infoListener) {

        mContext = context;
        mInfoListener = infoListener;
        mVideoBit = bit;
        mVideoFrame = frame;
        mCarLifeMediaAudioEnabled = context.getSharedPreferences("set", MODE_PRIVATE)
                .getBoolean("carlife_media_audio", true);
        mTtsAudioCompatibilityEnabled = context.getSharedPreferences("set", MODE_PRIVATE)
                .getBoolean("tts_audio_compatibility", false);

        SharedPreferences audioPrefs = context.getSharedPreferences("set", MODE_PRIVATE);
        mTtsSampleRate = audioPrefs.getInt("tts_sample_rate", 48000);
        boolean validRate = false;
        for (int rate : TtsPcmConverter.RATES) validRate |= mTtsSampleRate == rate;
        if (!validRate) mTtsSampleRate = 48000;
        mCarAudioVolumePercent = Math.max(0, Math.min(100,
                context.getSharedPreferences("set", MODE_PRIVATE).getInt("car_audio_volume", 30)));
        mPcmVolume = new PcmVolume(mCarAudioVolumePercent / 100.0);
        refreshSize();

        mMediaCodecTool = new MediaCodecTool();

        mAudioThread = new HandlerThread("audio");
        mAudioThread.start();
        mAudioReadHandler = new AudioHandler(mAudioThread.getLooper());


        startUsbTransferThread();

        if (testAduio) {
            mMediaCodecTool.startProjection(mContext, videoDataEncodeListener, REQUEST_CODE, mVISWidth, mVISHeight, mVideoBit, mVideoFrame);
            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
        }
    }

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
                if (mReleased || mInfoListener == null) return;

            mInputStream = null;
            mOutputStream = null;
        }
    };

    private Runnable runnable_toast = new Runnable() {
        @Override
        public void run() {
                if (mReleased || mInfoListener == null) return;

            Toast.makeText(mContext, "当前版本未授权,稍后自动断连", Toast.LENGTH_LONG).show();
        }
    };

    public synchronized void startProjection(FileInputStream in, FileOutputStream out) {
        if (mReleased) return;
        final int generation = ++mUsbGeneration;
        log("startProjection");
        log("[AUDIO] car output volume=" + mCarAudioVolumePercent + "% (PCM gain)");
        mHuAudioTransmissionMode = null;
        mHuMediaSampleRate = null;
        mHuContentEncryption = null;
        notifyAudioFeatureStatus();
        usbOk = true;
        mInputStream = in;
        mOutputStream = out;
        mUsbReadHandler.obtainMessage(0, generation, 0).sendToTarget();

    }

    public void mediaPermissionOk(Activity activity, int resultCode, Intent resultData) {
        mMediaCodecTool.onActivityResult(
                activity,
                resultCode,
                resultData,
                new MediaCodecTool.ProjectionReadyListener() {
                    @Override
                    public void onProjectionReady() {
                        if (isUsbAudioEnabled()) {
                            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
                        } else {
                            log("[AUDIO] CarLife USB audio disabled");
                        }
                    }

                    @Override
                    public void onProjectionStopped() {
                        mCancelAudioTest = true;
                        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_STOP);
                    }
                }
        );
    }

    public synchronized void resetUsb() {
        mCancelAudioTest = true;
        if (usbOk) {
            log("resetUsb");
            usbOk = false;
            mUsbGeneration++;
            mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_STOP);
            mMediaCodecTool.stopProjection();
            mUsbWriteHandler.removeCallbacksAndMessages(null);
            mPendingAudioPackets.set(0);
            if (ForgroundService.mService != null) {
                ForgroundService.mService.hideCarCursor();
            }
        }

    }

    public void release() {
        mReleased = true;
        resetUsb();
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_STOP);
        mMainHandler.removeCallbacksAndMessages(null);
        mInfoListener = null;
        mAudioThread.quitSafely();
        mUsbReadThread.quitSafely();
        mUsbWriteThread.quitSafely();
    }

    public void startReadAudio() {
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_START);
    }

    public void updateVideoConfig(int videoFps, int videoBitrate) {
        mVideoBit = videoFps;
        mVideoFrame = videoBitrate;
        log("video config updated: " + videoFps + " fps, " + videoBitrate + " bps");
    }

    private boolean isUsbAudioEnabled() {
        return mCarLifeMediaAudioEnabled || mTtsAudioCompatibilityEnabled;
    }

    public void updateCarLifeMediaAudioEnabled(boolean enabled) {
        mCancelAudioTest = true;
        mCarLifeMediaAudioEnabled = enabled;
        log("[AUDIO] USB media setting=" + enabled + " (reconnect to renegotiate)");
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_RECONFIGURE);
    }

    public void updateTtsAudioFormat(int sampleRate) {
        if (mTtsSampleRate == sampleRate) return;
        mCancelAudioTest = true;
        mTtsSampleRate = sampleRate;
        log("[TTS-AUDIO] selected rate=" + sampleRate + " channels=1");
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_RECONFIGURE);
    }

    public void updateCarAudioVolume(int percent) {
        mCarAudioVolumePercent = Math.max(0, Math.min(100, percent));
        log("[AUDIO] car output volume=" + mCarAudioVolumePercent + "% (PCM gain)");
    }

    public void updateTtsAudioCompatibilityEnabled(boolean enabled) {
        mCancelAudioTest = true;
        mTtsAudioCompatibilityEnabled = enabled;
        log("[TTS-AUDIO] compatibility=" + enabled + " (reconnect to renegotiate)");
        mAudioReadHandler.sendEmptyMessage(AudioHandler.AUDIO_RECONFIGURE);
    }

    public boolean requestEncryptionProbe() {
        if (!usbOk || mOutputStream == null) {
            log("[ENCRYPT] probe rejected: CarLife is not connected");
            return false;
        }

        final int generation = ++mEncryptionProbeGeneration;
        notifyEncryptionProbe(0, 0);
        log("[ENCRYPT] RSA public key probe request sent");

        mUsbWriteHandler.obtainMessage(
                MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST,
                exportCMDMsg(MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST, null)
        ).sendToTarget();

        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mReleased || mInfoListener == null) return;
                if (generation == mEncryptionProbeGeneration) {
                    log("[ENCRYPT] RSA probe timeout");
                    notifyEncryptionProbe(-1, 0);
                }
            }
        }, 1500);

        return true;
    }

    public boolean playAudioTestTone() {
        return startAudioTest(AudioHandler.AUDIO_TEST_MEDIA_48K);
    }

    public boolean playAudioTestTone44k() {
        return startAudioTest(AudioHandler.AUDIO_TEST_MEDIA_44K);
    }

    public boolean playTtsTestTone() {
        return startAudioTest(AudioHandler.AUDIO_TEST_TTS_16K);
    }

    public boolean playConfiguredTtsTestTone() {
        return startAudioTest(AudioHandler.AUDIO_TEST_TTS_SELECTED);
    }

    private synchronized boolean startAudioTest(int what) {
        if (mTtsAudioCompatibilityEnabled && what != AudioHandler.AUDIO_TEST_TTS_16K
                && what != AudioHandler.AUDIO_TEST_TTS_SELECTED) {
            log("[AUDIO-TEST] MEDIA test disabled in TTS compatibility mode");
            return false;
        }
        if (!usbOk || mOutputStream == null) {
            log("[AUDIO-TEST] rejected: CarLife is not connected");
            return false;
        }
        if (mAudioTestToneActive) {
            log("[AUDIO-TEST] already running");
            return false;
        }
        mCancelAudioTest = false;
        mAudioTestToneActive = true;
        mAudioReadHandler.sendEmptyMessage(what);
        return true;
    }

    private void notifyAudioFeatureStatus() {
        if (mInfoListener == null) {
            return;
        }

        final Integer mode = mHuAudioTransmissionMode;
        final Integer sampleRate = mHuMediaSampleRate;
        final Integer contentEncryption = mHuContentEncryption;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mReleased || mInfoListener == null) return;
                mInfoListener.onAudioFeatures(mode, sampleRate, contentEncryption);
            }
        });
    }

    private void notifyEncryptionProbe(final int state, final int keyLength) {
        if (mInfoListener == null) {
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mReleased || mInfoListener == null) return;
                mInfoListener.onEncryptionProbe(state, keyLength);
            }
        });
    }

    private void notifyModuleControl(final int moduleId, final int statusId) {
        if (mInfoListener == null) {
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mReleased || mInfoListener == null) return;
                mInfoListener.onModuleControl(moduleId, statusId);
            }
        });
    }


    private MediaCodecTool.VideoDataEncodeListener videoDataEncodeListener = new MediaCodecTool.VideoDataEncodeListener() {
        @Override
        public void onData(byte[] data) {
            if (!usbOk || mReleased) return;
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
                if (mReleased || mInfoListener == null) return;
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
        public static final int AUDIO_TEST_MEDIA_48K = 4;
        public static final int AUDIO_TEST_MEDIA_44K = 5;
        public static final int AUDIO_TEST_TTS_16K = 6;

        public static final int AUDIO_RECONFIGURE = 7;
        public static final int AUDIO_TEST_TTS_SELECTED = 8;

        private AudioRecord mAudioRecord;
        private boolean mAudioStart;
        private boolean mCaptureTtsMode;
        private int mCaptureRate;
        private int mCaptureChannels;
        private TtsPcmConverter mTtsConverter;
        private boolean mTtsSessionOpen;
        private final byte[] mCaptureBuffer = new byte[3840]; // 20 ms at 48k stereo
        private byte[] mTtsPacket = new byte[640]; // 20 ms in the selected TTS format
        private int mTtsPacketBytes;
        private long mCapturedBytes;
        private long mQueuedBytes;
        private long mQueuedPackets;
        private long mDroppedPackets;
        private AudioWriteStats mWriteStats = new AudioWriteStats();
        private boolean mAudioSignalSeen;
        private long mLastStatsTime;
        private long mStatsSamples;
        private double mStatsSquares;
        private int mStatsPeak;

        public AudioHandler(Looper looper) {
            super(looper);
        }

        private String audioTag() {
            return mCaptureTtsMode ? "[TTS-AUDIO] " : "[AUDIO] ";
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case AUDIO_START:
                    startCapture();
                    break;
                case AUDIO_READ:
                    readCapture();
                    break;
                case AUDIO_STOP:
                    removeMessages(AUDIO_START);
                    removeMessages(AUDIO_TEST_MEDIA_48K);
                    removeMessages(AUDIO_TEST_MEDIA_44K);
                    removeMessages(AUDIO_TEST_TTS_16K);
                    removeMessages(AUDIO_TEST_TTS_SELECTED);
                    mAudioTestToneActive = false;
                    stopCapture();
                    break;
                case AUDIO_RECONFIGURE:
                    stopCapture();
                    if (usbOk && mMediaCodecTool.getMediaProjection() != null) {
                        startCapture();
                    }
                    break;
                case AUDIO_TEST_MEDIA_48K:
                    if (!mTtsAudioCompatibilityEnabled) runMediaTestTone(48000);
                    else mAudioTestToneActive = false;
                    break;
                case AUDIO_TEST_MEDIA_44K:
                    if (!mTtsAudioCompatibilityEnabled) runMediaTestTone(44100);
                    else mAudioTestToneActive = false;
                    break;
                case AUDIO_TEST_TTS_16K:
                    runTtsTestTone(16000, 1);
                    break;
                case AUDIO_TEST_TTS_SELECTED:
                    runTtsTestTone(mTtsSampleRate, mTtsChannels);
                    break;
            }
        }

        private void startCapture() {
            if (mAudioStart || !usbOk || !isUsbAudioEnabled()
                    || mMediaCodecTool.getMediaProjection() == null) {
                return;
            }
            mCaptureTtsMode = mTtsAudioCompatibilityEnabled;
            mCaptureRate = mTtsSampleRate;
            mCaptureChannels = mTtsChannels;
            try {
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(mMediaCodecTool.getMediaProjection())
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();
                int minimum = AudioRecord.getMinBufferSize(48000,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) {
                    throw new IllegalStateException("invalid AudioRecord buffer size " + minimum);
                }
                mAudioRecord = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(48000)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minimum, mCaptureBuffer.length * 4))
                        .build();
                if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord not initialized");
                }
                mAudioRecord.startRecording();
                if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("AudioRecord did not start");
                }
                mAudioStart = true;
                mTtsPacketBytes = 0;
                mCapturedBytes = mQueuedBytes = mQueuedPackets = mDroppedPackets = 0;
                mWriteStats = new AudioWriteStats();
                mStatsSamples = 0;
                mStatsSquares = 0;
                mStatsPeak = 0;
                mAudioSignalSeen = false;
                mLastStatsTime = SystemClock.elapsedRealtime();
                log(audioTag() + "capture started: 48000Hz stereo PCM16");
                if (mCaptureTtsMode) {
                    mTtsConverter = new TtsPcmConverter(mCaptureRate, mCaptureChannels);
                    mTtsPacket = new byte[mCaptureRate / 50 * mCaptureChannels * 2];
                    log(audioTag() + "convert 48000/2ch -> " + mCaptureRate + "/"
                            + mCaptureChannels + "ch PCM16");
                    sendTtsInitPacket(mCaptureRate, mCaptureChannels);
                    mTtsSessionOpen = true;
                } else {
                    sendMediaInitPacket();
                }
                sendEmptyMessage(AUDIO_READ);
            } catch (Exception e) {
                log(audioTag() + "capture start failed: " + e);
                stopCapture();
            }
        }

        private void readCapture() {
            if (!mAudioStart) return;
            if (!usbOk || !isUsbAudioEnabled()
                    || mMediaCodecTool.getMediaProjection() == null) {
                stopCapture();
                return;
            }
            try {
                int length = mAudioRecord.read(mCaptureBuffer, 0, mCaptureBuffer.length,
                        AudioRecord.READ_BLOCKING);
                if (length < 0) {
                    throw new IllegalStateException("AudioRecord.read error=" + length);
                }
                if (length > 0) {
                    mCapturedBytes += length;
                    recordPcmStats(mCaptureBuffer, length);
                    if (mCaptureTtsMode) {
                        byte[] mono = mTtsConverter.convert(mCaptureBuffer, length);
                        int offset = 0;
                        while (offset < mono.length) {
                            int count = Math.min(mTtsPacket.length - mTtsPacketBytes,
                                    mono.length - offset);
                            System.arraycopy(mono, offset, mTtsPacket, mTtsPacketBytes, count);
                            offset += count;
                            mTtsPacketBytes += count;
                            if (mTtsPacketBytes == mTtsPacket.length) {
                                queueCaptureData(TTS, MSG_NAVI_TTS_DATA, mTtsPacket, mTtsPacketBytes);
                                mTtsPacketBytes = 0;
                            }
                        }
                    } else {
                        queueCaptureData(MEDIA, MSG_MEDIA_DATA, mCaptureBuffer, length);
                    }
                }
                // Yield to STOP/settings even if an OEM returns no data without blocking.
                sendEmptyMessageDelayed(AUDIO_READ, length == 0 ? 10 : 0);
            } catch (Exception e) {
                log(audioTag() + "capture read failed: " + e);
                stopCapture();
            }
        }

        private void queueCaptureData(byte channel, int type, byte[] data, int length) {
            if (enqueueAudioPacket(channel, type, data, length, mWriteStats)) {
                mQueuedBytes += length;
                mQueuedPackets++;
            } else {
                mDroppedPackets++;
            }
        }

        private void recordPcmStats(byte[] data, int length) {
            int peak = 0;
            for (int i = 0; i + 1 < length; i += 2) {
                int value = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                peak = Math.max(peak, Math.abs(value));
                mStatsSquares += (double) value * value;
                mStatsSamples++;
            }
            mStatsPeak = Math.max(mStatsPeak, peak);
            if (!mAudioSignalSeen && peak > 64) {
                mAudioSignalSeen = true;
                log(audioTag() + "PCM active rms="
                        + Math.round(Math.sqrt(mStatsSquares / Math.max(1, mStatsSamples)))
                        + " peak=" + peak);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - mLastStatsTime >= 2000) {
                long rms = Math.round(Math.sqrt(mStatsSquares / Math.max(1, mStatsSamples)));
                log(audioTag() + "PCM rms=" + rms + " peak=" + mStatsPeak
                        + " captured=" + mCapturedBytes / 1024 + "KB queued="
                        + mQueuedBytes / 1024 + "KB queuedPackets=" + mQueuedPackets
                        + " sent=" + mWriteStats.bytes.get() / 1024 + "KB packets="
                        + mWriteStats.packets.get()
                        + " dropped=" + mDroppedPackets + " volume=" + mCarAudioVolumePercent + "%");
                if (!mAudioSignalSeen) {
                    log(audioTag() + "PCM still silent; check RECORD_AUDIO permission, "
                            + "source app capture policy/usage (navigation guidance may be excluded)");
                }
                mLastStatsTime = now;
                mStatsSamples = 0;
                mStatsSquares = 0;
                mStatsPeak = 0;
            }
        }

        private void stopCapture() {
            removeMessages(AUDIO_READ);
            boolean wasStarted = mAudioStart;
            mAudioStart = false;
            if (mAudioRecord != null) {
                try {
                    if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        mAudioRecord.stop();
                    }
                } catch (Exception e) {
                    log(audioTag() + "stop capture error: " + e);
                } finally {
                    try {
                        mAudioRecord.release();
                    } catch (RuntimeException e) {
                        log(audioTag() + "release capture error: " + e);
                    }
                    mAudioRecord = null;
                }
            }
            if (mTtsSessionOpen) {
                if (usbOk) {
                    enqueueAudioPacket(TTS, MSG_NAVI_TTS_END, new byte[0], 0);
                    log("[TTS-AUDIO] NAVI_TTS_END");
                } else {
                    log("[TTS-AUDIO] session closed: USB disconnected");
                }
                mTtsSessionOpen = false;
            } else if (wasStarted && usbOk) {
                enqueueMediaPacket(Utils.MSG_MEDIA_STOP, new byte[0], 0);
            }
            mTtsPacketBytes = 0;
            mTtsConverter = null;
            if (wasStarted) log(audioTag() + "capture stopped");
        }

        private void runMediaTestTone(int sampleRate) {
            if (!usbOk || mOutputStream == null || mCancelAudioTest || mReleased) {
                mAudioTestToneActive = false;
                return;
            }

            boolean restartCapture = mAudioStart;
            mAudioTestToneActive = true;
            stopCapture();

            try {
                sendMediaInitPacket(sampleRate);

                final int frequency = 1000;
                final int framesPerPacket = sampleRate / 50; // exactly 20 ms
                final int packetCount = 100;                // exactly 2 seconds
                final int amplitude = 6500;

                long sampleIndex = 0;
                log(
                        "[AUDIO-TEST] MEDIA START 1kHz "
                                + sampleRate
                                + "Hz stereo PCM16 duration=2s"
                );

                for (int packet = 0; packet < packetCount && usbOk && !mReleased && !mCancelAudioTest; packet++) {
                    byte[] pcm = new byte[framesPerPacket * 4];
                    int offset = 0;

                    for (int frame = 0; frame < framesPerPacket; frame++) {
                        double phase = 2.0 * Math.PI * frequency
                                * sampleIndex / sampleRate;
                        short value = (short) Math.round(
                                Math.sin(phase) * amplitude
                        );
                        sampleIndex++;

                        pcm[offset++] = (byte) (value & 0xFF);
                        pcm[offset++] = (byte) ((value >> 8) & 0xFF);
                        pcm[offset++] = (byte) (value & 0xFF);
                        pcm[offset++] = (byte) ((value >> 8) & 0xFF);
                    }

                    enqueueMediaPacket(MSG_MEDIA_DATA, pcm, pcm.length);
                    SystemClock.sleep(20);
                }

                log("[AUDIO-TEST] MEDIA END " + sampleRate + "Hz");
            } catch (Exception e) {
                log("[AUDIO-TEST] MEDIA failed: " + e);
            } finally {
                enqueueMediaPacket(Utils.MSG_MEDIA_STOP, new byte[0], 0);
                mAudioTestToneActive = false;
                if (restartCapture && isUsbAudioEnabled() && usbOk && !mCancelAudioTest) {
                    sendEmptyMessage(AUDIO_START);
                }
            }
        }

        private void runTtsTestTone(int sampleRate, int channels) {
            if (!usbOk || mOutputStream == null || mCancelAudioTest || mReleased) {
                mAudioTestToneActive = false;
                return;
            }

            boolean restartCapture = mAudioStart;
            mAudioTestToneActive = true;
            stopCapture();

            try {
                final int frequency = 1000;
                final int framesPerPacket = sampleRate / 50; // 20 ms
                final int packetCount = 100;
                final int amplitude = 6500;

                sendTtsInitPacket(sampleRate, channels);

                long sampleIndex = 0;
                log("[AUDIO-TEST] TTS START 1kHz " + sampleRate + "Hz channels="
                        + channels + " PCM16 duration=2s");

                for (int packet = 0; packet < packetCount && usbOk && !mReleased && !mCancelAudioTest; packet++) {
                    byte[] pcm = new byte[framesPerPacket * channels * 2];
                    int offset = 0;

                    for (int frame = 0; frame < framesPerPacket; frame++) {
                        double phase = 2.0 * Math.PI * frequency
                                * sampleIndex / sampleRate;
                        short value = (short) Math.round(
                                Math.sin(phase) * amplitude
                        );
                        sampleIndex++;

                        for (int channel = 0; channel < channels; channel++) {
                            pcm[offset++] = (byte) (value & 0xFF);
                            pcm[offset++] = (byte) ((value >> 8) & 0xFF);
                        }
                    }

                    enqueueAudioPacket(
                            TTS,
                            MSG_NAVI_TTS_DATA,
                            pcm,
                            pcm.length
                    );
                    SystemClock.sleep(20);
                }

            } catch (Exception e) {
                log("[AUDIO-TEST] TTS failed: " + e);
            } finally {
                enqueueAudioPacket(TTS, MSG_NAVI_TTS_END, new byte[0], 0);
                log("[AUDIO-TEST] TTS END");
                mAudioTestToneActive = false;
                if (restartCapture && isUsbAudioEnabled() && usbOk && !mCancelAudioTest) {
                    sendEmptyMessage(AUDIO_START);
                }
            }
        }
    }

    private synchronized void sendTtsInitPacket(int sampleRate, int channels) {
        mTtsWireChannels = channels;
        CarlifeTTSInitProto.CarlifeTTSInit init = CarlifeTTSInitProto.CarlifeTTSInit.newBuilder()
                .setSampleRate(sampleRate)
                .setChannelConfig(channels)
                .setSampleFormat(16)
                .build();
        enqueueAudioPacket(TTS, MSG_NAVI_TTS_INIT, init.toByteArray(), init.getSerializedSize());
        log("[TTS-AUDIO] NAVI_TTS_INIT channel=4 rate=" + sampleRate + " channels=" + channels);
    }

    private void sendMediaInitPacket() {
        sendMediaInitPacket(48000);
    }

    private void sendMediaInitPacket(int sampleRate) {
        CarlifeMusicInitProto.CarlifeMusicInit.Builder builder =
                CarlifeMusicInitProto.CarlifeMusicInit.newBuilder();
        builder.setSampleRate(sampleRate);
        builder.setChannelConfig(2);
        builder.setSampleFormat(16);
        byte[] init = builder.build().toByteArray();
        enqueueMediaPacket(MSG_MEDIA_INIT, init, init.length);
        log(
                "[AUDIO] MEDIA_INIT sent "
                        + sampleRate
                        + "Hz channelConfig=2 sampleFormat=16"
        );
    }

    private void enqueueMediaPacket(int serviceType, byte[] data, int length) {
        enqueueAudioPacket(MEDIA, serviceType, data, length);
    }

    private boolean enqueueAudioPacket(
            byte channel,
            int serviceType,
            byte[] data,
            int length
    ) {
        return enqueueAudioPacket(channel, serviceType, data, length, null);
    }

    private synchronized boolean enqueueAudioPacket(byte channel, int serviceType, byte[] data,
                                       int length, AudioWriteStats stats) {
        if (!usbOk || mOutputStream == null) return false;
        boolean isData = serviceType == MSG_MEDIA_DATA || serviceType == MSG_NAVI_TTS_DATA;
        if (isData && mPendingAudioPackets.get() >= 8) return false;
        // Apply only to PCM DATA, never protobuf INIT or END messages.
        if (isData) {
            data = mPcmVolume.apply(data, length, channel == TTS ? mTtsWireChannels : 2,
                    mCarAudioVolumePercent / 100.0);
        }
        byte[] carLifeMsg = exportVideoMsg(serviceType, data, length);
        byte[] headmsg = new byte[8];
        headmsg[3] = channel;
        intToBytes2(carLifeMsg.length, headmsg, 4);
        CarMsg carMsg = new CarMsg(headmsg, carLifeMsg);
        carMsg.audioStats = stats;
        mPendingAudioPackets.incrementAndGet();
        if (!mUsbWriteHandler.sendMessage(mUsbWriteHandler.obtainMessage(MSG_WRITE_AUDIO, carMsg))) {
            mPendingAudioPackets.updateAndGet(count -> Math.max(0, count - 1));
            return false;
        }
        return true;
    }

    private void startUsbTransferThread() {
        mUsbReadThread = new HandlerThread("read");
        mUsbReadThread.start();
        mUsbReadHandler = new Handler(mUsbReadThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 0: {
//                        mMainHandler.postDelayed(runnable_toast,300000 - 3000);
//                        mMainHandler.postDelayed(runnable,300000);
                        final int generation = msg.arg1;
                        final FileInputStream input = mInputStream;
                        while (usbOk && !mReleased && generation == mUsbGeneration) {
                            try {
                                byte[] data = new byte[8];
                                CarLifeFrameReader.readFully(input, data);
                                int len = data.length;

                                if (len == 8) {
                                    int msg_type = data[3];
                                    int msgLen = bytesToInt2(data, 4);
                                    CarLifeFrameReader.checkPayloadLength(msgLen);
                                    byte[] msgdata = new byte[msgLen];
                                    CarLifeFrameReader.readFully(input, msgdata);
                                    if (generation != mUsbGeneration || !usbOk) break;
                                    len = msgdata.length;
                                    int carmsgLen = bytesToShort2(msgdata, 0) & 0xffff;
                                    if (carmsgLen > msgdata.length - 8) {
                                        throw new IOException("invalid CarLife command payload length " + carmsgLen);
                                    }
                                    int type = bytesToInt2(msgdata, 4);
                                    // A parsed MOVE log below retains every delta/timestamp;
                                    // avoid five redundant dumps and UI notifications per MOVE.
                                    if (type != MSG_TOUCH_PAD_MOVE) {
                                        log("msg_type = " + msg_type + ", read data = " + Arrays.toString(data));
                                        log("msgLen = " + msgLen);
                                        log("read data = " + Arrays.toString(msgdata));
                                        log("read msg data = " + len + " msgLen " + msgLen);
                                        log("read carmsgLen data = " + carmsgLen + " type " + type);
                                    }
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
                                                                .setValue(isUsbAudioEnabled() ? 0 : 1)
                                                                .build();
                                                CarlifeFeatureConfigProto.CarlifeFeatureConfig mediaSampleRate =
                                                        CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                                                                .setKey("MEDIA_SAMPLE_RATE")
                                                                .setValue(0)
                                                                .build();
                                                CarlifeFeatureConfigProto.CarlifeFeatureConfig contentEncryption =
                                                        CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                                                                .setKey("CONTENT_ENCRYPTION")
                                                                .setValue(0)
                                                                .build();
                                                CarlifeFeatureConfigListProto.CarlifeFeatureConfigList featureRequest =
                                                        CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.newBuilder()
                                                                .setCnt(4)
                                                                .addFeatureConfig(focusUi)
                                                                .addFeatureConfig(audioMode)
                                                                .addFeatureConfig(mediaSampleRate)
                                                                .addFeatureConfig(contentEncryption)
                                                                .build();
                                                log(
                                                        "[FEATURE] request FOCUS_UI=1"
                                                                + " AUDIO_TRANSMISSION_MODE="
                                                                + (isUsbAudioEnabled() ? 0 : 1)
                                                                + " MEDIA_SAMPLE_RATE=0"
                                                                + " CONTENT_ENCRYPTION=0"
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
                                            case MSG_CMD_MODULE_CONTROL: {
                                                try {
                                                    CarlifeModuleStatusProto.CarlifeModuleStatus control =
                                                            CarlifeModuleStatusProto.CarlifeModuleStatus.parseFrom(msgdata);
                                                    int moduleId = control.getModuleID();
                                                    int statusId = control.getStatusID();
                                                    log(
                                                            "[MODULE] HU control module="
                                                                    + moduleId
                                                                    + " status="
                                                                    + statusId
                                                    );
                                                    notifyModuleControl(moduleId, statusId);

                                                } catch (Exception e) {
                                                    log("[MODULE] HU control parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_CMD_HU_FEATURE_CONFIG_RESPONSE: {
                                                try {
                                                    CarlifeFeatureConfigListProto.CarlifeFeatureConfigList response =
                                                            CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.parseFrom(msgdata);
                                                    log("[FEATURE] HU response cnt=" + response.getCnt());
                                                    mHuAudioTransmissionMode = null;
                                                    mHuMediaSampleRate = null;
                                                    mHuContentEncryption = null;
                                                    for (CarlifeFeatureConfigProto.CarlifeFeatureConfig feature
                                                            : response.getFeatureConfigList()) {
                                                        log(
                                                                "[FEATURE] HU "
                                                                        + feature.getKey()
                                                                        + "="
                                                                        + feature.getValue()
                                                        );
                                                        if ("AUDIO_TRANSMISSION_MODE".equals(feature.getKey())) {
                                                            mHuAudioTransmissionMode = feature.getValue();
                                                        } else if ("MEDIA_SAMPLE_RATE".equals(feature.getKey())) {
                                                            mHuMediaSampleRate = feature.getValue();
                                                        } else if ("CONTENT_ENCRYPTION".equals(feature.getKey())) {
                                                            mHuContentEncryption = feature.getValue();
                                                        }
                                                    }
                                                    notifyAudioFeatureStatus();
                                                } catch (Exception e) {
                                                    log("[FEATURE] response parse error: " + e);
                                                }
                                            }
                                            break;
                                            case MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE: {
                                                try {
                                                    CarlifeHuRsaPublicKeyResponseProto.CarlifeHuRsaPublicKeyResponse response =
                                                            CarlifeHuRsaPublicKeyResponseProto.CarlifeHuRsaPublicKeyResponse.parseFrom(msgdata);
                                                    String key = response.getRsaPublicKey();
                                                    mEncryptionProbeGeneration++;
                                                    log("[ENCRYPT] HU RSA public key response len=" + key.length());
                                                    notifyEncryptionProbe(1, key.length());
                                                } catch (Exception e) {
                                                    log("[ENCRYPT] HU RSA response parse error: " + e);
                                                    notifyEncryptionProbe(-2, 0);
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
                if (mReleased || mInfoListener == null) return;
                                                            mInfoListener.onVISID(statisticsInfo.getCuid());
                                                        }
                                                    });
                                                } catch (InvalidProtocolBufferException e) {
                                                    e.printStackTrace();
                                                }
                                                log("[SESSION] STATISTIC_INFO -> FOREGROUND -> SCREEN_ON -> AUTHEN_RESULT");

                                                mUsbWriteHandler.obtainMessage(
                                                        MSG_CMD_FOREGROUND,
                                                        exportCMDMsg(MSG_CMD_FOREGROUND, null)
                                                ).sendToTarget();

                                                mUsbWriteHandler.obtainMessage(
                                                        MSG_CMD_SCREEN_ON,
                                                        exportCMDMsg(MSG_CMD_SCREEN_ON, null)
                                                ).sendToTarget();

                                                CarlifeAuthenResultProto.CarlifeAuthenResult.Builder builder =
                                                        CarlifeAuthenResultProto.CarlifeAuthenResult.newBuilder();
                                                builder.setResult(true);
                                                mUsbWriteHandler.obtainMessage(
                                                        MSG_CMD_MD_AUTHEN_RESULT,
                                                        exportCMDMsg(
                                                                MSG_CMD_MD_AUTHEN_RESULT,
                                                                builder.build().toByteArray()
                                                        )
                                                ).sendToTarget();
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
                                                    if (ForgroundService.mService != null) {
                                                        ForgroundService.mService.onCarPadMove(dx, dy);
                                                    }
                                                    log(
                                                            "[PAD] MOVE dx=" + dx
                                                                    + " dy=" + dy
                                                                    + " ts=" + move.getTimestamp()
                                                    );
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

                                synchronized (MsgProcess.this) {
                                    if (generation == mUsbGeneration) resetUsb();
                                }
                                break;
                            }

                            //SystemClock.sleep(10);
                        }
                    }
                }
            }
        };

        mUsbWriteThread = new HandlerThread("write");
        mUsbWriteThread.start();
        mUsbWriteHandler = new Handler(mUsbWriteThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);

                final int generation = mUsbGeneration;
                final FileOutputStream output = mOutputStream;
                try {
                    if (!usbOk || mReleased || output == null) return;
                    switch (msg.what) {
                        case MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS:
                        case MSG_CMD_MD_INFO:
                        case MSG_CMD_FOREGROUND:
                        case MSG_CMD_SCREEN_ON:
                        case MSG_CMD_MD_AUTHEN_RESULT:
                        case MSG_CMD_MD_FEATURE_CONFIG_REQUEST:
                        case MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST: {
                            byte[] carLifeMsg = (byte[]) msg.obj;
                            byte[] headmsg = new byte[8];
                            headmsg[3] = CMD;
                            intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                            output.write(headmsg);
                            log("msg=" + msg.what + "write data =" + Arrays.toString(headmsg));
                            output.write(carLifeMsg);
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
                                output.write(headmsg);
                                log("msg=" + msg.what + "write data =" + Arrays.toString(headmsg));
                                output.write(carLifeMsg);
                                log("msg=" + msg.what + "write data =" + Arrays.toString(carLifeMsg));
                                log("write data ok");
                            }

                            {
                                byte[] carLifeMsg = exportCMDMsg(MSG_CMD_FOREGROUND, null);
                                byte[] headmsg = new byte[8];
                                headmsg[3] = CMD;
                                intToBytes2(carLifeMsg.length, headmsg, 4);//carlifemsg len
                                output.write(headmsg);
                                log("msg=" + MSG_CMD_FOREGROUND + "write data =" + Arrays.toString(headmsg));
                                output.write(carLifeMsg);
                                log("msg=" + MSG_CMD_FOREGROUND + "write data =" + Arrays.toString(carLifeMsg));
                                log("write data ok");
                            }


                        }
                        break;
                        case MSG_CMD_VIDEO_ENCODER_START: {
                            log("now start MSG_CMD_VIDEO_ENCODER_START");

                            // Audio INIT belongs to capture startup, after projection permission.
                            // TTS compatibility never initializes the MEDIA channel.

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
                            output.write(carMsg.head);
                            output.write(carMsg.msg);
                            if (carMsg.audioStats != null) {
                                carMsg.audioStats.bytes.addAndGet(carMsg.msg.length - 12);
                                carMsg.audioStats.packets.incrementAndGet();
                            }
                        }
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    synchronized (MsgProcess.this) {
                        if (generation == mUsbGeneration) resetUsb();
                    }
                } finally {
                    if (msg.what == MSG_WRITE_AUDIO) {
                        synchronized (MsgProcess.this) {
                            if (generation == mUsbGeneration) {
                                mPendingAudioPackets.updateAndGet(count -> Math.max(0, count - 1));
                            }
                        }
                    }
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

        void onAudioFeatures(
                Integer audioTransmissionMode,
                Integer mediaSampleRate,
                Integer contentEncryption
        );

        void onEncryptionProbe(int state, int keyLength);

        void onModuleControl(int moduleId, int statusId);
    }

    private static final class AudioWriteStats {
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong packets = new AtomicLong();
    }

    static class CarMsg {
        AudioWriteStats audioStats;
        byte[] head;
        byte[] msg;

        CarMsg(byte[] b1, byte[] b3) {
            head = b1;
            msg = b3;
        }
    }
}
