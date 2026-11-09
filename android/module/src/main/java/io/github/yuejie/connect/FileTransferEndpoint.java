package io.github.yuejie.connect;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Asynchronous OEM-process executor. Registration and capability policy belong to native-pairing. */
public final class FileTransferEndpoint implements AutoCloseable {
    public interface Completion { void success(JSONObject data); void fail(String code); }
    private record Request(String session, String request) {}
    private static final class Work {
        final FileTransferEngine.Key key;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long deadline;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Completion completion;
        volatile JSONObject result;
        volatile Request request;
        Work(FileTransferEngine.Key key, long deadline) { this.key = key; this.deadline = deadline; }
    }
    private final FileTransferEngine.SessionGate sessions;
    private final LongSupplier clock;
    private final FileTransferEngine engine;
    private final FileTransferProtocol protocol;
    private final ConcurrentHashMap<Request, Work> work = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FileTransferEngine.Key, CompletionStage<Void>> nativeResults = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> { Thread t = new Thread(task, "yj-file-executor"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "yj-file-expiry"); t.setDaemon(true); return t;
    });
    private volatile Work running;
    private volatile boolean closed;

    public FileTransferEndpoint(Context context, FileTransferEngine.SessionGate authenticatedSession) throws IOException {
        this(executorDirectory(context), authenticatedSession, SystemClock::elapsedRealtime);
    }

    private static File executorDirectory(Context context) {
        String pkg = context.getPackageName();
        if (!pkg.equals("com.oplus.linker") && !pkg.equals("com.xiaomi.mirror")) throw new SecurityException("invalid_executor_context");
        return new File(context.getCacheDir(), "yj-file-in-" + android.os.Process.myPid());
    }

    FileTransferEndpoint(File privateDirectory, FileTransferEngine.SessionGate authenticatedSession,
                         LongSupplier monotonicClock) throws IOException {
        sessions = authenticatedSession;
        clock = monotonicClock;
        engine = new FileTransferEngine(privateDirectory, this::current, clock);
        protocol = new FileTransferProtocol(engine);
        timer.scheduleWithFixedDelay(this::sessionChanged, 5, 5, TimeUnit.SECONDS);
    }

    /** Local receive UI/native approval only. This method is intentionally absent from execute(). */
    public CompletableFuture<Void> authorize(FileTransferEngine.Key key, FileTransferEngine.Offer offer,
                                              FileTransferEngine.Sink localNewDestination) {
        return authorizeTracked(key, offer, localNewDestination).thenApply(ignored -> null);
    }

    public CompletableFuture<FileTransferEngine.Authorization> authorizeTracked(FileTransferEngine.Key key,
            FileTransferEngine.Offer offer, FileTransferEngine.Sink localNewDestination) {
        return authorizeTracked(key, offer, localNewDestination, CompletableFuture.completedFuture(null));
    }

    /** Native drag receivers hold the wire commit ACK until their real consumer reports a result.
     * Local engine commit completion still resolves first, so provider open never deadlocks on it. */
    public CompletableFuture<FileTransferEngine.Authorization> authorizeTracked(FileTransferEngine.Key key,
            FileTransferEngine.Offer offer, FileTransferEngine.Sink localNewDestination, CompletionStage<Void> nativeResult) {
        CompletableFuture<FileTransferEngine.Authorization> result = new CompletableFuture<>();
        try { worker.execute(() -> {
            try {
                if (nativeResult == null) throw new IOException("native_file_result_required");
                FileTransferEngine.Authorization authorization = engine.authorizeTracked(key, offer, localNewDestination);
                nativeResults.keySet().removeIf(k -> !engine.retains(k));
                nativeResults.put(key, nativeResult);
                authorization.committed().whenComplete((receipt, error) -> { if (error != null) nativeResults.remove(key); });
                result.complete(authorization);
            }
            catch (Exception error) { result.completeExceptionally(error); }
        }); } catch (RuntimeException busy) { result.completeExceptionally(busy); }
        return result;
    }

    /** Local owner release. Remote cancellation still uses the authenticated file_cancel operation. */
    public CompletableFuture<Void> revoke(FileTransferEngine.Key key) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { worker.execute(() -> {
            try {
                engine.cancel(key);
                CompletionStage<Void> old = nativeResults.get(key);
                if (old != null && !old.toCompletableFuture().isDone())
                    nativeResults.put(key, CompletableFuture.failedFuture(new IOException("file_cancelled")));
                if (!engine.retains(key)) nativeResults.remove(key);
                for (Work item : work.values()) if (item.key.equals(key) && item.completion != null)
                    finishDeferred(item, new IOException("file_cancelled"));
                result.complete(null);
            }
            catch (Exception error) { result.completeExceptionally(error); }
        }); } catch (RuntimeException busy) { result.completeExceptionally(busy); }
        return result;
    }

    /** Matches BridgeClient task fields. Caller adapts its TaskCompletion to this Completion. */
    public void execute(JSONObject task, Completion completion) {
        final JSONObject payload;
        final String operation;
        final Request request;
        final Work item;
        try {
            operation = task.getString("operation");
            if (!FileTransferProtocol.OPERATIONS.contains(operation)) throw new IOException("invalid_file_operation");
            payload = new JSONObject(task.getJSONObject("payload").toString());
            String session = task.getString("session_id");
            request = new Request(session, task.getString("request_id"));
            item = new Work(new FileTransferEngine.Key(task.getString("projection_id"), session,
                    FileTransferProtocol.string(payload, "transfer")), task.getLong("deadline_elapsed_ms"));
            if (closed || !sessions.current(item.key)) throw new IOException("stale_session");
            if (clock.getAsLong() >= item.deadline) throw new IOException("request_timeout");
            if (work.putIfAbsent(request, item) != null) throw new IOException("duplicate_file_request");
        } catch (Exception invalid) { completion.fail(code(invalid)); return; }
        try {
            worker.execute(() -> {
                running = item;
                try {
                    if (item.cancelled.get()) throw new IOException("file_cancelled");
                    if (clock.getAsLong() >= item.deadline) throw new IOException("request_timeout");
                    if (!current(item.key)) throw new IOException("stale_session");
                    JSONObject result = protocol.execute(item.key.peer(), item.key.session(), operation, payload);
                    if (!operation.equals("file_commit") && item.cancelled.get()) throw new IOException("file_cancelled");
                    if (closed || !sessions.current(item.key)) throw new IOException("stale_session");
                    if (clock.getAsLong() >= item.deadline) throw new IOException("request_timeout");
                    CompletionStage<Void> nativeResult = nativeResults.get(item.key);
                    if (operation.equals("file_commit") && nativeResult != null) {
                        item.result = result; item.request = request; item.completion = completion;
                        nativeResult.whenComplete((ignored, error) -> finishDeferred(item, error));
                    } else { item.finished.set(true); completion.success(result); }
                } catch (Exception error) {
                    if (item.finished.compareAndSet(false, true)) completion.fail(code(error));
                }
                finally {
                    // current() still sees cancellation/deadline while cleanup runs.
                    try { engine.maintain(); } catch (IOException error) { logCleanup(); }
                    nativeResults.keySet().removeIf(key -> !engine.retains(key));
                    running = null;
                    if (item.completion == null || item.finished.get()) work.remove(request, item);
                }
            });
        } catch (RuntimeException busy) { work.remove(request, item); completion.fail("file_busy"); }
    }

    public void cancel(String sessionId, String requestId) {
        Work item = work.get(new Request(sessionId, requestId));
        if (item != null) {
            item.cancelled.set(true);
            // Engine commit may already have handed bytes to a native consumer. Cancellation is
            // then a request, not proof of rollback: await the real native result or the deadline.
        }
    }

    public void sessionChanged() {
        if (closed) return;
        try { worker.execute(() -> {
            try { engine.maintain(); } catch (IOException error) { logCleanup(); }
            for (Work item : work.values()) if (item.completion != null && (
                    !sessions.current(item.key) || clock.getAsLong() >= item.deadline))
                finishDeferred(item, new IOException("request_timeout"));
            nativeResults.keySet().removeIf(key -> !engine.retains(key));
        }); }
        catch (RuntimeException busy) { /* bounded worker is busy; next task/expiry repeats cleanup */ }
    }

    private boolean current(FileTransferEngine.Key key) {
        if (closed || !sessions.current(key)) return false;
        Work item = running;
        return item == null || !item.key.equals(key)
                || (!item.cancelled.get() && clock.getAsLong() < item.deadline);
    }

    private void finishDeferred(Work item, Throwable error) {
        // Native callback, provider read, timer and cancellation may race. Exactly one wire result.
        if (!item.finished.compareAndSet(false, true)) return;
        try {
            if (closed || !sessions.current(item.key)) item.completion.fail("stale_session");
            else if (clock.getAsLong() >= item.deadline) item.completion.fail("request_timeout");
            else if (error != null) item.completion.fail("native_file_receive_failed");
            else item.completion.success(item.result);
        } finally { work.remove(item.request, item); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; timer.shutdownNow();
        for (Work item : work.values()) item.cancelled.set(true);
        for (Work item : work.values()) if (item.completion != null) finishDeferred(item, new IOException("stale_session"));
        nativeResults.clear();
        // Never do filesystem/native cleanup on the main thread.
        worker.shutdown();
        Thread cleanup = new Thread(() -> {
            try {
                while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { }
                engine.close();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException error) { logCleanup(); }
        }, "yj-file-cleanup");
        cleanup.setDaemon(true); cleanup.start();
    }

    private static String code(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z_]{3,64}") ? message : "file_io_failed";
    }
    private static void logCleanup() { android.util.Log.w("YueJieFiles", "file_cleanup_pending"); }
}
