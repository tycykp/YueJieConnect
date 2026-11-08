package io.github.qiangjian.connect;

import android.content.ClipData;
import android.content.ClipDescription;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** HTML is an explicit pair; never infer it from Mirror's lossy A0 plain-text message. */
final class ClipboardRichContent {
    static final int MAX_BYTES = 4096;

    record Html(String text, String html) {
        Html {
            if (text == null || html == null || html.isEmpty()
                    || utf8(text).length + utf8(html).length > MAX_BYTES)
                throw new IllegalArgumentException("invalid_html_payload");
        }
        int utf8Bytes() { return utf8(text).length + utf8(html).length; }
        String digest() {
            MessageDigest hash = sha256();
            byte[] plain = utf8(text), rich = utf8(html);
            hash.update(ByteBuffer.allocate(4).putInt(plain.length).array());
            hash.update(plain); hash.update(rich);
            return hex(hash.digest());
        }
        ClipData localClip() {
            return new ClipData(new ClipDescription("clipboard", new String[]{"text/plain", "text/html"}),
                    new ClipData.Item(text, html, null, null));
        }
        boolean matches(ClipData clip) {
            try { return equals(snapshot(clip)); }
            catch (IllegalArgumentException invalid) { return false; }
        }
    }

    static Html snapshot(ClipData clip) {
        if (clip == null || clip.getItemCount() != 1 || !clip.getDescription().hasMimeType("text/html"))
            throw new IllegalArgumentException("unsupported_clipboard_format");
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null || item.getIntent() != null || item.getText() == null)
            throw new IllegalArgumentException("unsupported_clipboard_format");
        return new Html(item.getText().toString(), item.getHtmlText());
    }

    static byte[] utf8(String value) {
        if (value == null || value.length() > MAX_BYTES) throw new IllegalArgumentException("invalid_html_payload");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("invalid_html_payload"); }
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    private ClipboardRichContent() {}
}
