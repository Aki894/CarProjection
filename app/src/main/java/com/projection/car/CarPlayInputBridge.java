package com.projection.car;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

/** One leased DiPlay input consumer. USB reader never waits for a remote HID sender. */
final class CarPlayInputBridge {
    static final CarPlayInputQueue queue = new CarPlayInputQueue();
    private static final CarPlayPadInput pad = new CarPlayPadInput(queue);
    private static IBinder owner;
    private static int uid;
    private static long lastPoll;
    private static boolean active;
    private static IBinder.DeathRecipient death;
    static synchronized boolean ready() { return owner != null && active && SystemClock.elapsedRealtime() - lastPoll < 1000; }
    static synchronized int[][] poll(int caller, IBinder token, boolean live) throws RemoteException {
        if (token == null) throw new IllegalArgumentException("input owner");
        if (owner != null && (!owner.equals(token) || uid != caller)) {
            if (SystemClock.elapsedRealtime() - lastPoll < 1000) return new int[0][];
            clearOwner();
        }
        if (owner == null) {
            owner = token; uid = caller;
            death = () -> { synchronized (CarPlayInputBridge.class) { if (owner == token) clearOwner(); } };
            try { token.linkToDeath(death, 0); } catch (RemoteException e) { clearOwner(); throw e; }
            reset();
        }
        if (active != live || SystemClock.elapsedRealtime() - lastPoll >= 1000) reset();
        lastPoll = SystemClock.elapsedRealtime(); active = live;
        return queue.drain();
    }
    static synchronized void close(int caller, IBinder token) { if (uid == caller && owner != null && owner.equals(token)) clearOwner(); }
    private static void clearOwner() { if (owner != null && death != null) owner.unlinkToDeath(death, 0); owner = null; active = false; reset(); }
    static synchronized void reset() { pad.reset(); queue.reset(); }
    static synchronized void down() { pad.down(SystemClock.elapsedRealtime()); }
    static synchronized void move(int x, int y) { pad.move(x, y); }
    static synchronized void up() { pad.up(SystemClock.elapsedRealtime()); }
    static synchronized void key(int key) {
        if (key == Utils.KEYCODE_OK) pad.click(SystemClock.elapsedRealtime());
        else if (key == Utils.KEYCODE_BACK) queue.add(CarPlayInputQueue.KNOB, 4, 0, 0);
        else if (key == Utils.KEYCODE_HOME) queue.add(CarPlayInputQueue.KNOB, 2, 0, 0);
        else if (key == Utils.KEYCODE_SELECTOR_NEXT) queue.add(CarPlayInputQueue.WHEEL, 1, 0, 0);
        else if (key == Utils.KEYCODE_SELECTOR_PREVIOUS) queue.add(CarPlayInputQueue.WHEEL, -1, 0, 0);
        else if (key == Utils.KEYCODE_SEEK_ADD) queue.add(CarPlayInputQueue.MEDIA, 4, 0, 0);
        else if (key == Utils.KEYCODE_SEEK_SUB) queue.add(CarPlayInputQueue.MEDIA, 5, 0, 0);
    }
    static synchronized void touch(int action, int x, int y) { queue.add(CarPlayInputQueue.TOUCH, action, x, y); }
}
