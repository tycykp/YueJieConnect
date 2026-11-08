package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static io.github.qiangjian.connect.TelephonySyncController.*;
import static io.github.qiangjian.connect.TelephonySyncWire.*;

/** Async typed task router. A result is produced only by the actual session's native delegate. */
final class TelephonySyncEndpoint implements AutoCloseable {
    interface Calls {
        CompletableFuture<Result> execute(Command command, long deadlineElapsedMs);
        void cancel(Session session, String request);
    }
    interface States {
        CompletableFuture<TelephonySyncRelay.Receipt> accept(Session session, Snapshot snapshot, long deadlineElapsedMs);
        void cancel(Session session, String call, long revision);
    }
    interface Sms {
        CompletableFuture<TelephonySyncSmsNative.Result> apply(TelephonySyncSmsNative.Command command, long deadlineElapsedMs);
        void cancel(Session session, String request);
    }
    private static final class Work {
        final String operation;
        final Map<String, Object> payload;
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        final long deadline;
        Work(String operation, Map<String, Object> payload, long deadline) { this.operation = operation; this.payload = payload; this.deadline = deadline; }
    }
    private final Session session;
    private final Calls calls;
    private final States states;
    private final Sms sms;
    private final BooleanSupplier ready;
    private final LongSupplier clock;
    private final Runnable closeNative;
    private final Map<String, Work> requests = new LinkedHashMap<>();
    private boolean closed;
    TelephonySyncEndpoint(Session session, Calls calls, States states, Sms sms, BooleanSupplier ready,
                          LongSupplier clock, Runnable closeNative) {
        this.session = java.util.Objects.requireNonNull(session); this.calls = calls; this.states = states; this.sms = sms;
        this.ready = java.util.Objects.requireNonNull(ready); this.clock = java.util.Objects.requireNonNull(clock);
        this.closeNative = java.util.Objects.requireNonNull(closeNative);
    }
    Session session() { return session; }
    synchronized Set<String> operations() {
        if (!current()) return Set.of();
        java.util.HashSet<String> result = new java.util.HashSet<>();
        if (calls != null) result.add(CALL_CONTROL); if (states != null) result.add(CALL_STATE);
        if (sms != null) result.add(SMS_STATUS); return Set.copyOf(result);
    }
    synchronized CompletableFuture<Map<String, Object>> execute(Session source, String operation, String request,
                                                                Map<String, Object> payload, long deadlineElapsedMs) {
        if (!current() || !session.equals(source)) return failure("session_invalid");
        if (request == null || !request.matches("[A-Za-z0-9_-]{1,96}") || payload == null || !OPERATIONS.contains(operation))
            return failure("invalid_payload");
        Work previous = requests.get(request);
        if (previous != null) return previous.operation.equals(operation) && previous.payload.equals(payload)
                ? previous.future : failure("request_conflict");
        if (deadlineElapsedMs <= clock.getAsLong()) return failure("deadline_expired");
        if (requests.size() >= 256 || requests.values().stream().filter(w -> !w.future.isDone()).count() >= 32)
            return failure("request_capacity");
        if (!operations().contains(operation)) return failure("native_unavailable");
        final Work work;
        try { work = new Work(operation, Map.copyOf(payload), Math.min(deadlineElapsedMs, clock.getAsLong() + 10_000)); }
        catch (RuntimeException invalid) { return failure("invalid_payload"); }
        requests.put(request, work);
        try {
            CompletableFuture<Map<String, Object>> nativeResult;
            switch (operation) {
                case CALL_CONTROL -> {
                    Command command = control(session, request, work.payload);
                    nativeResult = calls.execute(command, work.deadline).thenApply(result -> controlReceipt(command, result));
                }
                case CALL_STATE -> {
                    Snapshot snapshot = state(work.payload);
                    nativeResult = states.accept(session, snapshot, work.deadline).thenApply(result -> stateReceipt(snapshot, result));
                }
                case SMS_STATUS -> {
                    TelephonySyncSmsNative.Command command = sms(session, request, work.payload);
                    nativeResult = sms.apply(command, work.deadline).thenApply(result -> smsReceipt(command, result));
                }
                default -> throw new Failure("invalid_payload");
            }
            nativeResult.whenComplete((result, failure) -> completed(work, result, failure));
        } catch (RuntimeException failure) { completed(work, null, failure); }
        return work.future;
    }
    private synchronized void completed(Work work, Map<String, Object> result, Throwable failure) {
        if (work.future.isDone()) return;
        if (!current()) { work.future.completeExceptionally(new Failure("session_invalid")); return; }
        if (clock.getAsLong() >= work.deadline) { work.future.completeExceptionally(new Failure("outcome_unknown")); return; }
        if (failure != null) {
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            work.future.completeExceptionally(new Failure(cause instanceof Failure f ? f.code : "outcome_unknown"));
        } else if (result == null) work.future.completeExceptionally(new Failure("invalid_result"));
        else work.future.complete(result);
    }
    synchronized void cancel(Session source, String request) {
        if (!session.equals(source)) return;
        Work work = requests.get(request); if (work == null || work.future.isDone()) return;
        // Dispatch may already have crossed the native boundary. Cancellation never claims rollback.
        work.future.completeExceptionally(new Failure("outcome_unknown"));
        cancelNative(request, work);
    }
    private void cancelNative(String request, Work work) {
        try {
            switch (work.operation) {
                case CALL_CONTROL -> calls.cancel(session, request);
                case CALL_STATE -> { Snapshot s = state(work.payload); states.cancel(session, s.call(), s.revision()); }
                case SMS_STATUS -> sms.cancel(session, request);
            }
        } catch (RuntimeException ignored) { /* Waiting is withdrawn; no invented native cancellation ACK. */ }
    }
    synchronized void tick() {
        if (!current()) { close(); return; }
        for (var entry : new java.util.ArrayList<>(requests.entrySet())) {
            Work work = entry.getValue();
            if (!work.future.isDone() && clock.getAsLong() >= work.deadline) cancel(session, entry.getKey());
        }
    }
    private boolean current() {
        if (closed) return false;
        try { if (ready.getAsBoolean()) return true; } catch (RuntimeException ignored) { }
        close(); return false; // A withdrawn native executor cannot revive this session's task ledger.
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (var entry : new java.util.ArrayList<>(requests.entrySet())) if (!entry.getValue().future.isDone()) {
            entry.getValue().future.completeExceptionally(new Failure("session_invalid")); cancelNative(entry.getKey(), entry.getValue());
        }
        try { closeNative.run(); } catch (RuntimeException ignored) { /* Native owners keep their cleanup evidence. */ }
    }
    private static CompletableFuture<Map<String, Object>> failure(String code) { return CompletableFuture.failedFuture(new Failure(code)); }
}
