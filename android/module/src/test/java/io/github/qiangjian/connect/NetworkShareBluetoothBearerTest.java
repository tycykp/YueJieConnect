package io.github.qiangjian.connect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure byte-pump checks. No claim about Android PFD, real Bluetooth, pairing or stable readiness. */
public class NetworkShareBluetoothBearerTest {
    @Test public void arbitraryBinaryIsUnchangedAcrossPartialReadsAndBoundedScratchSpace() {
        byte[] original = new byte[48_011];
        for (int i = 0; i < original.length; i++) original[i] = (byte) i;
        InputStream source = new ByteArrayInputStream(original) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                assertTrue(length <= 8192); return super.read(bytes, offset, Math.min(7, length));
            }
        };
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertEquals("bluetooth_eof", NetworkShareBluetoothBearer.pump(source, destination, () -> false));
        assertArrayEquals(original, destination.toByteArray());
    }
    @Test public void closeDuringBlockingReadDoesNotWriteTheLateBytes() {
        AtomicBoolean closed = new AtomicBoolean(); ByteArrayOutputStream destination = new ByteArrayOutputStream();
        InputStream source = new ByteArrayInputStream(new byte[]{1, 2}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int n = super.read(bytes, offset, length); closed.set(true); return n;
            }
        };
        assertEquals("bluetooth_closed", NetworkShareBluetoothBearer.pump(source, destination, closed::get));
        assertEquals(0, destination.size());
    }
    @Test public void closedPumpDoesNotReadOrWriteAndDoesNotConsumeCallerStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{4});
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertEquals("bluetooth_closed", NetworkShareBluetoothBearer.pump(source, destination, () -> true));
        assertEquals(1, source.available()); assertEquals(0, destination.size());
    }
    @Test public void ioFailureUsesFixedCodeAndCannotExposePayloadOrExceptionText() {
        OutputStream destination = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("private synthetic transport details"); }
        };
        assertEquals("bluetooth_io_failed", NetworkShareBluetoothBearer.pump(new ByteArrayInputStream(new byte[]{9}), destination, () -> false));
    }
}
