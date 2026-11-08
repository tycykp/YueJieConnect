package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import static io.github.qiangjian.connect.TelephonySyncController.Session;

/** OEM message status replication. This never sends, retries, or reports radio delivery. */
final class TelephonySyncSmsNative implements AutoCloseable {
    record Snapshot(String message, long revision, int status) {}
    record Command(Session session, String request, String message, long revision, int status) {
        Command {
            Objects.requireNonNull(session);
            if (request == null || !request.matches("[A-Za-z0-9_-]{1,96}") || message == null
                    || !message.matches("[0-9a-f]{32}") || revision < 1 || !terminal(status))
                throw new IllegalArgumentException("invalid_sms_status_command");
        }
    }
    record Result(String request, String message, String code, Snapshot observed) {}
    // These are process-local references. No wire decoder may construct them from peer fields.
    record Target(String identifier, String row) {
        Target {
            if (identifier == null || identifier.isBlank() || identifier.length() > 512
                    // The original OEM Action interpolates this local identifier in a quoted SQL IN.
                    // Do not pass a malformed imported identifier into that native selection builder.
                    || identifier.chars().anyMatch(c -> c == '\'' || c < 0x20 || c == 0x7f)
                    || row == null || !row.matches("[0-9]{1,20}"))
                throw new IllegalArgumentException("invalid_native_message");
        }
        @Override public String toString() { return "Target[redacted]"; }
    }
    interface Policy {
        boolean observes(Session session);
        boolean updates(Session session);
    }
    interface Backend {
        /** Recheck exact row/protocol/current status before enqueue. Completion requires native readback. */
        void apply(Target target, int previousStatus, int status, Completion completion) throws Exception;
    }
    interface Completion {
        void complete(String code, Target target, int status);
        boolean active();
    }
    private static final class Entry {
        final Target target;
        Snapshot snapshot;
        Entry(Target target, Snapshot snapshot) { this.target = target; this.snapshot = snapshot; }
    }
    private static final class Pending {
        final Command command;
        final Target target;
        final long deadline;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        Pending(Command command, Target target, long deadline) {
            this.command = command; this.target = target; this.deadline = deadline;
        }
    }
    private final Session session;
    private final Policy policy;
    private final Backend backend;
    private final LongSupplier clock;
    private final Map<String, Entry> messages = new LinkedHashMap<>();
    private final Map<String, Pending> requests = new LinkedHashMap<>();
    private boolean closed;
    TelephonySyncSmsNative(Session session, Policy policy, Backend backend, LongSupplier clock) {
        this.session = Objects.requireNonNull(session); this.policy = Objects.requireNonNull(policy);
        this.backend = Objects.requireNonNull(backend); this.clock = Objects.requireNonNull(clock);
    }
    // Called only from an actual OEM source/readback, never from a peer-provided identifier.
    synchronized Snapshot observe(Target target, int status) {
        Objects.requireNonNull(target);
        if (closed || !policy.observes(session)) return null;
        if (status < 0 || status > 9) return null; // outgoing SMS only, no incoming/MMS/RCS.
        for (Entry e : messages.values()) {
            if (e.target.identifier().equals(target.identifier())) {
                if (!e.target.equals(target)) return null; // never rebind an identifier to another row.
                if (e.snapshot.status() != status)
                    e.snapshot = new Snapshot(e.snapshot.message(), e.snapshot.revision() + 1, status);
                return e.snapshot;
            }
        }
        if (messages.size() >= 512) return null;
        String id = UUID.randomUUID().toString().replace("-", "");
        Snapshot snapshot = new Snapshot(id, 1, status);
        messages.put(id, new Entry(target, snapshot));
        return snapshot;
    }
    synchronized CompletableFuture<Result> apply(Command c, long remainingMs) {
        if (closed || !session.equals(c.session())) return result(c, "session_invalid", null);
        if (!policy.observes(session) || !policy.updates(session)) return result(c, "permission_denied", null);
        Pending old = requests.get(c.request());
        if (old != null) return old.command.equals(c) ? old.result : result(c, "request_conflict", null);
        if (remainingMs <= 0) return result(c, "deadline_expired", null);
        if (requests.size() >= 256) return result(c, "request_capacity", null);
        Entry e = messages.get(c.message());
        if (e == null) return result(c, "sms_missing", null);
        if (e.snapshot.revision() != c.revision()) return result(c, "stale_message", e.snapshot);
        // Final OEM messages are immutable through this path; never overwrite a later delivery/failure.
        if (e.snapshot.status() != 4 && e.snapshot.status() != 5 && e.snapshot.status() != 6
                && e.snapshot.status() != 7) return result(c, "invalid_message_state", e.snapshot);
        for (Pending p : requests.values()) if (!p.result.isDone() && p.command.message().equals(c.message()))
            return result(c, "sms_busy", null);
        long now = clock.getAsLong();
        Pending p = new Pending(c, e.target, now + Math.min(5000, remainingMs));
        requests.put(c.request(), p);
        try {
            backend.apply(e.target, e.snapshot.status(), c.status(), new Completion() {
                @Override public void complete(String code, Target target, int status) { completed(p, code, target, status); }
                @Override public boolean active() { return pendingActive(p); }
            });
        } catch (SecurityException denied) { finish(p, "permission_denied", null); }
        catch (Exception failure) { finish(p, "outcome_unknown", null); }
        return p.result;
    }
    private synchronized boolean pendingActive(Pending p) {
        if (p.result.isDone()) return false;
        if (closed) { finish(p, "session_invalid", null); return false; }
        if (!policy.observes(session) || !policy.updates(session)) { finish(p, "permission_revoked", null); return false; }
        if (clock.getAsLong() >= p.deadline) { finish(p, "outcome_unknown", null); return false; }
        return true;
    }
    private synchronized void completed(Pending p, String code, Target target, int status) {
        if (p.result.isDone()) return;
        if (closed) { finish(p, "session_invalid", null); return; }
        if (!policy.observes(session) || !policy.updates(session)) { finish(p, "permission_revoked", null); return; }
        if (clock.getAsLong() >= p.deadline) { finish(p, "outcome_unknown", null); return; }
        if (!"native_applied".equals(code)) {
            finish(p, switch (code == null ? "" : code) {
                case "sms_missing", "stale_message", "permission_denied", "native_failed" -> code;
                default -> "outcome_unknown";
            }, null); return;
        }
        if (!p.target.equals(target) || p.command.status() != status) { finish(p, "native_mismatch", null); return; }
        Entry e = messages.get(p.command.message());
        if (e == null || !e.target.equals(target)) { finish(p, "sms_missing", null); return; }
        if (e.snapshot.revision() != p.command.revision() && e.snapshot.status() != status) {
            finish(p, "stale_message", e.snapshot); return;
        }
        Snapshot observed = observe(target, status);
        finish(p, "native_applied", observed);
    }
    synchronized void tick() {
        for (Pending p : requests.values().toArray(Pending[]::new)) {
            if (!p.result.isDone() && (!policy.observes(session) || !policy.updates(session)))
                finish(p, "permission_revoked", null);
            else if (!p.result.isDone() && clock.getAsLong() >= p.deadline) finish(p, "outcome_unknown", null);
        }
        if (!policy.observes(session)) messages.clear();
    }
    synchronized boolean cancel(Session source, String request) {
        if (closed || !session.equals(source)) return false;
        Pending p = requests.get(request);
        if (p == null || p.result.isDone()) return false;
        finish(p, "outcome_unknown", null); return true;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (Pending p : requests.values()) if (!p.result.isDone()) finish(p, "session_invalid", null);
        messages.clear(); requests.clear();
    }
    private void finish(Pending p, String code, Snapshot snapshot) {
        p.result.complete(new Result(p.command.request(), p.command.message(), code, snapshot));
    }
    private static CompletableFuture<Result> result(Command c, String code, Snapshot snapshot) {
        return CompletableFuture.completedFuture(new Result(c.request(), c.message(), code, snapshot));
    }
    private static boolean terminal(int status) { return status == 1 || status == 8 || status == 9; }
}
