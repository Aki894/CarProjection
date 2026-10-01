package com.projection.car;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AppLogger {

    public interface Listener {
        void onLogUpdated();
    }

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Listener listener;

    private AppLogger() {
    }

    public static void append(String message) {
        String line = FORMAT.format(new Date()) + "  " + message;
        synchronized (LINES) {
            if (LINES.size() >= MAX_LINES) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
        }

        Listener current = listener;
        if (current != null) {
            current.onLogUpdated();
        }
    }

    public static void setListener(Listener value) {
        listener = value;
        if (value != null) {
            value.onLogUpdated();
        }
    }

    public static void clearListener(Listener value) {
        if (listener == value) {
            listener = null;
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        Listener current = listener;
        if (current != null) {
            current.onLogUpdated();
        }
    }

    public static List<String> snapshot(boolean inputOnly) {
        List<String> result = new ArrayList<>();
        synchronized (LINES) {
            for (String line : LINES) {
                if (!inputOnly
                        || line.contains("[TOUCH]")
                        || line.contains("[KEY]")
                        || line.contains("[PAD]")
                        || line.contains("[PAD-GESTURE]")) {
                    result.add(line);
                }
            }
        }
        return result;
    }
}
