package io.github.yuejie.connect;

import android.os.SystemClock;

/** One clock for all locally imported Mirror formats, including two writes in the same ms. */
final class ClipboardNativeClock {
    private static long previous;
    static synchronized long next() {
        previous = Math.max(SystemClock.uptimeMillis() + 1, previous + 1);
        return previous;
    }
    private ClipboardNativeClock() {}
}
