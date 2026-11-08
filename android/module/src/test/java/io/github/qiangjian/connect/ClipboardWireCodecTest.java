package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ClipboardWireCodecTest {
    @Test public void maximumControlCharactersFitBinderAndNoiseWithoutTruncation() {
        String text = "\u0000".repeat(4096);
        String encoded = ClipboardWireCodec.encode(text);
        assertEquals(5464, encoded.length());
        // Worst permitted identifiers plus envelope overhead. Base64 contains no JSON escapes.
        String json = "{\"id\":\"" + "d".repeat(256) + "\",\"session_id\":\"" + "a".repeat(64)
                + "\",\"request_id\":\"" + "r".repeat(128) + "\",\"op\":\"clipboard_write\",\"text_b64\":\""
                + encoded + "\",\"deadline_ms\":30000}";
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length + 1024 < 8192);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length * 2 + 1024 < 16384);
        assertEquals(text, ClipboardWireCodec.decode(encoded));
    }
    @Test public void mixedUnicodeAndEveryControlCharacterRoundTrip() {
        StringBuilder value = new StringBuilder("原生→native🙂\"\\");
        for (int i = 0; i < 32; i++) value.append((char) i);
        assertEquals(value.toString(), ClipboardWireCodec.decode(ClipboardWireCodec.encode(value.toString())));
    }
    @Test public void noncanonicalPaddingAndWhitespaceAreRejected() {
        for (String invalid : new String[]{"YQ", "YQ=", "YR==", "YQ==\n", "YQ== ", "-_==", "===="})
            assertThrows(invalid, IllegalArgumentException.class, () -> ClipboardWireCodec.decode(invalid));
    }
    @Test public void encodedLengthDoesNotPermit4097Bytes() {
        assertThrows(IllegalArgumentException.class,
                () -> ClipboardWireCodec.decode(Base64.getEncoder().encodeToString(new byte[4097])));
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.decode("A".repeat(5468)));
    }
    @Test public void malformedUtf8AndInvalidUtf16DoNotGetReplacementCharacters() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.decode("wK8="));
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.encode("\ud800"));
    }
    @Test public void nullAndEmptyRemainInvalidRatherThanClearingClipboard() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> ClipboardWireCodec.decode(""));
    }
}
