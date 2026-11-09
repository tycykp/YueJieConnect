package io.github.yuejie.connect;

import android.content.ClipData;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Independent, unpublished rich receive operation. Public routing must authenticate the actual
 * peer/session and grant HTML explicitly before invoking it. No automatic file consent here. */
final class ClipboardRichReceiver implements AutoCloseable {
    record Result(String peer, String session, String request, String mime, int utf8Bytes, String error) {
        boolean applied() { return error == null; }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClipboardNativeContent endpoint;
    private final ClipboardEchoGuard echoes;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private final OplusClipboardTasks tasks = new OplusClipboardTasks();
    private final Map<OplusClipboardTasks.Key, CompletableFuture<Result>> results = new HashMap<>();
    private String peer, session;
    private volatile boolean closed;

    ClipboardRichReceiver(ClipboardNativeContent endpoint, ClipboardEchoGuard echoes,
            BooleanSupplier allowed, Supplier<JSONObject> projection) {
        this.endpoint = Objects.requireNonNull(endpoint); this.echoes = Objects.requireNonNull(echoes);
        this.allowed = Objects.requireNonNull(allowed); this.projection = Objects.requireNonNull(projection);
    }

    CompletableFuture<Result> executeHtml(String expectedPeer, String expectedSession, String request,
            ClipboardRichContent.Html html, long deadlineElapsedMs) {
        Objects.requireNonNull(html);
        CompletableFuture<Result> reply = new CompletableFuture<>();
        main.post(() -> {
            synchronizeSession();
            if (!current(expectedPeer, expectedSession)) { reply.complete(error(expectedPeer, expectedSession, request, "stale_session")); return; }
            final OplusClipboardTasks.Accepted accepted;
            try { accepted = tasks.accept(expectedSession, request, html.digest(), deadlineElapsedMs, SystemClock.elapsedRealtime()); }
            catch (IllegalArgumentException | IllegalStateException invalid) {
                reply.complete(error(expectedPeer, expectedSession, request, invalid.getMessage())); return;
            }
            var key = accepted.permit().key();
            var previous = results.get(key);
            if (previous != null) { previous.thenAccept(reply::complete); return; }
            if (accepted.admission() != OplusClipboardTasks.Admission.NEW) {
                reply.complete(error(expectedPeer, expectedSession, request, "executor_state_unavailable")); return;
            }
            results.put(key, reply);
            main.post(() -> applyHtml(expectedPeer, accepted.permit(), html, reply));
        });
        return reply;
    }

    private void applyHtml(String expectedPeer, OplusClipboardTasks.Permit permit, ClipboardRichContent.Html html,
            CompletableFuture<Result> reply) {
        String expected = permit.key().session(), request = permit.key().request();
        synchronizeSession();
        if (!current(expectedPeer, expected)) { reply.complete(error(expectedPeer, expected, request, "stale_session")); return; }
        if (!tasks.claim(permit, SystemClock.elapsedRealtime())) {
            String reason = tasks.error(permit);
            reply.complete(error(expectedPeer, expected, request, reason == null ? "executor_state_unavailable" : reason)); return;
        }
        String failure = null;
        echoes.armHtml(expected, html);
        try { ClipData actual = endpoint.writeHtml(html); echoes.seal(actual); }
        catch (SecurityException denied) { failure = "permission_denied"; }
        catch (ReflectiveOperationException nativeFailure) { failure = "native_clipboard_failed"; }
        catch (RuntimeException unverified) { failure = "clipboard_write_unverified"; }
        if (failure != null) echoes.clear();
        if (!current(expectedPeer, expected)) failure = "stale_session";
        if (!tasks.complete(permit, failure)) failure = "stale_session";
        reply.complete(new Result(expectedPeer, expected, request, "text/html", failure == null ? html.utf8Bytes() : 0, failure));
    }

    CompletableFuture<Boolean> cancel(String expectedPeer, String expectedSession, String request) {
        CompletableFuture<Boolean> reply = new CompletableFuture<>();
        main.post(() -> {
            boolean cancelled = current(expectedPeer, expectedSession) && tasks.cancel(expectedSession, request);
            if (cancelled) {
                var result = results.get(new OplusClipboardTasks.Key(expectedSession, request));
                if (result != null) result.complete(error(expectedPeer, expectedSession, request, "cancelled"));
            }
            reply.complete(cancelled);
        });
        return reply;
    }
    void sessionChanged() { main.post(this::synchronizeSession); }
    private void synchronizeSession() {
        JSONObject p = projection.get();
        String next = closed || !allowed.getAsBoolean() || p == null ? null : p.optString("session_id", null);
        String nextPeer = next == null ? null : p.optString("id", null);
        if (Objects.equals(session, next) && Objects.equals(peer, nextPeer)) return;
        for (var item : results.entrySet()) item.getValue().complete(error(peer, item.getKey().session(), item.getKey().request(), "stale_session"));
        results.clear(); tasks.session(null); tasks.session(next); echoes.clear(); session = next; peer = nextPeer;
    }
    private boolean current(String expectedPeer, String expectedSession) {
        JSONObject p = projection.get();
        return !closed && allowed.getAsBoolean() && p != null && expectedPeer != null && !expectedPeer.isBlank()
                && expectedPeer.equals(p.optString("id")) && expectedSession != null
                && expectedSession.matches("[0-9a-f]{64}") && expectedSession.equals(p.optString("session_id"));
    }
    private static Result error(String peer, String session, String request, String error) {
        return new Result(peer, session, request, "text/html", 0, error);
    }
    @Override public void close() { closed = true; sessionChanged(); }
}
