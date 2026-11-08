package io.github.qiangjian.connect;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** App-private per-native-request foreground consent. Pairing and CAMERA permission do not allow it. */
final class CameraShareConsentRegistry {
    record Scope(String sessionId, String peerId, long streamId, String cameraId) {
        Scope {
            new CameraShareLease(sessionId, peerId);
            if (streamId <= 0 || cameraId == null || cameraId.isBlank() || cameraId.length() > 128)
                throw new IllegalArgumentException("invalid_camera_scope");
        }
    }
    enum State { PENDING, APPROVED, ACTIVE, CLOSED }
    static final class Entry {
        final Scope scope;
        final long deadlineMs;
        private final BooleanSupplier current;
        private State state = State.PENDING;
        private long approvedUntilMs;
        private Entry(Scope scope, long deadlineMs, BooleanSupplier current) {
            this.scope = scope; this.deadlineMs = deadlineMs; this.current = current;
        }
        private boolean current() {
            try { return current.getAsBoolean(); } catch (RuntimeException e) { return false; }
        }
    }
    private record Key(String session, long stream) { }
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<Key> used = new HashSet<>();
    private boolean closed;

    synchronized String arm(Scope scope, long nowMs, long permissionDeadlineMs, BooleanSupplier current) {
        if (closed || scope == null || current == null || permissionDeadlineMs <= nowMs
                || permissionDeadlineMs - nowMs > 120_000) throw new IllegalArgumentException("invalid_camera_request_deadline");
        Entry entry = new Entry(scope, permissionDeadlineMs, current);
        if (!entry.current()) throw new SecurityException("camera_native_request_expired");
        if (entries.values().stream().filter(e -> e.state != State.CLOSED).count() >= 4)
            throw new IllegalStateException("camera_consent_capacity");
        Key key = new Key(scope.sessionId(), scope.streamId());
        if (used.contains(key)) throw new IllegalStateException("camera_request_replayed");
        if (used.size() >= 128) throw new IllegalStateException("camera_request_history_full");
        byte[] nonce = new byte[32]; random.nextBytes(nonce);
        StringBuilder token = new StringBuilder(64);
        for (byte b : nonce) token.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        java.util.Arrays.fill(nonce, (byte) 0);
        used.add(key); entries.put(token.toString(), entry);
        return token.toString();
    }

    synchronized Entry pending(String token, long nowMs) {
        Entry entry = live(token, nowMs);
        return entry != null && entry.state == State.PENDING ? entry : null;
    }

    /** Only the non-exported actual consent Activity's explicit button + runtime grant path calls this. */
    synchronized boolean approve(String token, long nowMs) {
        Entry entry = pending(token, nowMs);
        if (entry == null) return false;
        entry.state = State.APPROVED;
        entry.approvedUntilMs = Math.min(entry.deadlineMs, nowMs + 5_000);
        return true;
    }

    /** Called when the actual user-started camera FGS is beginning; consumes approval once. */
    synchronized Entry activate(String token, long nowMs) {
        Entry entry = live(token, nowMs);
        if (entry == null || entry.state != State.APPROVED) return null;
        entry.state = State.ACTIVE;
        return entry;
    }

    synchronized boolean active(String token, Scope scope, long nowMs) {
        Entry entry = live(token, nowMs);
        return entry != null && entry.state == State.ACTIVE && entry.scope.equals(scope);
    }

    synchronized State state(String token, long nowMs) {
        Entry entry = live(token, nowMs);
        return entry == null ? State.CLOSED : entry.state;
    }

    synchronized void cancel(String token) {
        Entry entry = entries.get(token);
        if (entry != null) entry.state = State.CLOSED;
    }

    synchronized void sessionInvalidated(String session) {
        for (Entry entry : entries.values()) if (entry.scope.sessionId().equals(session)) entry.state = State.CLOSED;
        // Keep anti-replay tombstones for this controller's lifetime; old UI handles never revive.
    }

    synchronized void close() {
        closed = true;
        for (Entry entry : entries.values()) entry.state = State.CLOSED;
    }

    private Entry live(String token, long nowMs) {
        if (closed) return null;
        Entry entry = entries.get(token);
        if (entry == null || entry.state == State.CLOSED) return null;
        if (!entry.current() || (entry.state == State.PENDING && nowMs >= entry.deadlineMs)
                || (entry.state == State.APPROVED && nowMs >= entry.approvedUntilMs)) {
            entry.state = State.CLOSED;
            return null;
        }
        return entry;
    }
}
