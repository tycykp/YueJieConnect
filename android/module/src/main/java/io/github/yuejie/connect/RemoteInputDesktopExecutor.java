package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static io.github.yuejie.connect.RemoteInputSession.Result.*;

/** Bounded native input lane: admission is never reported as execution success.
 * close revokes immediately, cancels queued actions, and releases held input on this same worker.
 * An action already dispatched retains its actual native result, even if close races with it. */
public final class RemoteInputDesktopExecutor implements AutoCloseable {
    public static final int MAX_PENDING = 32;
    public static final long MAX_REMAINING_MS = 2_000;
    private record Job(RemoteInputSession.Scope scope, long sequence, XiaomiScreenInputCodec.Event event,
                       long deadline, CompletableFuture<RemoteInputSession.Result> result) { }
    private final Object admission = new Object();
    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final Semaphore capacity = new Semaphore(MAX_PENDING);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final RemoteInputSession.Scope scope;
    private final BooleanSupplier allowed;
    private final LongSupplier elapsed;
    private final RemoteInputDesktopSession.Target target;
    private final RemoteInputDesktopSession session;
    private final CompletableFuture<RemoteInputDesktopSession.ReleaseResult> terminated = new CompletableFuture<>();
    private final Thread worker;
    private long runningDeadline;

    public RemoteInputDesktopExecutor(RemoteInputSession.Scope scope, BooleanSupplier currentAndInputAllowed,
            LongSupplier elapsedRealtimeMs, LongSupplier uptimeMs, RemoteInputDesktopSession.Target target) {
        this.scope = Objects.requireNonNull(scope); allowed = Objects.requireNonNull(currentAndInputAllowed);
        elapsed = Objects.requireNonNull(elapsedRealtimeMs); this.target = Objects.requireNonNull(target);
        session = new RemoteInputDesktopSession(scope, () -> active.get() && granted(), uptimeMs,
                new RemoteInputDesktopSession.Target() {
                    public boolean hasFocus(long epoch) { return timely() && target.hasFocus(epoch); }
                    public boolean mouse(RemoteInputDesktopSession.Mouse event) { return timely() && target.mouse(event); }
                    public boolean key(RemoteInputDesktopSession.Key event) { return timely() && target.key(event); }
                    public RemoteInputDesktopSession.ReleaseResult release(RemoteInputDesktopSession.Reset reset) {
                        return target.release(reset); // Cleanup alone may outlive the business grant/deadline.
                    }
                });
        worker = new Thread(this::run, "yj-native-desktop-input");
        worker.setDaemon(true); worker.start();
    }
    private boolean granted() {
        try { return allowed.getAsBoolean(); } catch (RuntimeException error) { return false; }
    }
    private boolean timely() { return active.get() && elapsed.getAsLong() < runningDeadline; }
    /** deadlineElapsedMs is computed locally from authenticated task remaining time.
     * The returned stage completes only after the native result, never merely after offer().
     * Cancel the lease via close(); cancelling a derived future cannot undo native input. */
    public CompletionStage<RemoteInputSession.Result> submit(RemoteInputSession.Scope request, long sequence,
            XiaomiScreenInputCodec.Event event, long deadlineElapsedMs) {
        if (request == null || event == null || sequence <= 0) return CompletableFuture.completedStage(INVALID_PAYLOAD);
        if (!scope.sessionId().equals(request.sessionId()) || scope.streamId() != request.streamId())
            return CompletableFuture.completedStage(STALE_SESSION);
        if (scope.focusEpoch() != request.focusEpoch()) return CompletableFuture.completedStage(STALE_FOCUS);
        long now = elapsed.getAsLong();
        if (now < 0 || deadlineElapsedMs < 0 || deadlineElapsedMs <= now || deadlineElapsedMs - now > MAX_REMAINING_MS)
            return CompletableFuture.failedStage(new TimeoutException("input_deadline_invalid"));
        CompletableFuture<RemoteInputSession.Result> result = new CompletableFuture<>();
        boolean overload = false;
        synchronized (admission) {
            if (!active.get()) return CompletableFuture.completedStage(STALE_SESSION);
            if (!capacity.tryAcquire()) overload = true;
            else if (!queue.offer(new Job(request, sequence, event, deadlineElapsedMs, result))) {
                capacity.release(); overload = true;
            }
        }
        if (overload) {
            result.completeExceptionally(new RejectedExecutionException("input_queue_full"));
            close(); // Never drop a release event and leave a button/key held.
        }
        return result.minimalCompletionStage();
    }
    private void run() {
        try {
            while (active.get()) {
                if (!granted() || !target.hasFocus(scope.focusEpoch())) break;
                Job job;
                try { job = queue.poll(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException error) { if (!active.get()) break; else continue; }
                if (job == null) continue;
                try {
                    if (!active.get()) { job.result.complete(STALE_SESSION); continue; }
                    if (elapsed.getAsLong() >= job.deadline) {
                        job.result.completeExceptionally(new TimeoutException("input_expired_before_execution"));
                        close(); continue;
                    }
                    runningDeadline = job.deadline;
                    RemoteInputSession.Result result = job.event.apply(session, job.scope, job.sequence);
                    job.result.complete(result);
                    if (result == NATIVE_REJECTED || result == DENIED || result == NO_FOCUS || result == STALE_SESSION) close();
                } catch (RuntimeException error) {
                    job.result.completeExceptionally(new IllegalStateException("native_input_execution_failed"));
                    close();
                } finally { capacity.release(); }
            }
        } catch (RuntimeException ignored) {
            // Unavailable native focus/authority closes the lane; no payload is logged.
        } finally {
            close(); session.close(); terminated.complete(session.releaseResult());
        }
    }
    public CompletionStage<RemoteInputDesktopSession.ReleaseResult> termination() { return terminated.minimalCompletionStage(); }
    @Override public void close() {
        List<Job> cancelled = new ArrayList<>();
        synchronized (admission) {
            active.set(false); queue.drainTo(cancelled);
        }
        for (Job job : cancelled) { capacity.release(); job.result.complete(STALE_SESSION); }
        if (Thread.currentThread() != worker) worker.interrupt();
    }
}
