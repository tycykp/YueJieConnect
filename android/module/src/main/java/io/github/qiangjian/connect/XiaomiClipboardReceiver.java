package io.github.qiangjian.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Typed in-process receiver; only the authenticated public bridge may dispatch into this object. */
final class XiaomiClipboardReceiver {
    record Result(String session, String request, int utf8Bytes, String error) {
        boolean applied() { return error == null; }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final XiaomiClipboardEndpoint endpoint;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final OplusClipboardTasks tasks = new OplusClipboardTasks();
    private final Map<OplusClipboardTasks.Key, CompletableFuture<Result>> results = new HashMap<>();
    private String session;
    private volatile boolean closed;

    XiaomiClipboardReceiver(Context context, ClassLoader loader, BooleanSupplier allowed,
                            Supplier<JSONObject> projection) throws ReflectiveOperationException {
        this.context = context;
        this.endpoint = new XiaomiClipboardEndpoint(loader);
        this.allowed = allowed;
        this.projection = projection;
    }

    CompletableFuture<Result> execute(String expectedSession, String request, String text, long deadlineElapsedMs) {
        CompletableFuture<Result> reply = new CompletableFuture<>();
        main.post(() -> {
            synchronizeSession();
            if (!current(expectedSession)) { reply.complete(new Result(expectedSession, request, 0, "stale_session")); return; }
            final byte[] encoded;
            final OplusClipboardTasks.Accepted accepted;
            try {
                encoded = OplusClipboardCodec.encode(text);
                accepted = tasks.accept(expectedSession, request, digest(encoded), deadlineElapsedMs, SystemClock.elapsedRealtime());
            } catch (IllegalArgumentException | IllegalStateException error) {
                reply.complete(new Result(expectedSession, request, 0, error.getMessage())); return;
            }
            OplusClipboardTasks.Key key = accepted.permit().key();
            CompletableFuture<Result> previous = results.get(key);
            if (previous != null) { previous.thenAccept(reply::complete); return; }
            // Non-NEW admissions must have a retained outcome/future. Missing state fails closed.
            if (accepted.admission() != OplusClipboardTasks.Admission.NEW) {
                reply.complete(new Result(expectedSession, request, 0, "executor_state_unavailable")); return;
            }
            results.put(key, reply);
            // Separate acceptance and execution turns so expiry/cancel/revocation can win the queue.
            main.post(() -> apply(accepted.permit(), text, encoded.length, reply));
        });
        return reply;
    }

    private void apply(OplusClipboardTasks.Permit permit, String text, int bytes, CompletableFuture<Result> reply) {
        String expected = permit.key().session(), request = permit.key().request();
        synchronizeSession();
        if (!current(expected)) { reply.complete(new Result(expected, request, 0, "stale_session")); return; }
        if (!tasks.claim(permit, SystemClock.elapsedRealtime())) {
            String error = tasks.error(permit);
            reply.complete(new Result(expected, request, 0, error == null ? "executor_state_unavailable" : error)); return;
        }
        String error = null;
        try { endpoint.writeText(context, text); }
        catch (SecurityException denied) { error = "permission_denied"; }
        catch (ReflectiveOperationException failure) { error = "native_clipboard_failed"; }
        catch (RuntimeException failure) { error = "clipboard_write_unverified"; }
        if (!current(expected)) error = "stale_session";
        if (!tasks.complete(permit, error)) error = "stale_session";
        reply.complete(new Result(expected, request, error == null ? bytes : 0, error));
    }

    CompletableFuture<Boolean> cancel(String expectedSession, String request) {
        CompletableFuture<Boolean> reply = new CompletableFuture<>();
        main.post(() -> {
            boolean cancelled = current(expectedSession) && tasks.cancel(expectedSession, request);
            if (cancelled) {
                CompletableFuture<Result> result = results.get(new OplusClipboardTasks.Key(expectedSession, request));
                if (result != null) result.complete(new Result(expectedSession, request, 0, "cancelled"));
            }
            reply.complete(cancelled);
        });
        return reply;
    }

    void sessionChanged() { main.post(this::synchronizeSession); }
    private void synchronizeSession() {
        JSONObject p = projection.get();
        String next = closed || !allowed.getAsBoolean() || p == null ? null : p.optString("session_id", null);
        if (java.util.Objects.equals(session, next)) return;
        for (var item : results.entrySet()) item.getValue().complete(new Result(item.getKey().session(), item.getKey().request(), 0, "stale_session"));
        results.clear(); tasks.session(next); session = next;
    }

    private boolean current(String expectedSession) {
        JSONObject p = projection.get();
        return !closed && allowed.getAsBoolean() && p != null && expectedSession != null
                && expectedSession.matches("[a-f0-9]{64}") && expectedSession.equals(p.optString("session_id"));
    }
    private static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable", impossible); }
    }
    void close() { closed = true; sessionChanged(); }
}
