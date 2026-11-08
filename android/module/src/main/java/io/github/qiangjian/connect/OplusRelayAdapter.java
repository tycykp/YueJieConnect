package io.github.qiangjian.connect;

import android.content.Context;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Called at an explicitly bound DataBus peer; sendRelayInfo itself broadcasts and has no peer ID. */
final class OplusRelayAdapter implements AutoCloseable {
    record Submission(String requestId, CompletableFuture<AppRelayTasks.Result> result) {}
    private static final class Pending {
        final String id;
        final Object callback;
        final BooleanSupplier current;
        final BooleanSupplier cancelRequested;
        final CompletableFuture<AppRelayTasks.Result> future = new CompletableFuture<>();
        volatile boolean cancelled;
        boolean delivered;
        Pending(String id, Object callback, BooleanSupplier current, BooleanSupplier cancelRequested) {
            this.id = id; this.callback = callback; this.current = current; this.cancelRequested = cancelRequested;
        }
    }
    private final Context context;
    private final ClassLoader loader;
    private final AppRelaySender sender;
    private final Method success, failure;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "qj-oplus-relay-source"); t.setDaemon(true); return t; });
    private volatile boolean closed;
    OplusRelayAdapter(Context context, ClassLoader loader, AppRelaySender sender) throws ReflectiveOperationException {
        if (!"com.oplus.linker".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context; this.loader = loader; this.sender = sender;
        Class<?> callback = Class.forName("com.oplus.synergy.engine.ISendDataCallback", false, loader);
        success = callback.getMethod("onRelayDataSent"); failure = callback.getMethod("onSendRelayDataFailed", int.class);
    }
    // The caller must supply the actual selected native peer/session, never infer one from a
    // global broadcast or claim an OAF/DataBus publisher is ready merely because this exists.
    Submission send(AppRelayPeer peer, byte[] payload, Object nativeCallback, BooleanSupplier current) {
        return send(peer, payload, nativeCallback, current, () -> false);
    }
    Submission send(AppRelayPeer peer, byte[] payload, Object nativeCallback, BooleanSupplier current, BooleanSupplier cancelRequested) {
        String request = "oplus-relay-" + UUID.randomUUID();
        Pending item = new Pending(request, nativeCallback, current, cancelRequested);
        if (payload == null || payload.length < 9 || payload.length > 16384) {
            finish(item, failed(item, "invalid_relay_payload")); return new Submission(request, item.future);
        }
        byte[] copy = payload.clone();
        synchronized (pending) {
            if (closed || peer == null || pending.size() >= 8 || !current(item)) {
                finish(item, failed(item, "native_peer_unavailable")); return new Submission(request, item.future);
            }
            pending.put(request, item);
        }
        item.future.whenComplete((ignored, error) -> { if (item.future.isCancelled()) cancel(request); });
        try { worker.execute(() -> {
            try {
                if (closed || !current(item)) { finish(item, failed(item, "session_revoked")); return; }
                if (cancelled(item)) { finish(item, cancelledResult(item)); return; }
                AppRelayRequest relay = OplusRelayCodec.decode(context, loader, copy, request);
                if (closed || !current(item)) { finish(item, failed(item, "session_revoked")); return; }
                if (cancelled(item)) { finish(item, cancelledResult(item)); return; }
                sender.send(peer.projectionId(), peer.sessionId(), relay).whenComplete((receipt, error) -> {
                    AppRelayTasks.Result result = error == null && receipt != null ? receipt : failed(item, "remote_request_failed");
                    finish(item, current(item) && !closed ? result : failed(item, "session_revoked"));
                });
                if (cancelled(item)) sender.cancel(request);
            } catch (Exception invalid) { finish(item, failed(item, AppRelayTasks.code(invalid))); }
        }); } catch (java.util.concurrent.RejectedExecutionException busy) { finish(item, failed(item, "relay_busy")); }
        return new Submission(request, item.future);
    }
    void cancel(String request) {
        Pending item = pending.get(request);
        if (item != null) { item.cancelled = true; sender.cancel(request); }
    }
    private static AppRelayTasks.Result failed(Pending item, String code) {
        return new AppRelayTasks.Result(item.id, AppRelayTasks.State.FAILED, code, "", -1);
    }
    private static AppRelayTasks.Result cancelledResult(Pending item) {
        return new AppRelayTasks.Result(item.id, AppRelayTasks.State.CANCELLED, "cancelled_before_launch", "", -1);
    }
    private static boolean cancelled(Pending item) {
        try { return item.cancelled || item.cancelRequested.getAsBoolean(); } catch (RuntimeException unavailable) { return true; }
    }
    private static boolean current(Pending item) { try { return item.current.getAsBoolean(); } catch (RuntimeException unavailable) { return false; } }
    private void finish(Pending item, AppRelayTasks.Result result) {
        synchronized (item) {
            if (item.delivered) return;
            item.delivered = true;
        }
        pending.remove(item.id, item);
        if ((!current(item) || closed) && result.state() == AppRelayTasks.State.COMPLETED) result = failed(item, "session_revoked");
        boolean delivered = false;
        try {
            if (item.callback != null) {
                if (result.state() == AppRelayTasks.State.COMPLETED) success.invoke(item.callback); else failure.invoke(item.callback, -1);
                delivered = true;
            }
        } catch (ReflectiveOperationException unavailable) { result = failed(item, "native_execution_failed"); }
        // Foreign Binder callbacks run outside our lock. A dead/absent callback cannot produce
        // the native-result evidence needed for the device acceptance ledger.
        if (delivered) AppRelayEvents.emit(AppRelayEvents.Event.SOURCE_NATIVE_RESULT, item.id, AppRelayEvents.outcome(result));
        item.future.complete(result);
    }
    @Override public void close() {
        closed = true;
        for (Pending item : java.util.List.copyOf(pending.values())) {
            item.cancelled = true; sender.cancel(item.id); finish(item, failed(item, "session_revoked"));
        }
        worker.shutdown(); // Drain bounded work; each cancelled decode receives one native failure.
    }
}
