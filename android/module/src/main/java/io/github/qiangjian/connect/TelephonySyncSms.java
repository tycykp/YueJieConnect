package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Explicit-SIM SMS submission ledger. Payloads stay in memory and are never included in results/logs. */
final class TelephonySyncSms implements AutoCloseable {
    static final int MAX_BODY_BYTES = 8192;
    record Command(TelephonySyncController.Session session, String backendEpoch, String request,
                   int subscription, String destination, String body) {
        Command {
            Objects.requireNonNull(session);
            if (backendEpoch == null || !backendEpoch.matches("[0-9a-f]{32}") || request == null
                    || !request.matches("[A-Za-z0-9_-]{1,96}") || subscription < 0
                    || destination == null || !destination.matches("\\+?[0-9]{3,20}")
                    || body == null || body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES
                    || !wellFormed(body)) throw new IllegalArgumentException("invalid_sms");
        }
        @Override public String toString() { return "SmsCommand[redacted]"; }
    }
    record Result(String request, String code, int nativeCode, int sentParts, int totalParts) {}
    interface Policy { boolean allows(TelephonySyncController.Session session, int subscription); }
    interface Completion { void sent(int part, int nativeCode); }
    interface Backend {
        /** Must prepare parts without sending; returned part count is bounded and stable. */
        Prepared prepare(Command command) throws Exception;
    }
    interface Prepared {
        int parts();
        void send(Completion callback) throws Exception;
        void dispose();
    }
    private final TelephonySyncController.Session session;
    private final Policy policy;
    private final Backend backend;
    private final LongSupplier clock;
    private final Map<String, Pending> requests = new LinkedHashMap<>();
    private final String epoch = UUID.randomUUID().toString().replace("-", "");
    private boolean closed;
    private static final class Pending {
        final Command command;
        final Prepared prepared;
        final boolean[] sent;
        final long deadline;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        int count;
        Pending(Command c, Prepared p, int parts, long deadline) {
            command = c; prepared = p; sent = new boolean[parts]; this.deadline = deadline;
        }
    }
    TelephonySyncSms(TelephonySyncController.Session session, Policy policy, Backend backend, LongSupplier clock) {
        this.session = session; this.policy = policy; this.backend = backend; this.clock = clock;
    }
    String epoch() { return epoch; }
    synchronized CompletableFuture<Result> send(Command command) {
        return send(command, 60_000);
    }
    synchronized CompletableFuture<Result> send(Command command, long remainingMs) {
        if (closed || !session.equals(command.session()) || !epoch.equals(command.backendEpoch()))
            return result(command, "session_invalid");
        if (!policy.allows(session, command.subscription())) return result(command, "permission_denied");
        Pending old = requests.get(command.request());
        if (old != null) return old.command.equals(command) ? old.future : result(command, "request_conflict");
        if (remainingMs <= 0) return result(command, "deadline_expired");
        if (requests.size() >= 128) return result(command, "request_capacity");
        long deadline = clock.getAsLong() + Math.min(60_000, remainingMs);
        Prepared prepared = null;
        try {
            prepared = backend.prepare(command);
            int parts = prepared.parts();
            if (parts < 1 || parts > 64) { prepared.dispose(); return result(command, "invalid_segments"); }
            Pending p = new Pending(command, prepared, parts, deadline);
            requests.put(command.request(), p);
            if (clock.getAsLong() >= deadline) finish(p, "deadline_expired", 0);
            else if (!policy.allows(session, command.subscription())) finish(p, "permission_denied", 0);
            else try { prepared.send((part, code) -> sent(p, part, code)); }
            catch (SecurityException denied) { finish(p, "permission_denied", 0); }
            catch (Exception failure) { finish(p, "outcome_unknown", 0); }
            return p.future;
        } catch (SecurityException denied) {
            if (prepared != null) prepared.dispose();
            return result(command, "permission_denied");
        } catch (Exception failure) {
            if (prepared != null) prepared.dispose();
            return result(command, "prepare_failed");
        }
    }
    synchronized void cancel(TelephonySyncController.Session source, String request) {
        if (!session.equals(source)) return;
        Pending pending = requests.get(request);
        if (pending != null) finish(pending, "outcome_unknown", 0);
    }
    private synchronized void sent(Pending p, int part, int nativeCode) {
        if (p.future.isDone() || part < 0 || part >= p.sent.length || p.sent[part]) return;
        if (closed || !policy.allows(session, p.command.subscription())) { finish(p, "outcome_unknown", 0); return; }
        if (clock.getAsLong() >= p.deadline) { finish(p, "outcome_unknown", 0); return; }
        // Activity.RESULT_OK (-1) means sent to network, not recipient delivery.
        if (nativeCode != -1) { finish(p, "send_failed", nativeCode); return; }
        p.sent[part] = true; p.count++;
        if (p.count == p.sent.length) finish(p, "radio_sent", nativeCode);
    }
    synchronized void tick() {
        for (Pending p : requests.values().toArray(Pending[]::new)) if (!p.future.isDone()
                && (clock.getAsLong() >= p.deadline || !policy.allows(session, p.command.subscription())))
            finish(p, "outcome_unknown", 0);
    }
    @Override public synchronized void close() {
        closed = true;
        for (Pending p : requests.values().toArray(Pending[]::new)) finish(p, "outcome_unknown", 0);
        requests.clear();
    }
    private static void finish(Pending p, String code, int nativeCode) {
        if (p.future.isDone()) return;
        try { p.prepared.dispose(); } catch (RuntimeException ignored) { /* Results must still terminate. */ }
        p.future.complete(new Result(p.command.request(), code, nativeCode, p.count, p.sent.length));
    }
    private static CompletableFuture<Result> result(Command c, String code) {
        return CompletableFuture.completedFuture(new Result(c.request(), code, 0, 0, 0));
    }
    private static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == s.length() || !Character.isLowSurrogate(s.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
