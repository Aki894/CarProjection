package com.projection.car;

import java.io.BufferedInputStream;
import java.io.InputStream;

/** Read entire AOA transfers before parsing small CarLife headers. */
final class AccessoryInputStream extends BufferedInputStream {
    static final int USB_BUFFER_SIZE = 16384;
    AccessoryInputStream(InputStream input) { super(input, USB_BUFFER_SIZE); }
}
