package com.projection.car;
import android.content.*;
public final class BoardBootReceiver extends BroadcastReceiver {
    public void onReceive(Context c,Intent i) {if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()))c.startForegroundService(new Intent(c,BoardSessionService.class));}
}
