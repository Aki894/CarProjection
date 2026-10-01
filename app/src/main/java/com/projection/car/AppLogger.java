package com.projection.car;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

public final class AppLogger {
    public interface Listener { void onLogUpdated(); }

    private static final int DISPLAY_LINES = 300;
    private static final int HISTORY_LINES = 5000;
    private static final int DIAGNOSTIC_LINES = 1000;
    private static final ArrayDeque<Entry> LINES = new ArrayDeque<>();
    private static final ArrayDeque<Entry> DIAGNOSTICS = new ArrayDeque<>();
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static long sequence;
    private static volatile Listener listener;

    private static final class Entry {
        final long sequence;
        final String line;
        Entry(long sequence, String line) { this.sequence = sequence; this.line = line; }
    }

    private AppLogger() {}

    public static void append(String message) {
        synchronized (LINES) {
            // SimpleDateFormat is shared by USB, audio, encoder and UI threads.
            Entry entry = new Entry(sequence++, FORMAT.format(new Date()) + "  " + message);
            if (LINES.size() >= HISTORY_LINES) LINES.removeFirst();
            LINES.addLast(entry);
            if (isDiagnostic(message)) {
                if (DIAGNOSTICS.size() >= DIAGNOSTIC_LINES) DIAGNOSTICS.removeFirst();
                DIAGNOSTICS.addLast(entry);
            }
        }
        Listener current = listener;
        if (current != null) current.onLogUpdated();
    }

    public static void setListener(Listener value) {
        listener = value;
        if (value != null) value.onLogUpdated();
    }

    public static void clearListener(Listener value) {
        if (listener == value) listener = null;
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
            DIAGNOSTICS.clear();
        }
        Listener current = listener;
        if (current != null) current.onLogUpdated();
    }

    public static List<String> snapshot(boolean inputOnly) {
        ArrayDeque<String> visible = new ArrayDeque<>();
        synchronized (LINES) {
            for (Entry entry : LINES) {
                String line = entry.line;
                if (!inputOnly || line.contains("[TOUCH]") || line.contains("[KEY]")
                        || line.contains("[PAD]") || line.contains("[PAD-GESTURE]")
                        || line.contains("[FOCUS]")) {
                    if (visible.size() >= DISPLAY_LINES) visible.removeFirst();
                    visible.addLast(line);
                }
            }
        }
        return new ArrayList<>(visible);
    }

    public static List<String> exportSnapshot() {
        TreeMap<Long, String> ordered = new TreeMap<>();
        synchronized (LINES) {
            // Merge by sequence so older diagnostic lines survive touch traffic,
            // without duplicates or incorrect ordering across midnight.
            for (Entry entry : DIAGNOSTICS) ordered.put(entry.sequence, entry.line);
            for (Entry entry : LINES) ordered.put(entry.sequence, entry.line);
        }
        return new ArrayList<>(ordered.values());
    }

    private static boolean isDiagnostic(String message) {
        return message.startsWith("[AUDIO") || message.startsWith("[TTS-AUDIO]")
                || message.startsWith("[FEATURE]") || message.startsWith("[ENCRYPT]")
                || message.startsWith("[MODULE]") || message.startsWith("[BRIGHTNESS]")
                || message.startsWith("[SESSION]") || message.startsWith("MediaProjection")
                || message.equals("startProjection") || message.equals("resetUsb");
    }
}
