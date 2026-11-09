package io.github.yuejie.connect;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** The sampled feature=0 payload is UTF-8. Feature=1 is Parcel + URI metadata, NOT UTF-8. */
final class OplusClipboardCodec {
    static final int MAX_BYTES = 4096;
    private OplusClipboardCodec() {}

    static String decode(int feature, int uriCount, byte[] data) {
        if (feature != 0 || uriCount != 0) throw new IllegalArgumentException("unsupported_clipboard_format");
        if (data == null || data.length == 0 || data.length > MAX_BYTES)
            throw new IllegalArgumentException("invalid_payload");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid_utf8", error);
        }
    }

    static byte[] encode(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES)
            throw new IllegalArgumentException("invalid_payload");
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (buffer.remaining() > MAX_BYTES) throw new IllegalArgumentException("invalid_payload");
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid_utf8", error);
        }
    }
}
