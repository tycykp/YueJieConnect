package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static io.github.qiangjian.connect.RemoteInputSession.Result.*;

/** Authenticated inbound input assembly. One FIFO/replay ledger covers desktop and IME input.
 * The control owner verifies the requesting peer and delivers a copied typed command here;
 * this class does not authenticate IPC/wire or infer a focus from a remote payload. */
public final class RemoteInputReceiver implements AutoCloseable {
    public interface Target extends AutoCloseable {
        /** Must enqueue onto the original native desktop/main worker and return immediately.
         * Complete with the actual native execution result, never queue admission. */
        CompletionStage<RemoteInputSession.Result> desktop(RemoteInputSession.Scope scope, long sequence,
                XiaomiScreenInputCodec.Event event, long deadlineElapsedMs);
        CompletionStage<RemoteInputSession.Result> text(RemoteInputSession.Scope scope, long sequence,
                XiaomiScreenInputCodec.TextEvent event, long deadlineElapsedMs);
        /** Called once from any control thread. Fence immediately, enqueue native releases onto
         * each target's original worker and return without waiting for them. Cleanup must still
         * run when consent or focus is gone. */
        @Override void close();
    }
    private record Job(RemoteInputSender.Command command, long deadline,
                       CompletableFuture<RemoteInputSession.Result> result) { }
    private final Object admission = new Object();
    private final RemoteInputSession.Scope desktop, ime;
    private final BooleanSupplier currentRenderedStreamAndInputAllowed;
    private final LongSupplier elapsed;
    private final Target target;
    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(32);
    private final Semaphore capacity = new Semaphore(32);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final Thread worker;
    private long sequence;

    public RemoteInputReceiver(RemoteInputSession.Scope desktopFocus, RemoteInputSession.Scope actualImeFocus,
            BooleanSupplier currentRenderedStreamAndInputAllowed, LongSupplier elapsedRealtimeMs, Target target) {
        desktop = Objects.requireNonNull(desktopFocus); ime = actualImeFocus;
        if (ime != null && (!ime.sessionId().equals(desktop.sessionId()) || ime.streamId() != desktop.streamId()))
            throw new IllegalArgumentException("native_input_focus_stream_mismatch");
        this.currentRenderedStreamAndInputAllowed = Objects.requireNonNull(currentRenderedStreamAndInputAllowed);
        elapsed = Objects.requireNonNull(elapsedRealtimeMs); this.target = Objects.requireNonNull(target);
        worker = new Thread(this::run, "qj-native-input-receiver"); worker.setDaemon(true); worker.start();
    }
    private boolean granted() {
        if (!active.get()) return false;
        try { return currentRenderedStreamAndInputAllowed.getAsBoolean(); }
        catch (RuntimeException error) { return false; }
    }
    /** remainingMs is an authenticated relative budget (1..2000), never the other phone's clock.
     * The native deadline starts at admission and includes waiting behind either input kind. */
    public CompletionStage<RemoteInputSession.Result> receive(RemoteInputSender.Command command, long remainingMs) {
        if (command == null) return CompletableFuture.completedStage(INVALID_PAYLOAD);
        if (!desktop.sessionId().equals(command.scope().sessionId()) || desktop.streamId() != command.scope().streamId())
            return CompletableFuture.completedStage(STALE_SESSION);
        RemoteInputSession.Scope expected = command.payload() instanceof RemoteInputSender.Desktop ? desktop : ime;
        if (expected == null) return CompletableFuture.completedStage(NO_FOCUS);
        if (expected.focusEpoch() != command.scope().focusEpoch()) return CompletableFuture.completedStage(STALE_FOCUS);
        long now = elapsed.getAsLong();
        if (now < 0 || remainingMs < 1 || remainingMs > 2_000 || now > Long.MAX_VALUE - remainingMs)
            return CompletableFuture.failedStage(new IllegalArgumentException("native_input_deadline_invalid"));
        CompletableFuture<RemoteInputSession.Result> result = new CompletableFuture<>();
        boolean overload = false;
        synchronized (admission) {
            if (!active.get()) return CompletableFuture.completedStage(STALE_SESSION);
            if (command.sequence() <= sequence) return CompletableFuture.completedStage(REPLAY);
            sequence = command.sequence(); // One use including rejected native execution; never retry text/click.
            if (!capacity.tryAcquire()) overload = true;
            else if (!queue.offer(new Job(command, now + remainingMs, result))) {
                capacity.release(); overload = true;
            }
        }
        if (overload) {
            result.completeExceptionally(new IllegalStateException("native_input_backpressure")); close();
        }
        return result.minimalCompletionStage();
    }
    private void run() {
        try {
            while (active.get()) {
                if (!granted()) break;
                Job job;
                try { job = queue.poll(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException error) { if (!active.get()) break; else continue; }
                if (job == null) continue;
                try { execute(job); }
                finally { capacity.release(); }
            }
        } finally {
            close();
            cleanup.whenComplete((ignored, error) -> {
                if (error != null) termination.completeExceptionally(new IllegalStateException("native_input_release_unconfirmed"));
                else termination.complete(null);
            });
        }
    }
    private void execute(Job job) {
        if (!granted()) { job.result.complete(DENIED); close(); return; }
        if (elapsed.getAsLong() >= job.deadline) {
            job.result.completeExceptionally(new TimeoutException("native_input_expired_before_execution")); close(); return;
        }
        CompletableFuture<RemoteInputSession.Result> nativeResult = new CompletableFuture<>();
        try {
            RemoteInputSender.Command command = job.command;
            CompletionStage<RemoteInputSession.Result> stage = command.payload() instanceof RemoteInputSender.Desktop d
                    ? target.desktop(command.scope(), command.sequence(), d.event(), job.deadline)
                    : target.text(command.scope(), command.sequence(), ((RemoteInputSender.Text) command.payload()).event(), job.deadline);
            if (stage == null) throw new IllegalStateException();
            stage.whenComplete((result, error) -> {
                if (error != null || result == null) nativeResult.completeExceptionally(new IllegalStateException());
                else nativeResult.complete(result);
            });
            // Wait only on this dedicated assembly worker. Binder/UI/polling and native worker
            // remain independent. A silent target cannot retain a live lease indefinitely.
            RemoteInputSession.Result result;
            while (true) {
                if (nativeResult.isDone()) { result = nativeResult.get(); break; }
                if (!granted()) throw new IllegalStateException();
                long remaining = job.deadline - elapsed.getAsLong();
                if (remaining <= 0) throw new TimeoutException();
                try { result = nativeResult.get(Math.min(50, remaining), TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException error) { /* Recheck local budget and live authority. */ }
            }
            job.result.complete(result);
            if (result != ACCEPTED) close();
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException error) {
            // Dispatch may already have reached the native target. Never return cancelled/denied
            // or retry automatically after losing its result. A known actual result wins the race.
            RemoteInputSession.Result known = null;
            try { if (nativeResult.isDone() && !nativeResult.isCompletedExceptionally()) known = nativeResult.getNow(null); }
            catch (RuntimeException ignored) { }
            if (known != null) job.result.complete(known);
            else job.result.completeExceptionally(new RemoteInputSender.Unconfirmed("native_input_result_unconfirmed"));
            close();
        }
    }
    public CompletionStage<Void> termination() { return termination.minimalCompletionStage(); }
    @Override public void close() {
        List<Job> cancelled = new ArrayList<>();
        synchronized (admission) { active.set(false); queue.drainTo(cancelled); }
        // Fence the downstream targets now, before an in-flight dispatcher can enqueue another
        // native operation. Native cleanup itself is still dispatched to the original worker.
        if (cleanupStarted.compareAndSet(false, true)) {
            try { target.close(); cleanup.complete(null); }
            catch (RuntimeException error) { cleanup.completeExceptionally(new IllegalStateException()); }
        }
        for (Job job : cancelled) { capacity.release(); job.result.complete(STALE_SESSION); }
        if (Thread.currentThread() != worker) worker.interrupt();
    }
}
