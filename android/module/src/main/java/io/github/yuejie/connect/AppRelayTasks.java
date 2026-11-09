package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Per authenticated session state. Calls into the endpoint are serialized with cancel/revoke. */
final class AppRelayTasks {
    enum State { PREPARED, LAUNCHING, COMPLETED, FAILED, CANCELLED }
    record Result(String requestId, State state, String code, String targetPackage, int taskId) {}
    interface Endpoint {
        Prepared prepare(AppRelayRequest request) throws Exception;
        void launch(Prepared prepared) throws Exception;
        int observe(Prepared prepared) throws Exception;
        void release(Prepared prepared);
        default void close() {}
    }
    interface Prepared { String targetPackage(); }
    private static final class Entry {
        final AppRelayRequest request;
        Prepared prepared;
        Result result;
        long created;
        Entry(AppRelayRequest request) { this.request = request; }
    }
    private final Endpoint endpoint;
    private final BooleanSupplier authorized;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    AppRelayTasks(Endpoint endpoint, BooleanSupplier authorized) { this(endpoint, authorized, () -> System.nanoTime() / 1000000); }
    AppRelayTasks(Endpoint endpoint, BooleanSupplier authorized, LongSupplier clock) {
        this.endpoint = endpoint; this.authorized = authorized; this.clock = clock;
    }

    synchronized Result prepare(AppRelayRequest request) {
        Entry existing = entries.get(request.requestId());
        if (existing != null) {
            if (!existing.request.equals(request)) return result(request, State.FAILED, "request_conflict", "", -1);
            return existing.result;
        }
        if (!live()) return result(request, State.FAILED, "session_revoked", "", -1);
        // Keep terminal IDs until the session ends: retries must never relaunch old requests.
        if (entries.size() >= 128) return result(request, State.FAILED, "session_capacity", "", -1);
        Entry entry = new Entry(request); entry.created = clock.getAsLong(); entries.put(request.requestId(), entry);
        try {
            entry.prepared = endpoint.prepare(request);
            if (!live()) return finish(entry, State.FAILED, "session_revoked", -1);
            return set(entry, State.PREPARED, "ready", -1);
        } catch (Exception error) { return finish(entry, State.FAILED, code(error), -1); }
    }

    synchronized Result launch(String id) {
        expire();
        Entry entry = entries.get(id);
        if (entry == null) return unknown(id);
        if (entry.result.state() != State.PREPARED) return entry.result;
        if (!live()) return finish(entry, State.FAILED, "session_revoked", -1);
        set(entry, State.LAUNCHING, "launch_dispatched", -1);
        try { endpoint.launch(entry.prepared); return entry.result; }
        catch (Exception error) { return finish(entry, State.FAILED, code(error), -1); }
    }

    synchronized Result status(String id) {
        expire();
        Entry entry = entries.get(id);
        if (entry == null) return unknown(id);
        if (entry.result.state() != State.LAUNCHING) return entry.result;
        if (!live()) return finish(entry, State.FAILED, "session_revoked_after_dispatch", -1);
        try {
            int task = endpoint.observe(entry.prepared);
            return task >= 0 ? finish(entry, State.COMPLETED, "foreground_observed", task) : entry.result;
        } catch (Exception error) { return finish(entry, State.FAILED, code(error), -1); }
    }

    synchronized Result cancel(String id) {
        Entry entry = entries.get(id);
        if (entry == null) return unknown(id);
        if (entry.result.state() == State.PREPARED) return finish(entry, State.CANCELLED, "cancelled_before_launch", -1);
        if (entry.result.state() == State.LAUNCHING || entry.result.state() == State.COMPLETED)
            return result(entry.request, entry.result.state(), "too_late_to_cancel", entry.result.targetPackage(), entry.result.taskId());
        return entry.result;
    }

    synchronized void expire() {
        for (Entry entry : entries.values()) {
            if (entry.result != null && (entry.result.state() == State.PREPARED || entry.result.state() == State.LAUNCHING)
                    && clock.getAsLong() - entry.created > 15000) finish(entry, State.FAILED, "request_timeout", -1);
        }
    }

    synchronized void close() {
        closed = true;
        for (Entry entry : entries.values()) {
            if (entry.result.state() == State.PREPARED || entry.result.state() == State.LAUNCHING)
                finish(entry, State.FAILED, "session_revoked", -1);
        }
        endpoint.close();
    }
    private boolean live() { return !closed && authorized.getAsBoolean(); }
    private Result finish(Entry entry, State state, String code, int task) {
        Result value = set(entry, state, code, task);
        if (entry.prepared != null) { endpoint.release(entry.prepared); entry.prepared = null; }
        return value;
    }
    private Result set(Entry entry, State state, String code, int task) {
        String target = entry.prepared == null ? "" : entry.prepared.targetPackage();
        entry.result = result(entry.request, state, code, target, task); return entry.result;
    }
    private static Result result(AppRelayRequest r, State s, String c, String target, int task) { return new Result(r.requestId(), s, c, target, task); }
    private static Result unknown(String id) { return new Result(id, State.FAILED, "unknown_request", "", -1); }
    static String code(Exception error) {
        if (error instanceof SecurityException) return "permission_denied";
        String message = error.getMessage();
        return message != null && message.matches("[a-z_]{1,64}") ? message : "native_execution_failed";
    }
}
