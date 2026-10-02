package com.projection.car;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.DataInputStream;
import java.io.IOException;

/** One authenticated local video producer; frame payloads travel through a bounded pipe. */
final class CarPlayVideoBridge {
    interface Sink {
        int[] status();
        boolean config(long token, byte[] parameterSets, int width, int height);
        void frame(long token, byte[] data);
        void ended(long token);
    }
    private static volatile Sink sink;
    private static Endpoint endpoint;
    private static long sequence;
    static void attach(Sink next) { invalidate(); sink = next; }
    static void detach(Sink expected) { if (sink == expected) { invalidate(); sink = null; } }
    static int[] status() { Sink current = sink; return current == null ? new int[5] : current.status(); }
    static void invalidate() { Endpoint old; synchronized (CarPlayVideoBridge.class) { old = endpoint; }
        if (old != null) old.close(); }
    static ParcelFileDescriptor open(int uid, IBinder owner, int width, int height) throws IOException {
        Sink current = sink; int[] state = status();
        if (owner == null || state[0] == 0 || state[1] != width || state[2] != height || current == null) return null;
        synchronized (CarPlayVideoBridge.class) {
            if (endpoint != null) return null;
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Endpoint next = new Endpoint(uid, owner, pipe[0], current, ++sequence, width, height);
            try { owner.linkToDeath(next, 0); }
            catch (RemoteException e) { pipe[0].close(); pipe[1].close(); return null; }
            endpoint = next;
            Thread reader = new Thread(next::read, "carplay-h264-pipe"); reader.setDaemon(true); reader.start();
            AppLogger.append("[BRIDGE] video pipe opened target=" + width + "x" + height);
            return pipe[1];
        }
    }
    static void close(int uid, IBinder owner) {
        Endpoint old; synchronized (CarPlayVideoBridge.class) { old = endpoint; }
        if (old != null && old.uid == uid && old.owner.equals(owner)) old.close();
    }
    private static final class Endpoint implements IBinder.DeathRecipient {
        final int uid, width, height; final IBinder owner; final ParcelFileDescriptor input;
        final Sink target; final long token; volatile boolean closed;
        Endpoint(int uid, IBinder owner, ParcelFileDescriptor input, Sink target, long token, int width, int height) {
            this.uid=uid; this.owner=owner; this.input=input; this.target=target; this.token=token;
            this.width=width; this.height=height;
        }
        void read() {
            boolean configured = false;
            try (DataInputStream in = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(input))) {
                while (!closed) {
                    int kind = in.readInt();
                    if (kind != 1 && kind != 2) throw new IOException("Unknown video record");
                    byte[] data = H264BridgeFrames.readRecord(in, kind == 1 ? H264BridgeFrames.MAX_CONFIG : H264BridgeFrames.MAX_FRAME);
                    if (closed) break;
                    if (kind == 1) {
                        int[] actual = H264BridgeFrames.size(data);
                        if (actual[0] != width || actual[1] != height || !target.config(token, data, width, height))
                            throw new IOException("SPS does not match negotiated car display");
                        configured = true;
                        AppLogger.append("[BRIDGE] video SPS/PPS accepted=" + actual[0] + "x" + actual[1]);
                    } else {
                        if (!configured) throw new IOException("Video frame before config");
                        H264BridgeFrames.types(data); // Reject malformed units before the USB writer.
                        target.frame(token, data);
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                if (!closed) AppLogger.append("[BRIDGE] video pipe ended: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally { close(); }
        }
        void close() {
            synchronized (CarPlayVideoBridge.class) {
                if (closed) return; closed=true;
                if (endpoint == this) endpoint=null;
                try { owner.unlinkToDeath(this, 0); } catch (RuntimeException ignored) {}
                try { input.close(); } catch (IOException ignored) {}
            }
            target.ended(token);
        }
        @Override public void binderDied() { close(); }
    }
}
