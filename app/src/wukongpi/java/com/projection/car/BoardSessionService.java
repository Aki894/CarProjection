package com.projection.car;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;

/** Owns CarLife transport from cold boot without an Activity, recording or accessibility. */
public final class BoardSessionService extends Service {
    private static final String TOKEN="com.projection.car.Board.v1";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MsgProcess process;
    private ParcelFileDescriptor descriptor;
    private UsbAccessory current;
    private boolean requested;
    private String state="waiting",error="";
    private int width,height;
    private PowerManager.WakeLock wake;
    private final Runnable poll=new Runnable() { public void run() {
        if(requested) discover();
        if(wake!=null) { if(process!=null && process.isUsbConnected()) wake.acquire(30000L); else if(wake.isHeld()) wake.release(); }
        handler.postDelayed(this,2000);
    }};
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("board_car","CarLife",NotificationManager.IMPORTANCE_LOW));
        startForeground(101,new Notification.Builder(this,"board_car").setSmallIcon(android.R.drawable.stat_sys_data_usb)
            .setContentTitle("WuKong CarLife").setContentText("Background car bridge").setOngoing(true).build());
        requested=getSharedPreferences("set",0).getBoolean("board_requested",true);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WuKong:CarLife");wake.setReferenceCounted(false);
        registerReceiver(detach,new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED));
        handler.post(poll);
    }
    private final BroadcastReceiver detach=new BroadcastReceiver() { public void onReceive(Context c,Intent i) {
        UsbAccessory a=i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if(a!=null && a.equals(current)) close("accessory detached");
    }};
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null) action(i.getStringExtra("command"));return START_STICKY;
    }
    private void action(String command) {
        if(command==null || "status".equals(command)) return;
        if(!"start".equals(command) && !"stop".equals(command) && !"reconnect".equals(command)) throw new IllegalArgumentException("Unknown command");
        requested=!"stop".equals(command);
        getSharedPreferences("set",0).edit().putBoolean("board_requested",requested).apply();
        if(!"start".equals(command)) close(command);
        if(requested) discover();else state="idle";
    }
    private void discover() {
        if(process!=null && process.isUsbConnected()) return;
        UsbManager usb=getSystemService(UsbManager.class);
        UsbAccessory[] all=usb.getAccessoryList();
        if(all==null) {state="waiting";return;}
        for(UsbAccessory a:all) if("Baidu".equals(a.getManufacturer()) && "CarLife".equals(a.getModel())) {
            if(!usb.hasPermission(a)) {state="permission missing";error="Install board provisioning; no permission dialog is launched";return;}
            close("reopen");
            try {
                getSharedPreferences("set",0).edit().putBoolean("direct_carplay_video",true)
                    .putBoolean("direct_carplay_audio",true).putBoolean("direct_carplay_input",true).commit();
                descriptor=usb.openAccessory(a);
                if(descriptor==null) throw new IOException("openAccessory returned null");
                current=a;
                process=new MsgProcess(this,30,4_000_000,new MsgProcess.InfoListener() {
                    public void onTransportClosed(String reason) {close(reason);}
                    public void onProjectionStopped() {state="video stopped";}
                    public void onVISSize(int x,int y) {width=x;height=y;}
                    public void onVISID(String id) {}
                    public void onAudioFeatures(Integer mode,Integer rate,Integer encryption) {}
                    public void onEncryptionProbe(int result,int length) {}
                    public void onModuleControl(int module,int status) {}
                });
                FileDescriptor fd=descriptor.getFileDescriptor();
                process.startProjection(new FileInputStream(fd),new FileOutputStream(fd));
                state="connected";error="";
            } catch(Exception e) {close(e.toString());state="error";}
            return;
        }
    }
    private void close(String reason) {
        MsgProcess old=process;process=null;current=null;
        if(old!=null) {old.resetUsb(reason);old.release();}
        if(descriptor!=null) try {descriptor.close();}catch(IOException ignored){} descriptor=null;
        state="waiting";error=reason;
    }
    private JSONObject status() {
        try {return new JSONObject().put("state",state).put("error",error).put("requested",requested)
            .put("usbConnected",process!=null && process.isUsbConnected()).put("width",width).put("height",height)
            .put("version",BuildConfig.VERSION_NAME).put("settings",settings());} catch(Exception e) {throw new IllegalStateException(e);}
    }
    private JSONObject settings() throws Exception {
        android.content.SharedPreferences p=getSharedPreferences("set",0);
        return new JSONObject().put("usbMedia",p.getBoolean("carlife_media_audio",true))
            .put("ttsCompatibility",p.getBoolean("tts_audio_compatibility",false)).put("ttsRate",p.getInt("tts_sample_rate",48000));
    }
    private void configure(JSONObject j) throws Exception {
        java.util.Iterator<String> keys=j.keys();while(keys.hasNext()) if(!java.util.Arrays.asList("usbMedia","ttsCompatibility","ttsRate").contains(keys.next())) throw new IllegalArgumentException("Unknown car setting");
        int rate=j.getInt("ttsRate");boolean allowed=false;for(int r:TtsPcmConverter.RATES) allowed|=rate==r;
        if(!allowed) throw new IllegalArgumentException("Unsupported TTS sample rate");
        if(!getSharedPreferences("set",0).edit().putBoolean("carlife_media_audio",j.getBoolean("usbMedia"))
            .putBoolean("tts_audio_compatibility",j.getBoolean("ttsCompatibility")).putInt("tts_sample_rate",rate).commit()) throw new IOException("Save failed");
        action("reconnect");
    }
    private void enforceCaller() {
        int uid=Binder.getCallingUid();
        if(uid==0 || uid==2000 || uid==android.os.Process.myUid()) return;
        String[] pkgs=getPackageManager().getPackagesForUid(uid);
        if(pkgs!=null) for(String p:pkgs) if("com.shihab.diplay.hudtest".equals(p)
            && getPackageManager().checkSignatures(getPackageName(),p)==PackageManager.SIGNATURE_MATCH) return;
        throw new SecurityException("Signed bridge client required");
    }
    private final Binder binder=new Binder() {
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION) {reply.writeString(TOKEN);return true;}
            if(code!=FIRST_CALL_TRANSACTION) return super.onTransact(code,data,reply,flags);
            enforceCaller();data.enforceInterface(TOKEN);
            String command=data.readString(),json=data.readString();
            if(json==null || json.length()>8192) throw new IllegalArgumentException("Bad request");
            java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
            final String[] result=new String[1];final Exception[] failure=new Exception[1];
            handler.post(()->{try {if("config".equals(command))configure(new JSONObject(json));else action(command);result[0]=status().toString();}
                catch(Exception e){failure[0]=e;}finally{done.countDown();}});
            try {if(!done.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Control timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            if(failure[0]!=null) throw new IllegalArgumentException(failure[0].getMessage());
            reply.writeNoException();reply.writeString(result[0]);return true;
        }
    };
    @Override public IBinder onBind(Intent i) {return binder;}
    @Override public void onDestroy() {handler.removeCallbacksAndMessages(null);unregisterReceiver(detach);close("service destroyed");if(wake.isHeld())wake.release();super.onDestroy();}
}
