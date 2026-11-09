package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One ordered native input route. Queue acceptance is never a native result. Payloads must use
 * the authenticated, ordered control channel; capture consent alone does not authorize input. */
public final class RemoteInputSender implements AutoCloseable {
    public sealed interface Payload permits Desktop, Text { }
    public record Desktop(XiaomiScreenInputCodec.Event event) implements Payload {
        public Desktop { Objects.requireNonNull(event); }
        @Override public String toString() { return "DesktopInput"; }
    }
    public record Text(XiaomiScreenInputCodec.TextEvent event) implements Payload {
        public Text { Objects.requireNonNull(event); }
        @Override public String toString() { return "ImeInput"; }
    }
    public record Command(RemoteInputSession.Scope scope, long sequence, Payload payload) {
        public Command { Objects.requireNonNull(scope); Objects.requireNonNull(payload); if (sequence <= 0) throw new IllegalArgumentException(); }
        @Override public String toString() { return "RemoteInputCommand"; }
    }
    public interface Transport {
        /** Nonblocking FIFO enqueue. Completion must be the verified actual remote target result
         * for this same scope/sequence. remainingMs is a local budget, never a remote clock value. */
        CompletionStage<RemoteInputSession.Result> send(Command command, long remainingMs);
        /** Nonblocking revoke; receiver releases its held inputs on its original native worker. */
        void revoke(String reason);
    }
    /** An attempted operation may already have executed. Do not retry a click/text on this error. */
    public static final class Unconfirmed extends IllegalStateException {
        Unconfirmed(String reason) { super(reason); }
    }
    private record Pending(long deadline, CompletableFuture<RemoteInputSession.Result> result) { }
    private final RemoteInputSession.Scope desktop, ime;
    private final BooleanSupplier current;
    private final LongSupplier clock;
    private final Transport transport;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private long nextSequence;
    private boolean closed;
    public RemoteInputSender(RemoteInputSession.Scope desktopFocus, RemoteInputSession.Scope actualImeFocus,
            BooleanSupplier currentSessionPeerScreenAndInputAllowed, LongSupplier elapsedRealtimeMs, Transport transport) {
        desktop = Objects.requireNonNull(desktopFocus); ime = actualImeFocus;
        if (ime != null && (!ime.sessionId().equals(desktop.sessionId()) || ime.streamId() != desktop.streamId()))
            throw new IllegalArgumentException("native_input_focus_stream_mismatch");
        current = Objects.requireNonNull(currentSessionPeerScreenAndInputAllowed);
        clock = Objects.requireNonNull(elapsedRealtimeMs); this.transport = Objects.requireNonNull(transport);
    }
    public RemoteInputSession.Scope desktopScope() { return desktop; }
    public RemoteInputSession.Scope imeScope() { return ime; }
    public CompletionStage<RemoteInputSession.Result> desktop(XiaomiScreenInputCodec.Event event) {
        return submit(desktop, new Desktop(event));
    }
    public CompletionStage<RemoteInputSession.Result> text(XiaomiScreenInputCodec.TextEvent event) {
        Objects.requireNonNull(event);
        if (ime == null) return CompletableFuture.failedFuture(new RejectedExecutionException("native_ime_focus_unavailable"));
        return submit(ime, new Text(event));
    }
    private synchronized CompletionStage<RemoteInputSession.Result> submit(RemoteInputSession.Scope scope, Payload payload) {
        if (!live()) return CompletableFuture.failedFuture(new RejectedExecutionException("native_input_route_closed"));
        if (pending.size() >= 32 || nextSequence == Long.MAX_VALUE) {
            terminate("native_input_backpressure");
            return CompletableFuture.failedFuture(new RejectedExecutionException("native_input_backpressure"));
        }
        long sequence = ++nextSequence, deadline = clock.getAsLong() + 2_000;
        CompletableFuture<RemoteInputSession.Result> result = new CompletableFuture<>();
        pending.put(sequence, new Pending(deadline, result));
        try {
            CompletionStage<RemoteInputSession.Result> response = transport.send(new Command(scope, sequence, payload), 2_000);
            if (response == null) terminate("native_input_result_unconfirmed");
            else response.whenComplete((nativeResult, error) -> completed(sequence, nativeResult, error));
        } catch (RuntimeException error) { terminate("native_input_result_unconfirmed"); }
        return result;
    }
    private synchronized void completed(long sequence, RemoteInputSession.Result nativeResult, Throwable error) {
        Pending request = pending.get(sequence);
        if (request == null) return;
        if (error != null || nativeResult == null || !live() || clock.getAsLong() >= request.deadline) {
            terminate("native_input_result_unconfirmed"); return;
        }
        pending.remove(sequence); request.result.complete(nativeResult);
        // Any native failure may leave a modifier/down partially applied. Revoke; never resend it.
        if (nativeResult != RemoteInputSession.Result.ACCEPTED) terminate("native_input_target_rejected");
    }
    private boolean live() {
        if (closed) return false;
        boolean valid;
        try { valid = current.getAsBoolean(); } catch (RuntimeException error) { valid = false; }
        if (!valid) { terminate("native_input_authority_revoked"); return false; }
        long now = clock.getAsLong();
        for (Pending request : pending.values()) if (now >= request.deadline) {
            terminate("native_input_result_unconfirmed"); return false;
        }
        return true;
    }
    private void terminate(String reason) {
        if (closed) return;
        closed = true;
        var snapshot = new ArrayList<>(pending.values()); pending.clear();
        try { transport.revoke(reason); } catch (RuntimeException ignored) { }
        for (Pending request : snapshot) request.result.completeExceptionally(new Unconfirmed(reason));
    }
    public synchronized boolean current() { return live(); }
    public synchronized int outstanding() { return pending.size(); }
    /** Call from the existing public lifecycle timer, including while the remote side is silent. */
    public synchronized void tick() { live(); }
    @Override public synchronized void close() { terminate("native_input_closed"); }
}
