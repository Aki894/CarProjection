package com.projection.car;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Process-local registry; never owns the USB connection or starts an Activity. */
final class CarPlayAudioBridge {
    interface Sink { boolean ready(); void start(); }
    static final BridgePcmMixer MIXER = new BridgePcmMixer();
    private static final List<Endpoint> endpoints = new ArrayList<>();
    private static volatile Sink sink;
    static synchronized void attach(Sink next) { invalidate(); sink = next; }
    static synchronized void detach(Sink expected) {
        if (sink == expected) { invalidate(); sink = null; }
    }
    static boolean ready() { Sink current = sink; return current != null && current.ready(); }
    static synchronized void invalidate() {
        for (Endpoint endpoint : new ArrayList<>(endpoints)) endpoint.close(true);
        MIXER.clear();
    }
    static synchronized ParcelFileDescriptor open(int uid, IBinder owner, String audioType) throws IOException {
        if (!ready() || owner == null || endpoints.size() >= 6) return null;
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        BridgePcmMixer.Stream stream = MIXER.add("media".equals(audioType));
        if (stream == null) { pipe[0].close(); pipe[1].close(); return null; }
        Endpoint endpoint = new Endpoint(uid, owner, pipe[0], stream);
        try {
            owner.linkToDeath(endpoint, 0);
        } catch (RemoteException e) {
            pipe[0].close(); pipe[1].close(); stream.end(true); return null;
        }
        endpoints.add(endpoint);
        Thread reader = new Thread(endpoint::read, "carplay-pcm-pipe");
        reader.setDaemon(true); reader.start();
        sink.start();
        AppLogger.append("[BRIDGE] stream opened type=" + audioType + " uid=" + uid);
        return pipe[1];
    }
    static synchronized void close(int uid, IBinder owner) {
        for (Endpoint endpoint : new ArrayList<>(endpoints))
            if (endpoint.uid == uid && endpoint.owner.equals(owner)) endpoint.close(true);
    }
    private static final class Endpoint implements IBinder.DeathRecipient {
        final int uid;
        final IBinder owner;
        final ParcelFileDescriptor input;
        final BridgePcmMixer.Stream stream;
        boolean closed;
        Endpoint(int uid, IBinder owner, ParcelFileDescriptor input, BridgePcmMixer.Stream stream) {
            this.uid = uid; this.owner = owner; this.input = input; this.stream = stream;
        }
        void read() {
            try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                byte[] data = new byte[8192];
                int length;
                while ((length = in.read(data)) >= 0) if (length > 0) stream.append(data, length);
            } catch (IOException ignored) {
                // Peer exit, disable, USB disconnect and service destruction all close the pipe.
            } finally { close(false); }
        }
        void close(boolean discard) {
            synchronized (CarPlayAudioBridge.class) {
                if (closed) return;
                closed = true;
                endpoints.remove(this);
                try { owner.unlinkToDeath(this, 0); } catch (RuntimeException ignored) {}
                stream.end(discard);
                try { input.close(); } catch (IOException ignored) {}
                AppLogger.append("[BRIDGE] stream closed droppedFrames=" + stream.droppedFrames);
            }
        }
        @Override public void binderDied() { close(true); }
    }
}
