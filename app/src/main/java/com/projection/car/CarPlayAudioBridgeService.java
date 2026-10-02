package com.projection.car;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import java.io.IOException;

/** Binder control + bounded PCM pipe; no TCP port and no PCM-sized Binder transactions. */
public final class CarPlayAudioBridgeService extends Service {
    private static final String DESCRIPTOR = "com.projection.car.PcmBridge.v1";
    private void enforceClient() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages)
            if (name.equals("com.shihab.diplay.hudtest") || name.equals("com.shihab.diplay")) return;
        throw new SecurityException("Only the configured DiPlay package may use this bridge");
    }
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code < FIRST_CALL_TRANSACTION || code > FIRST_CALL_TRANSACTION + 7)
                return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            enforceClient();
            if (code == FIRST_CALL_TRANSACTION + 6) {
                IBinder inputOwner = data.readStrongBinder();
                int[][] events = CarPlayInputBridge.poll(Binder.getCallingUid(), inputOwner, data.readInt() != 0);
                reply.writeNoException(); reply.writeInt(events.length);
                for (int[] event : events) for (int value : event) reply.writeInt(value);
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 7) {
                CarPlayInputBridge.close(Binder.getCallingUid(), data.readStrongBinder());
                reply.writeNoException(); return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 1) {
                reply.writeNoException(); reply.writeInt(CarPlayAudioBridge.ready() ? 1 : 0); return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 4) {
                reply.writeNoException();
                for (int value : CarPlayVideoBridge.status()) reply.writeInt(value);
                return true;
            }
            IBinder owner = data.readStrongBinder();
            if (code == FIRST_CALL_TRANSACTION + 5) {
                CarPlayVideoBridge.close(Binder.getCallingUid(), owner);
                reply.writeNoException(); return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 3) {
                int width = data.readInt(), height = data.readInt();
                try {
                    ParcelFileDescriptor fd = CarPlayVideoBridge.open(Binder.getCallingUid(), owner, width, height);
                    reply.writeNoException(); reply.writeInt(fd == null ? 0 : 1);
                    if (fd != null) fd.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                } catch (IOException e) { reply.writeException(new IllegalStateException("Video pipe unavailable", e)); }
                return true;
            }
            if (code == FIRST_CALL_TRANSACTION + 2) {
                CarPlayAudioBridge.close(Binder.getCallingUid(), owner);
                reply.writeNoException(); return true;
            }
            String type = data.readString();
            if (type == null || type.length() > 64) throw new IllegalArgumentException("audio type");
            try {
                ParcelFileDescriptor fd = CarPlayAudioBridge.open(Binder.getCallingUid(), owner, type);
                reply.writeNoException(); reply.writeInt(fd == null ? 0 : 1);
                if (fd != null) fd.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } catch (IOException e) { reply.writeException(new IllegalStateException("PCM pipe unavailable", e)); }
            return true;
        }
    };
    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() { CarPlayAudioBridge.invalidate(); CarPlayVideoBridge.invalidate(); super.onDestroy(); }
}
