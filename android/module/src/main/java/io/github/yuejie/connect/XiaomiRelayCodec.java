package io.github.yuejie.connect;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

final class XiaomiRelayCodec {
    interface Files {
        // Export the OEM virtual downloadURL through the real file-transfer layer; do not fetch
        // arbitrary network URLs. The returned token identifies a committed destination file.
        String committedToken(Object relayOfficeMessage, android.net.Uri locallyGrantedUri, String peer, String session,
                              java.util.function.BooleanSupplier currentRequest) throws Exception;
        default String committedToken(Object message, android.net.Uri uri, String peer, String session,
                                      java.util.function.BooleanSupplier current, String nativeOwnerToken) throws Exception {
            if (!nativeOwnerToken.isEmpty()) throw new IllegalStateException("native_document_consent_required");
            return committedToken(message, uri, peer, session, current);
        }
    }
    static AppRelayRequest decode(Context context, Object message, String id, Files files,
                                  android.net.Uri officeUri, String peer, String session,
                                  java.util.function.BooleanSupplier currentRequest) throws Exception {
        return decode(context, message, id, files, officeUri, peer, session, currentRequest, "");
    }
    static AppRelayRequest decode(Context context, Object message, String id, Files files,
                                  android.net.Uri officeUri, String peer, String session,
                                  java.util.function.BooleanSupplier currentRequest, String nativeOwnerToken) throws Exception {
        String name = message.getClass().getSimpleName();
        AppRelayCatalog.requireClass(message, "com.xiaomi.mirror.message." + name);
        AppRelaySync.validateOwnerToken(nativeOwnerToken);
        if (!nativeOwnerToken.isEmpty() && !"RelayOfficeMessage".equals(name))
            throw new IllegalStateException("native_document_consent_required");
        String source = string(message, "packageName");
        if ("RelayBrowserMessage".equals(name)) {
            // Only carry the URL. Native cookies and browser-specific state stay on the source.
            return new AppRelayRequest(id, source, AppRelayRequest.Kind.BROWSER, List.of(),
                    string(message, "url"), "", "", "", 0, 0, "");
        }
        if ("RelayAppIntentMessage".equals(name)) {
            Class<?> deviceUtils = Class.forName("q4.j", false, message.getClass().getClassLoader());
            String nativeSigner = (String) deviceUtils.getMethod("o", Context.class, String.class).invoke(null, context, source);
            if (nativeSigner == null || !nativeSigner.equals(string(message, "signature"))) throw new SecurityException("source_signature_mismatch");
            List<String> targets = strings(message, "targetPkgs");
            // The regular Bilibili app advertises its HD edition in its Xiaomi SDK.
            // On the other device the same signed regular app also handles this URI.
            String uri = string(message, "uri");
            if (XiaomiRelayCatalog.BILI_PACKAGE.equals(source)) {
                android.net.Uri state = android.net.Uri.parse(uri);
                if ("bilibili".equalsIgnoreCase(state.getScheme()) && "home".equalsIgnoreCase(state.getHost()))
                    throw new IllegalStateException("native_source_unavailable");
                if (targets.equals(List.of("tv.danmaku.bilibilihd"))) targets = List.of("tv.danmaku.bilibilihd", source);
            }
            return new AppRelayRequest(id, source, AppRelayRequest.Kind.APP, targets,
                    uri, AppRelayEndpoint.signers(context, source).get(0), "", "", 0, 0, "");
        }
        if ("RelayOfficeMessage".equals(name)) {
            if (files == null) throw new IllegalStateException("file_transfer_required");
            return new AppRelayRequest(id, source, AppRelayRequest.Kind.DOCUMENT, List.of(source), "", "",
                    files.committedToken(message, officeUri, peer, session, currentRequest, nativeOwnerToken), officeMime(message), number(message, "progressType"), number(message, "progressInt"), string(message, "progressString"));
        }
        throw new IllegalArgumentException("not_application_handoff");
    }
    static String officeMime(Object message) throws Exception {
        int type = number(message, "officeType");
        String mime = switch (type) {
            case 1 -> "application/msword";
            case 2 -> "application/vnd.ms-excel";
            case 3 -> "application/vnd.ms-powerpoint";
            case 4 -> "application/pdf";
            case 5 -> "text/plain";
            default -> throw new IllegalArgumentException("invalid_office_type");
        };
        String path = string(message, "downloadPATH").toLowerCase(java.util.Locale.ROOT);
        if (type == 1 && path.endsWith(".docx")) mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (type == 2 && path.endsWith(".xlsx")) mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (type == 3 && path.endsWith(".pptx")) mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        return mime;
    }
    static boolean accepts(Object message) {
        if (message == null) return false;
        return List.of("RelayBrowserMessage", "RelayAppIntentMessage", "RelayOfficeMessage").contains(message.getClass().getSimpleName());
    }
    private static String string(Object message, String field) throws Exception {
        Object value = message.getClass().getField(field).get(message); return value == null ? "" : (String) value;
    }
    private static int number(Object message, String field) throws Exception { return message.getClass().getField(field).getInt(message); }
    private static List<String> strings(Object message, String field) throws Exception {
        Object value = message.getClass().getField(field).get(message);
        if (!(value instanceof List<?> list) || list.size() > 8) throw new IllegalArgumentException("invalid_targets");
        ArrayList<String> values = new ArrayList<>();
        for (Object item : list) { if (!(item instanceof String text)) throw new IllegalArgumentException("invalid_targets"); values.add(text); }
        return values;
    }
    private XiaomiRelayCodec() {}
}
