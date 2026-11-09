package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Only locally observed message references may be addressed from the transport. */
final class TelephonySyncSmsState implements AutoCloseable {
    record NativeTarget(int session, String reference) {}
    private final TelephonySyncController.Session session;
    private final Map<String, NativeTarget> targets = new LinkedHashMap<>();
    private boolean closed;
    TelephonySyncSmsState(TelephonySyncController.Session session) { this.session = session; }
    synchronized String observed(int nativeSession, String reference) {
        if (closed) throw new IllegalStateException("session_closed");
        if (nativeSession < 0 || reference == null || !reference.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("invalid_sms_reference");
        NativeTarget target = new NativeTarget(nativeSession, reference);
        for (var e : targets.entrySet()) if (e.getValue().equals(target)) return e.getKey();
        if (targets.size() >= 512) throw new IllegalStateException("sms_capacity");
        String id = UUID.randomUUID().toString().replace("-", ""); targets.put(id, target); return id;
    }
    synchronized List<NativeTarget> resolve(TelephonySyncController.Session source, List<String> ids) {
        if (closed || !session.equals(source)) throw new IllegalStateException("session_invalid");
        if (ids == null || ids.isEmpty() || ids.size() > 100) throw new IllegalArgumentException("invalid_payload");
        ArrayList<NativeTarget> result = new ArrayList<>();
        for (String id : ids) {
            NativeTarget target = targets.get(id);
            if (target == null) throw new IllegalStateException("sms_missing");
            if (!result.isEmpty() && result.get(0).session() != target.session()) throw new IllegalStateException("native_session_mismatch");
            if (!result.contains(target)) result.add(target);
        }
        return List.copyOf(result);
    }
    synchronized void nativeDisconnected(int nativeSession) { targets.values().removeIf(t -> t.session() == nativeSession); }
    @Override public synchronized void close() { closed = true; targets.clear(); }
}
