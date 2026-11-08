package io.github.qiangjian.connect;

import android.content.ClipData;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Owns a single real local file-receive lease after its native owner hands it off.
 * No destination creation/authorize method and no remotely supplied URI are accepted here.
 * Keep alive after success: paste needs this temporary file until revocation/lease expiry/close. */
final class ClipboardUriReceiver implements AutoCloseable {
    record Result(String peer, String session, String request, String transfer, long size, String sha256, String error) {
        boolean applied() { return error == null; }
    }
    private final FileTransferReceiveLease<Uri> lease;
    private final ClipboardNativeContent endpoint;
    private final ClipboardEchoGuard echoes;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread t = new Thread(task, "qj-clipboard-uri"); t.setDaemon(true); return t; });
    private final AtomicBoolean maintaining = new AtomicBoolean();
    private final CompletableFuture<Result> delivery = new CompletableFuture<>();
    private final Runnable expiry = this::maintain;
    private String request;
    private long deadline;
    private volatile boolean closed;
    private volatile boolean disposed;
    private boolean applying;
    private final Runnable timeout = () -> { if (!applying && !delivery.isDone()) fail("request_timeout"); };

    /** Transfers lease cleanup ownership to this instance. Caller enforces at most four leases
     * and supplies a genuinely selected native receive decision, not an RPC-generated grant. */
    ClipboardUriReceiver(FileTransferReceiveLease<Uri> lease, ClipboardNativeContent endpoint,
            ClipboardEchoGuard echoes, BooleanSupplier allowed, Supplier<JSONObject> projection) {
        this.lease = Objects.requireNonNull(lease); this.endpoint = Objects.requireNonNull(endpoint);
        this.echoes = Objects.requireNonNull(echoes); this.allowed = Objects.requireNonNull(allowed);
        this.projection = Objects.requireNonNull(projection);
        delivery.whenComplete((result, failure) -> main.removeCallbacks(timeout));
        main.post(expiry);
    }

    /** Waits for the actual local engine commit. Returns a copy: cancelling the client future
     * cannot forge/overwrite an applied result; use cancel() to race the queued native write. */
    CompletableFuture<Result> execute(String request, long deadlineElapsedMs) {
        CompletableFuture<Result> reply = new CompletableFuture<>();
        main.post(() -> {
            if (this.request != null) {
                if (!this.request.equals(request)) reply.complete(result(request, "clipboard_uri_already_started"));
                else delivery.thenAccept(reply::complete);
                return;
            }
            this.request = request; deadline = deadlineElapsedMs;
            delivery.thenAccept(reply::complete);
            long now = SystemClock.elapsedRealtime();
            if (request == null || !request.matches("[A-Za-z0-9_:-]{1,128}")
                    || deadline <= now || deadline - now > 30_000) { fail("expired_or_invalid_request"); return; }
            if (!current()) { fail("stale_session"); return; }
            main.postDelayed(timeout, deadline - now);
            lease.committed().whenComplete((receipt, failure) -> {
                if (failure != null || receipt == null || !lease.key().equals(receipt.key())
                        || lease.offer().size() != receipt.size() || !lease.offer().sha256().equals(receipt.sha256())) {
                    main.post(() -> { if (!delivery.isDone()) fail(!current() || !lease.current() ? "stale_session" : "file_receive_failed"); }); return;
                }
                enqueue(() -> {
                    if (delivery.isDone()) return;
                    try {
                        if (!current()) throw new IOException("stale_session");
                        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("request_timeout");
                        // URI I/O and the second full hash always happen off the native main thread.
                        Uri local = lease.claim(lease.key(), lease.offer());
                        main.post(() -> apply(local));
                    } catch (IOException | RuntimeException failureToClaim) {
                        String reason = failureToClaim.getMessage();
                        String code = "stale_session".equals(reason) || "request_timeout".equals(reason) ? reason : "clipboard_uri_claim_failed";
                        main.post(() -> { if (!delivery.isDone()) fail(!current() ? "stale_session" : code); });
                    }
                });
            });
        });
        return reply;
    }
    private void apply(Uri local) {
        if (delivery.isDone()) return;
        if (!current() || !lease.current()) { fail("stale_session"); return; }
        if (SystemClock.elapsedRealtime() >= deadline) { fail("request_timeout"); return; }
        applying = true;
        String failure = null;
        echoes.armUri(lease.key().session(), local, lease.offer().mime());
        try { ClipData actual = endpoint.writeLocalUri(local, lease.offer().mime()); echoes.seal(actual); }
        catch (SecurityException denied) { failure = "permission_denied"; }
        catch (ReflectiveOperationException nativeFailure) { failure = "native_clipboard_failed"; }
        catch (RuntimeException unverified) { failure = "clipboard_write_unverified"; }
        if (failure != null) echoes.clear();
        if (!current() || !lease.current()) failure = "stale_session";
        delivery.complete(result(request, failure));
        applying = false;
        if (failure != null) close();
    }
    CompletableFuture<Boolean> cancel() {
        CompletableFuture<Boolean> reply = new CompletableFuture<>();
        main.post(() -> {
            boolean cancelled = !applying && !delivery.isDone();
            if (cancelled) fail("cancelled");
            reply.complete(cancelled); // A completed native import is never described as rolled back.
        });
        return reply;
    }
    void sessionChanged() {
        main.post(() -> { if (!current()) { if (!delivery.isDone()) fail("stale_session"); else close(); } });
    }
    boolean disposed() { return disposed; }
    private boolean current() {
        try {
            JSONObject p = projection.get();
            return !closed && allowed.getAsBoolean() && p != null && lease.key().peer().equals(p.optString("id"))
                    && lease.key().session().equals(p.optString("session_id"));
        } catch (RuntimeException unavailable) { return false; }
    }
    private Result result(String request, String error) {
        return new Result(lease.key().peer(), lease.key().session(), request, lease.key().transfer(),
                error == null ? lease.offer().size() : 0, error == null ? lease.offer().sha256() : null, error);
    }
    private void fail(String code) { delivery.complete(result(request, code)); close(); }
    private void enqueue(Runnable task) {
        try { worker.execute(task); }
        catch (RuntimeException unavailable) {
            main.post(() -> { if (!delivery.isDone()) fail("clipboard_uri_worker_unavailable"); });
        }
    }
    private void maintain() {
        if (disposed) return;
        if (!maintaining.compareAndSet(false, true)) return;
        enqueue(() -> {
            boolean disposed = false;
            try {
                if (closed || !current()) lease.close(); else lease.maintain();
                disposed = lease.disposed();
                if (disposed && !delivery.isDone()) main.post(() -> fail("stale_session"));
            } catch (IOException cleanup) { /* Retain this exact lease and retry; no private URI/error logging. */ }
            finally {
                maintaining.set(false);
                if (disposed) { this.disposed = true; main.removeCallbacks(expiry); worker.shutdown(); }
                else main.postDelayed(expiry, 5000);
            }
        });
    }
    @Override public void close() {
        if (disposed) return;
        closed = true;
        main.post(() -> {
            if (!delivery.isDone()) delivery.complete(result(request, "stale_session"));
            main.removeCallbacks(expiry); maintain();
        });
    }
}
