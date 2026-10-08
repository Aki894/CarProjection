package com.projection.car;
import android.app.Activity;import android.content.Intent;import android.os.Bundle;
public final class BoardUsbActivity extends Activity {
    public void onCreate(Bundle state) {super.onCreate(state);startForegroundService(new Intent(this,BoardSessionService.class));finish();}
}
