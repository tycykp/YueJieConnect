package io.github.yuejie.connect;

import android.os.Handler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Calls the real registered Mirror app observer. e.J remains the only data producer. */
final class XiaomiRelaySource implements AutoCloseable {
    private record Candidate(AppRelayPeer peer, String process) {}
    private static final class Pending {
        final AppRelayPeer peer;
        final String id, pkg, ownerToken;
        final long nativeSession;
        final CompletableFuture<AppRelayTasks.Result> result = new CompletableFuture<>();
        boolean claimed, cancelled;
        Pending(AppRelayPeer peer, String id, String pkg, long nativeSession, String ownerToken) {
            this.peer = peer; this.id = id; this.pkg = pkg; this.nativeSession = nativeSession; this.ownerToken = ownerToken;
        }
    }
    private final Object helper;
    private final Handler handler;
    private final Method request;
    private final AppRelayPeer.Resolver resolver;
    private final BooleanSupplier allowed;
    private final AppRelaySender sender;
    private final Map<String, Candidate> candidates = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private boolean closed;
    XiaomiRelaySource(ClassLoader loader, AppRelayPeer.Resolver resolver, BooleanSupplier allowed, AppRelaySender sender) throws Exception {
        this.resolver = resolver; this.allowed = allowed; this.sender = sender;
        Class<?> local = Class.forName("com.xiaomi.mirror.relay.s0", false, loader);
        Object owner = local.getMethod("W").invoke(null);
        helper = local.getField("d").get(owner);
        handler = (Handler) helper.getClass().getField("c").get(helper);
        request = helper.getClass().getMethod("y", String.class, String.class, long.class);
    }
    synchronized void observe(AppRelayPeer peer, Object message) throws Exception {
        if (!"com.xiaomi.mirror.message.RelayAppMessage".equals(message.getClass().getName())) return;
        var update = XiaomiRelayCatalog.relay(message);
        candidates.entrySet().removeIf(entry -> !entry.getValue().peer().sessionId().equals(peer.sessionId()));
        String process = (String) message.getClass().getField("processName").get(message);
        for (var value : update.candidates()) {
            String key = key(peer, value.packageName());
            if (value.eligible(true, true)) {
                if (!candidates.containsKey(key) && candidates.size() >= 16) throw new IllegalStateException("relay_busy");
                candidates.put(key, new Candidate(peer, process));
            } else candidates.remove(key);
        }
    }
    AppRelaySync.Native forPeer(AppRelayPeer peer) {
        return new AppRelaySync.Native() {
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg) { return start(peer, id, pkg, ""); }
            public CompletableFuture<AppRelayTasks.Result> request(String id, String pkg, String token) { return start(peer, id, pkg, token); }
            public void cancel(String id) { XiaomiRelaySource.this.cancel(peer, id); }
        };
    }
    private synchronized CompletableFuture<AppRelayTasks.Result> start(AppRelayPeer peer, String id, String pkg, String token) {
        Candidate candidate = candidates.get(key(peer, pkg));
        if (!current(peer) || candidate == null) return CompletableFuture.failedFuture(new IllegalStateException("native_source_unavailable"));
        // Mirror owns a single native source slot. A response has its original OEM long session,
        // separate from the Noise session and from the gateway request ID.
        if (pending.values().stream().anyMatch(value -> !value.result.isDone()))
            return CompletableFuture.failedFuture(new IllegalStateException("relay_busy"));
        if (pending.size() >= 128) return CompletableFuture.failedFuture(new IllegalStateException("relay_busy"));
        long transaction;
        do { transaction = random.nextLong() & Long.MAX_VALUE; } while (transaction == 0 || pending.containsKey(transaction));
        Pending item = new Pending(peer, id, pkg, transaction, token); pending.put(transaction, item);
        if (!handler.post(() -> {
            synchronized (XiaomiRelaySource.this) {
                if (!current(peer) || item.cancelled) { finish(item, null, "cancelled_before_launch"); return; }
                try {
                    long nativeStarted = helper.getClass().getField("j").getLong(helper);
                    if (System.currentTimeMillis() - nativeStarted < 15000) {
                        Pending previous = pending.get(helper.getClass().getField("i").getLong(helper));
                        AppRelayTasks.Result completed = previous == null ? null : previous.result.getNow(null);
                        if (completed == null || completed.state() != AppRelayTasks.State.COMPLETED) throw new IllegalStateException("relay_busy");
                    }
                    if (!Boolean.TRUE.equals(helper.getClass().getMethod("v", String.class).invoke(helper, pkg)))
                        throw new IllegalStateException("native_source_unavailable");
                    request.invoke(helper, peer.nativeId(), candidate.process(), item.nativeSession);
                } catch (Exception failure) { finish(item, null, AppRelayTasks.code(failure)); }
            }
        })) finish(item, null, "native_source_unavailable");
        return item.result;
    }
    synchronized String claim(AppRelayPeer peer, Object owner, long nativeSession, String pkg) {
        Pending item = pending.get(nativeSession);
        if (owner != helper || item == null || !item.peer.equals(peer) || !item.pkg.equals(pkg)) return null;
        if (item.claimed || item.cancelled || item.result.isDone() || !current(peer)) return "";
        item.claimed = true;
        AppRelayEvents.emit(AppRelayEvents.Event.SOURCE_PAYLOAD, item.id, AppRelayEvents.Outcome.PENDING);
        return item.id;
    }
    synchronized void complete(long nativeSession, AppRelayTasks.Result result) {
        Pending item = pending.get(nativeSession);
        if (item != null) finish(item, result, "native_source_failed");
    }
    synchronized boolean cancelled(long nativeSession) {
        Pending item = pending.get(nativeSession); return item != null && (item.cancelled || item.result.isDone());
    }
    synchronized String documentOwnerToken(AppRelayPeer peer, long nativeSession) {
        Pending item = pending.get(nativeSession);
        return item != null && item.peer.equals(peer) && item.claimed && !item.cancelled && !item.result.isDone()
                && current(peer) ? item.ownerToken : "";
    }
    private void finish(Pending item, AppRelayTasks.Result result, String failure) {
        if (item.result.isDone()) return;
        if (result != null) item.result.complete(new AppRelayTasks.Result(item.id, result.state(), result.code(), result.targetPackage(), result.taskId()));
        else item.result.complete(new AppRelayTasks.Result(item.id,
                "cancelled_before_launch".equals(failure) ? AppRelayTasks.State.CANCELLED : AppRelayTasks.State.FAILED, failure, "", -1));
    }
    private synchronized void cancel(AppRelayPeer peer, String id) {
        for (Pending item : pending.values()) if (item.peer.equals(peer) && item.id.equals(id)) {
            item.cancelled = true;
            if (item.claimed) sender.cancel(item.id); else finish(item, null, "cancelled_before_launch");
        }
    }
    private boolean current(AppRelayPeer peer) { return !closed && allowed.getAsBoolean() && peer.equals(resolver.selected(peer.nativeId())); }
    private static String key(AppRelayPeer peer, String pkg) { return peer.sessionId() + ":" + pkg; }
    @Override public synchronized void close() {
        if (closed) return; closed = true; candidates.clear();
        for (Pending item : pending.values()) { item.cancelled = true; sender.cancel(item.id); finish(item, null, "session_revoked"); }
    }
}
