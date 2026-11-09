package io.github.yuejie.connect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Local bounded metadata table. Authenticated prepare is not a native receive decision.
 * The shared JNI table independently preserves transfer/step tombstones across OEM restarts. */
public final class FileTransferNegotiation implements AutoCloseable {
    public static final int SESSION_TRANSFERS = 256;
    public enum Purpose {
        DRAG("drag"), CLIPBOARD("clipboard"), APP_RELAY("app_relay");
        private final String wire;
        Purpose(String wire) { this.wire = wire; }
        public String wireName() { return wire; }
        public static Purpose fromWire(String wire) {
            for (Purpose value : values()) if (value.wire.equals(wire)) return value;
            throw new IllegalArgumentException("invalid_file_payload");
        }
    }
    public record Metadata(Purpose purpose, String nativeOwnerToken, FileTransferEngine.Offer offer) {
        public Metadata {
            Objects.requireNonNull(purpose); Objects.requireNonNull(offer);
            if (nativeOwnerToken == null || !nativeOwnerToken.matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("invalid_file_payload");
        }
    }
    public record Prepared(FileTransferEngine.Key key, Metadata metadata) {}
    public record State(Prepared prepared, boolean approved, boolean firstSeen) {}
    /** Notify the current single resource owner to revoke; false retains occupied capacity for retry.
     * Implementations must close their existing routes/lease, never introduce another delete owner. */
    public interface Cleanup { boolean release(); }
    private static final class Entry {
        final Prepared prepared;
        final long deadline;
        FileTransferEngine.SessionGate nativeGate;
        Cleanup cleanup;
        boolean deciding, approved, occupied = true, cleaning;
        String failure;
        Entry(Prepared prepared, long deadline) { this.prepared = prepared; this.deadline = deadline; }
    }
    private final Map<FileTransferEngine.Key, Entry> entries = new HashMap<>();
    private final FileTransferEngine.SessionGate sessions;
    private final LongSupplier clock;
    private String session;
    private String observedSession;
    private boolean closed;

    public FileTransferNegotiation(FileTransferEngine.SessionGate sessions, LongSupplier elapsedRealtime) {
        this.sessions = Objects.requireNonNull(sessions); clock = Objects.requireNonNull(elapsedRealtime);
    }

    /** Called only from an authenticated typed prepare request. No file, URI or grant is created. */
    public State prepare(FileTransferEngine.Key key, Metadata metadata) throws IOException {
        maintain();
        synchronized (this) {
            requireCurrent(key);
            Entry previous = entries.get(key);
            if (previous != null) {
                if (!previous.prepared.metadata().equals(metadata)) throw new IOException("consent_metadata_mismatch");
                if (previous.failure != null) throw new IOException(previous.failure);
                return new State(previous.prepared, previous.approved, false);
            }
            if (entries.size() >= SESSION_TRANSFERS) throw new IOException("file_transfer_limit");
            if (entries.values().stream().filter(e -> e.occupied).count() >= FileTransferEngine.MAX_TRANSFERS)
                throw new IOException("file_busy");
            if (entries.values().stream().anyMatch(e -> e.prepared.metadata().purpose() == metadata.purpose()
                    && e.prepared.metadata().nativeOwnerToken().equals(metadata.nativeOwnerToken())))
                throw new IOException("native_file_owner_reused");
            long lifetime = metadata.purpose() == Purpose.CLIPBOARD ? 300_000 : 30_000;
            Prepared prepared = new Prepared(key, metadata);
            Entry entry = new Entry(prepared, clock.getAsLong() + lifetime);
            entries.put(key, entry);
            if (session == null) session = key.session();
            return new State(prepared, false, true);
        }
    }

    /** Local exact-token lookup, e.g. for a native provider URI for which Android granted READ. */
    public Prepared find(Purpose purpose, String token) {
        maintain();
        synchronized (this) {
            for (Entry entry : entries.values()) if (entry.occupied && entry.failure == null
                    && entry.prepared.metadata().purpose() == purpose
                    && entry.prepared.metadata().nativeOwnerToken().equals(token)) return entry.prepared;
            return null;
        }
    }
    public List<Prepared> pending(Purpose purpose) {
        maintain();
        synchronized (this) {
            List<Prepared> values = new ArrayList<>();
            for (Entry entry : entries.values()) if (entry.occupied && entry.failure == null
                    && entry.prepared.metadata().purpose() == purpose) values.add(entry.prepared);
            return List.copyOf(values);
        }
    }
    public synchronized List<FileTransferEngine.Key> releasedKeys() {
        return entries.values().stream().filter(e -> !e.occupied).map(e -> e.prepared.key()).toList();
    }
    public synchronized List<Prepared> released() {
        return entries.values().stream().filter(e -> !e.occupied).map(e -> e.prepared).toList();
    }
    public synchronized boolean resourcesReleased() { return entries.values().stream().noneMatch(e -> e.occupied); }
    public synchronized java.util.Set<FileTransferEngine.Key> retainedKeys() { return java.util.Set.copyOf(entries.keySet()); }

    /** A genuine local native decision must precede this call. The owner retains its single lease.
     * false does not transfer cleanup responsibility for a newly created, unmatched lease. */
    public boolean approve(Prepared exact, FileTransferEngine.SessionGate nativeDecisionStillCurrent,
            CompletionStage<Void> actualLeaseAccepted, Cleanup cleanup) {
        Objects.requireNonNull(nativeDecisionStillCurrent); Objects.requireNonNull(actualLeaseAccepted);
        Objects.requireNonNull(cleanup);
        Entry entry;
        synchronized (this) {
            entry = entries.get(exact.key());
            if (entry == null || entry.prepared != exact || entry.failure != null || entry.deciding
                    || !current(exact.key()) || clock.getAsLong() >= entry.deadline
                    || !nativeCurrent(nativeDecisionStillCurrent, exact.key())) return false;
            entry.deciding = true; entry.nativeGate = nativeDecisionStillCurrent; entry.cleanup = cleanup;
        }
        actualLeaseAccepted.whenComplete((ignored, failure) -> {
            synchronized (FileTransferNegotiation.this) {
                if (entries.get(exact.key()) != entry || entry.failure != null) return;
                if (failure != null) fail(entry, "receive_consent_failed");
                else if (!current(exact.key()) || !nativeCurrent(entry.nativeGate, exact.key())) fail(entry, "stale_session");
                else if (clock.getAsLong() >= entry.deadline) fail(entry, "request_timeout");
                else entry.approved = true;
            }
            maintain();
        });
        return true;
    }

    public void revoke(FileTransferEngine.Key key) {
        synchronized (this) { Entry entry = entries.get(key); if (entry != null) fail(entry, "file_cancelled"); }
        maintain();
    }
    /** Called by the actual native resource owner only after it has disposed its lease and taken
     * the final transfer result. Releases the slot, but the key and owner-token tombstones remain. */
    public boolean released(Prepared exact) {
        synchronized (this) {
            Entry entry = entries.get(exact.key());
            if (entry == null || entry.prepared != exact) return false;
            fail(entry, "file_transfer_released"); entry.cleanup = null; entry.occupied = false;
            return true;
        }
    }
    public void sessionChanged() { maintain(); }
    /** Only the actual authenticated projection session goes here. Null disconnects without
     * forgetting tombstones; grants/executor availability alone never reset the session budget. */
    public void sessionChanged(String actualAuthenticatedSession) {
        if (actualAuthenticatedSession != null && !actualAuthenticatedSession.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid_session");
        synchronized (this) {
            if (actualAuthenticatedSession != null) {
                observedSession = actualAuthenticatedSession;
                if (session != null && !session.equals(observedSession))
                    for (Entry entry : entries.values()) fail(entry, "stale_session");
            }
        }
        maintain();
    }
    public void maintain() {
        List<Entry> cleanup = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (entry.failure == null) {
                    if (!current(entry.prepared.key())) fail(entry, "stale_session");
                    else if (entry.deciding && !nativeCurrent(entry.nativeGate, entry.prepared.key())) fail(entry, "stale_session");
                    else if (!entry.approved && clock.getAsLong() >= entry.deadline) fail(entry, "request_timeout");
                }
                if (entry.failure != null && entry.occupied) {
                    if (entry.cleanup == null) entry.occupied = false;
                    else if (!entry.cleaning) { entry.cleaning = true; cleanup.add(entry); }
                }
            }
            // Only a real session change can retire its bounded tombstones, and only after
            // resource cleanup. A temporarily unavailable OEM executor must not reset this table.
            if (session != null && observedSession != null && !session.equals(observedSession)
                    && entries.values().stream().noneMatch(e -> e.occupied)) {
                entries.clear(); session = observedSession;
            }
        }
        for (Entry entry : cleanup) {
            boolean disposed;
            try { disposed = entry.cleanup.release(); }
            catch (RuntimeException retry) { disposed = false; }
            synchronized (this) {
                entry.cleaning = false;
                if (disposed) { entry.occupied = false; entry.cleanup = null; }
            }
        }
    }
    private boolean nativeSessionCurrent(FileTransferEngine.Key key) {
        try { return sessions.current(key); } catch (RuntimeException denied) { return false; }
    }
    private boolean current(FileTransferEngine.Key key) {
        return !closed && (session == null || session.equals(key.session()))
                && (observedSession == null || observedSession.equals(key.session())) && nativeSessionCurrent(key);
    }
    private void requireCurrent(FileTransferEngine.Key key) throws IOException {
        if (!current(key)) throw new IOException("stale_session");
    }
    private static boolean nativeCurrent(FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) {
        try { return gate != null && gate.current(key); } catch (RuntimeException denied) { return false; }
    }
    private static void fail(Entry entry, String code) {
        if (entry.failure == null) entry.failure = code;
        entry.approved = false;
    }
    @Override public void close() { synchronized (this) { closed = true; } maintain(); }
}
