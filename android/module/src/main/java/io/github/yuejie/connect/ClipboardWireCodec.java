package io.github.yuejie.connect;

import java.util.Base64;

/** Canonical bounded envelope value. JSON transports must carry text_b64, never raw text. */
final class ClipboardWireCodec {
    static final int MAX_ENCODED_CHARS = 5464; // ceil(4096 / 3) * 4
    private ClipboardWireCodec() {}

    static String encode(String text) {
        return Base64.getEncoder().encodeToString(OplusClipboardCodec.encode(text));
    }

    static String decode(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ENCODED_CHARS)
            throw new IllegalArgumentException("invalid_payload");
        final byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid_base64"); }
        if (!Base64.getEncoder().encodeToString(bytes).equals(value))
            throw new IllegalArgumentException("noncanonical_base64");
        return OplusClipboardCodec.decode(0, 0, bytes);
    }
}
