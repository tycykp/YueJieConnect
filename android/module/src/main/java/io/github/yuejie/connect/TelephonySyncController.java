package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Session-bound call control. A native void return is submission, never completion. */
final class TelephonySyncController implements AutoCloseable {
    enum State { NEW, CONNECTING, DIALING, RINGING, ACTIVE, HOLDING, DISCONNECTING, ENDED }
    enum Action { ANSWER, REJECT, END, HOLD, UNHOLD }
    record Session(String peer, String id) {
        Session {
            if (peer == null || peer.isBlank() || peer.length() > 256 || id == null || !id.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid_session");
        }
    }
    record Snapshot(String call, long revision, State state) {}
    record Command(Session session, String request, String call, long revision, Action action) {
        Command {
            Objects.requireNonNull(session); Objects.requireNonNull(action);
            if (request == null || !request.matches("[A-Za-z0-9_-]{1,96}") || call == null
                    || !call.matches("[A-Za-z0-9_-]{1,96}") || revision < 1)
                throw new IllegalArgumentException("invalid_command");
        }
    }
    record Result(String request, String call, String code, Snapshot observed) {}
    interface Policy {
        boolean allows(Session session, Action action);
        default boolean observes(Session session) { return false; }
        /** Separate native approval for this exact call, never inferred from observe/answer consent. */
        default boolean audio(Session session, String call) { return false; }
    }
    interface NativeCall { void execute(Action action) throws Exception; }
    private static final int MAX_REQUESTS = 256;
    private static final int MAX_CALLS = 16;
    private static final long TIMEOUT_MS = 10_000;
    private final Session session;
    private final Policy policy;
    private final LongSupplier clock;
    private final Map<String, Entry> calls = new LinkedHashMap<>();
    // Do not evict IDs within a session: a late replay must never repeat a side effect.
    private final Map<String, Pending> requests = new LinkedHashMap<>();
    private boolean closed;
    private long serial;
    private final String epoch = UUID.randomUUID().toString().replace("-", "");
    private static final class Entry {
        final NativeCall handle;
        Snapshot snapshot;
        Entry(NativeCall handle, Snapshot snapshot) { this.handle = handle; this.snapshot = snapshot; }
    }
    private static final class Pending {
        final Command command;
        final long deadline;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        Pending(Command command, long deadline) { this.command = command; this.deadline = deadline; }
    }
    TelephonySyncController(Session session, Policy policy, LongSupplier clock) {
        this.session = Objects.requireNonNull(session); this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }
    synchronized String add(NativeCall handle, State state) {
        if (closed) throw new IllegalStateException("session_closed");
        if (calls.size() >= MAX_CALLS) throw new IllegalStateException("call_capacity");
        String id = "call_" + epoch + "_" + ++serial;
        calls.put(id, new Entry(Objects.requireNonNull(handle), new Snapshot(id, 1, Objects.requireNonNull(state))));
        return id;
    }
    synchronized Snapshot snapshot(String id) { Entry e = calls.get(id); return e == null ? null : e.snapshot; }
    synchronized void observe(String id, State state) {
        Entry e = calls.get(id);
        if (closed || e == null) return;
        if (e.snapshot.state() == State.ENDED && state != State.ENDED) return;
        if (e.snapshot.state() != state) e.snapshot = new Snapshot(id, e.snapshot.revision() + 1, state);
        for (Pending p : requests.values().toArray(Pending[]::new)) {
            if (p.future.isDone() || !p.command.call().equals(id)) continue;
            if (!policy.allows(session, p.command.action())) finish(p, "permission_revoked", e.snapshot);
            else if (clock.getAsLong() >= p.deadline) finish(p, "outcome_unknown", e.snapshot);
            else if (reached(p.command.action(), state)) finish(p, "observed", e.snapshot);
            else if (state == State.ENDED) finish(p, "call_ended", e.snapshot);
        }
    }
    synchronized void remove(String id) { observe(id, State.ENDED); calls.remove(id); }
    synchronized CompletableFuture<Result> execute(Command command) {
        return execute(command, TIMEOUT_MS);
    }
    synchronized CompletableFuture<Result> execute(Command command, long remainingMs) {
        if (closed || !session.equals(command.session())) return result(command, "session_invalid", null);
        if (!policy.allows(session, command.action())) return result(command, "permission_denied", null);
        Pending previous = requests.get(command.request());
        if (previous != null) return previous.command.equals(command) ? previous.future
                : result(command, "request_conflict", null);
        if (remainingMs <= 0) return result(command, "deadline_expired", null);
        if (requests.size() >= MAX_REQUESTS) return result(command, "request_capacity", null);
        Entry e = calls.get(command.call());
        if (e == null) return result(command, "call_missing", null);
        if (e.snapshot.revision() != command.revision()) return result(command, "stale_call", e.snapshot);
        if (!valid(command.action(), e.snapshot.state())) return result(command, "invalid_call_state", e.snapshot);
        for (Pending p : requests.values()) if (!p.future.isDone() && p.command.call().equals(command.call()))
            return result(command, "call_busy", e.snapshot);
        Pending p = new Pending(command, clock.getAsLong() + Math.min(TIMEOUT_MS, remainingMs));
        requests.put(command.request(), p);
        try { e.handle.execute(command.action()); }
        catch (SecurityException denied) { finish(p, "permission_denied", e.snapshot); }
        catch (Exception failure) { finish(p, "native_failure", e.snapshot); }
        return p.future;
    }
    synchronized void cancel(Session source, String request) {
        if (!session.equals(source)) return;
        Pending pending = requests.get(request);
        if (pending != null) finish(pending, "outcome_unknown", snapshot(pending.command.call()));
    }
    synchronized void tick() {
        for (Pending p : requests.values().toArray(Pending[]::new)) if (!p.future.isDone()) {
            if (!policy.allows(session, p.command.action())) finish(p, "permission_revoked", null);
            else if (clock.getAsLong() >= p.deadline) finish(p, "outcome_unknown", snapshot(p.command.call()));
        }
    }
    @Override public synchronized void close() {
        closed = true;
        for (Pending p : requests.values().toArray(Pending[]::new)) finish(p, "session_closed", null);
        calls.clear(); requests.clear();
    }
    private static boolean valid(Action a, State s) {
        return switch (a) {
            case ANSWER, REJECT -> s == State.RINGING;
            case END -> s == State.ACTIVE || s == State.HOLDING || s == State.DIALING || s == State.CONNECTING;
            case HOLD -> s == State.ACTIVE;
            case UNHOLD -> s == State.HOLDING;
        };
    }
    private static boolean reached(Action a, State s) {
        return switch (a) {
            case ANSWER, UNHOLD -> s == State.ACTIVE;
            case END, REJECT -> s == State.ENDED;
            case HOLD -> s == State.HOLDING;
        };
    }
    private static CompletableFuture<Result> result(Command c, String code, Snapshot s) {
        return CompletableFuture.completedFuture(new Result(c.request(), c.call(), code, s));
    }
    private static void finish(Pending p, String code, Snapshot s) {
        p.future.complete(new Result(p.command.request(), p.command.call(), code, s));
    }
}
