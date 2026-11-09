package io.github.yuejie.connect;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Portable application handoff, never an arbitrary serialized Android Intent. */
record AppRelayRequest(String requestId, String sourcePackage, Kind kind, List<String> targets,
                       String uri, String signerSha256, String fileToken, String mime,
                       int progressType, int progress, String progressText, String nativePayload) {
    enum Kind { APP, BROWSER, DOCUMENT, HOME, RICH_TEXT }
    private static final Set<String> BLOCKED = Set.of("intent", "file", "data", "javascript",
            "tel", "sms", "smsto", "mms", "mmsto", "mailto", "package");

    AppRelayRequest(String requestId, String sourcePackage, Kind kind, List<String> targets,
                    String uri, String signerSha256, String fileToken, String mime,
                    int progressType, int progress, String progressText) {
        this(requestId, sourcePackage, kind, targets, uri, signerSha256, fileToken, mime, progressType, progress, progressText, "");
    }
    AppRelayRequest {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")) fail("invalid_request_id");
        packageName(sourcePackage);
        if (kind == null || targets == null || (targets.isEmpty() && kind != Kind.BROWSER) || targets.size() > 8) fail("invalid_targets");
        targets = List.copyOf(targets);
        if (targets.stream().distinct().count() != targets.size()) fail("duplicate_targets");
        targets.forEach(AppRelayRequest::packageName);
        uri = bounded(uri, 8192); signerSha256 = bounded(signerSha256, 64);
        fileToken = bounded(fileToken, 128); mime = bounded(mime, 128);
        progressText = bounded(progressText, 2048);
        nativePayload = bounded(nativePayload, 4096);
        if (!signerSha256.isEmpty() && !signerSha256.matches("[0-9a-f]{64}")) fail("invalid_signer");
        if (progressType < 0 || progressType > 3 || progress < 0) fail("invalid_progress");
        if ((kind == Kind.APP || kind == Kind.RICH_TEXT) && signerSha256.isEmpty()) fail("missing_signer");
        if (kind == Kind.RICH_TEXT) {
            OplusRelayPayload.richText(nativePayload, sourcePackage);
            if (!targets.equals(List.of(sourcePackage))) fail("invalid_rich_text_target");
        } else if (!nativePayload.isEmpty()) fail("unexpected_native_payload");
        if (kind == Kind.APP || kind == Kind.BROWSER) {
            URI parsed;
            try { parsed = URI.create(uri); } catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid_uri"); }
            String scheme = parsed.getScheme();
            if (scheme == null || BLOCKED.contains(scheme.toLowerCase(java.util.Locale.ROOT))
                    || scheme.equalsIgnoreCase("content") || parsed.getRawUserInfo() != null) fail("unsafe_uri");
            if (kind == Kind.BROWSER && (!(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || parsed.getHost() == null)) fail("invalid_browser_uri");
        } else if (!uri.isEmpty()) fail("unexpected_uri");
        if (kind == Kind.DOCUMENT) {
            if (!fileToken.matches("[A-Za-z0-9_-]{1,128}") || !mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) fail("invalid_document");
        } else if (!fileToken.isEmpty() || !mime.isEmpty() || progressType != 0 || progress != 0 || !progressText.isEmpty()) fail("unexpected_document_fields");
    }

    static void packageName(String value) {
        if (value == null || value.length() > 200 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) fail("invalid_package");
    }
    private static String bounded(String value, int bytes) {
        if (value == null) return "";
        if (value.indexOf('\0') >= 0 || value.getBytes(StandardCharsets.UTF_8).length > bytes) fail("payload_too_large");
        return value;
    }
    private static void fail(String code) { throw new IllegalArgumentException(code); }
    // Do not let logs expose a URL, file token, or application document position.
    @Override public String toString() { return "AppRelayRequest[" + requestId + "," + kind + "]"; }
}
