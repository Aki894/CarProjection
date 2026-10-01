package com.projection.car;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** InputStream reads can split either the outer header or its payload. */
final class CarLifeFrameReader {
    private CarLifeFrameReader() {}

    static void readFully(InputStream input, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int count = input.read(data, offset, data.length - offset);
            if (count < 0) throw new EOFException("CarLife frame ended at " + offset + "/" + data.length);
            if (count == 0) {
                int value = input.read();
                if (value < 0) throw new EOFException("CarLife frame ended at " + offset + "/" + data.length);
                data[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
    }

    static void checkPayloadLength(int length) throws IOException {
        // Incoming command/touch frames need an 8-byte inner header.
        if (length < 8 || length > 1024 * 1024) {
            throw new IOException("invalid CarLife frame length " + length);
        }
    }
}
