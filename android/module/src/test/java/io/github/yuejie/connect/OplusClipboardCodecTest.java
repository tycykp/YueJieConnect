package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OplusClipboardCodecTest {
    @Test public void realUtf8PayloadRoundTripsWithoutLosingUnicode() {
        String text = "一加→小米\n🙂\u0000原文";
        assertEquals(text, OplusClipboardCodec.decode(0, 0, OplusClipboardCodec.encode(text)));
    }
    @Test public void parcelAndUriPayloadsMustNotBeInterpretedAsText() {
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(1, 0, new byte[]{65}));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(0, 1, new byte[]{65}));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(2, 0, new byte[]{65}));
    }
    @Test public void malformedAndTruncatedUtf8AreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(0, 0, new byte[]{(byte)0xe4, (byte)0xb8}));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(0, 0, new byte[]{(byte)0xc0, (byte)0xaf}));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.encode("\ud800"));
    }
    @Test public void byteLimitIsEnforcedForMultibyteText() {
        assertEquals(4096, OplusClipboardCodec.encode("a".repeat(4096)).length);
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.encode("中".repeat(1366)));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.decode(0, 0, new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> OplusClipboardCodec.encode(""));
    }
}
