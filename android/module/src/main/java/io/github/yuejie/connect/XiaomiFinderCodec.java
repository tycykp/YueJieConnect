package io.github.yuejie.connect;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** SharedChannel strips prefix 3/6. ringfind payload is direction, method, body. */
final class XiaomiFinderCodec {
    static final int START = 1, STOP = 2, STATE = 3, NOTIFY = 4;
    record Command(int method, String action, long timestamp) {}

    static Command decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > 1024 || bytes[0] != 0)
            throw new IllegalArgumentException("invalid_finder_packet");
        int method = bytes[1] & 255;
        if (method == STATE && bytes.length == 2) return new Command(method, "state", 0);
        if (method != START && method != STOP) throw new IllegalArgumentException("invalid_finder_method");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("invalid_finder_utf8", error); }
        // OEM format is userName/deviceName/time. The names are not trusted identity.
        int separator = text.lastIndexOf('/');
        if (separator <= 0 || text.indexOf('/') == separator) throw new IllegalArgumentException("invalid_finder_body");
        long timestamp;
        try { timestamp = Long.parseLong(text.substring(separator + 1)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("invalid_finder_timestamp", error); }
        if (timestamp < 0) throw new IllegalArgumentException("invalid_finder_timestamp");
        return new Command(method, method == START ? "start" : "stop", timestamp);
    }

    static byte[] response(int method, int status) {
        if (method < START || method > NOTIFY || status < 0) throw new IllegalArgumentException("invalid_finder_response");
        int length = status == 0 ? 1 : (32 - Integer.numberOfLeadingZeros(status) + 7) / 8;
        byte[] result = new byte[length + 2];
        result[0] = 1;
        result[1] = (byte) method;
        for (int i = 0; i < length; i++) result[i + 2] = (byte) (status >>> (8 * (length - i - 1)));
        return result;
    }

    static int nativeStatus(int method, boolean ringing) {
        if (method == STATE || method == NOTIFY) return ringing ? 100 : 101;
        if ((method == START && ringing) || (method == STOP && !ringing)) return 0;
        return 200;
    }
}
