package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Two base64 values share a 4096-byte budget (at most 5468 encoded chars plus JSON).
 * Empty plain fallback is valid HTML; lone surrogates and noncanonical base64 are rejected. */
final class ClipboardRichCodec {
    static JSONObject encode(ClipboardRichContent.Html value) throws JSONException {
        return new JSONObject().put("mime", "text/html")
                .put("utf8_b64", Base64.getEncoder().encodeToString(ClipboardRichContent.utf8(value.text())))
                .put("html_utf8_b64", Base64.getEncoder().encodeToString(ClipboardRichContent.utf8(value.html())));
    }
    static ClipboardRichContent.Html decode(JSONObject payload) {
        java.util.HashSet<String> fields = new java.util.HashSet<>();
        if (payload != null) payload.keys().forEachRemaining(fields::add);
        if (payload == null || !fields.equals(Set.of("mime", "utf8_b64", "html_utf8_b64"))
                || !"text/html".equals(payload.opt("mime"))) throw new IllegalArgumentException("invalid_html_payload");
        return new ClipboardRichContent.Html(decodePart(payload.opt("utf8_b64")), decodePart(payload.opt("html_utf8_b64")));
    }
    private static String decodePart(Object value) {
        if (!(value instanceof String text) || text.length() > ClipboardWireCodec.MAX_ENCODED_CHARS)
            throw new IllegalArgumentException("invalid_html_payload");
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (bytes.length > ClipboardRichContent.MAX_BYTES || !Base64.getEncoder().encodeToString(bytes).equals(text))
                throw new IllegalArgumentException("invalid_html_payload");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid_html_payload");
        }
    }
    private ClipboardRichCodec() {}
}
