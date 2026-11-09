package io.github.yuejie.connect;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Minimal selected-peer delegates for the shared native adapter; never infer a peer from a topic. */
final class AppRelayNativeDelegate implements AutoCloseable {
    private final AppRelayBridge bridge;
    private final AppRelayPeer.Resolver resolver;
    private final Supplier<JSONObject> projection;
    private final BooleanSupplier allowed;
    private final OplusRelayAdapter oplus;
    private final BooleanSupplier documentReady;
    private final java.util.Map<String, CompletableFuture<AppRelayTasks.Result>> syncs = new java.util.HashMap<>();
    private AppRelayDocumentDecisions documentDecisions;
    private Class<?> oplusConfig;
    void documentDecisions(AppRelayDocumentDecisions decisions, ClassLoader loader, int brand) throws Exception {
        this.documentDecisions = decisions;
        if (brand == 2) oplusConfig = Class.forName("com.oplus.synergy.utils.Config", false, loader);
    }
    private volatile boolean closed;
    AppRelayNativeDelegate(AppRelayBridge bridge, AppRelayPeer.Resolver resolver, Supplier<JSONObject> projection,
                           BooleanSupplier allowed, OplusRelayAdapter oplus) {
        this(bridge, resolver, projection, allowed, oplus, () -> false);
    }
    AppRelayNativeDelegate(AppRelayBridge bridge, AppRelayPeer.Resolver resolver, Supplier<JSONObject> projection,
                           BooleanSupplier allowed, OplusRelayAdapter oplus, BooleanSupplier documentReady) {
        this.bridge = bridge; this.resolver = resolver; this.projection = projection; this.allowed = allowed; this.oplus = oplus;
        this.documentReady = documentReady;
    }
    AppRelayPeer selected(String nativePeer) {
        if (closed || nativePeer == null || nativePeer.isEmpty() || !allowed.getAsBoolean()) return null;
        AppRelayPeer peer = resolver.selected(nativePeer);
        return bridge.authorized() && peer != null
                && nativePeer.equals(peer.nativeId()) && peer.matches(projection.get()) ? peer : null;
    }
    boolean isGatewayClick(android.content.Intent intent) {
        return intent != null && (intent.hasExtra(OplusRelayDirectory.EXTRA_NATIVE_PEER)
                || intent.hasExtra(OplusRelayDirectory.EXTRA_PROJECTION) || intent.hasExtra(OplusRelayDirectory.EXTRA_SESSION));
    }
    AppRelayPeer selectedClick(android.content.Intent intent) {
        if (!isGatewayClick(intent)) return null;
        AppRelayPeer peer = selected(intent.getStringExtra(OplusRelayDirectory.EXTRA_NATIVE_PEER));
        if (peer != null && documentDecisions != null) {
            try {
                String pkgKey = (String)oplusConfig.getField("KEY_RELAY_SOURCE_PKG_NAME").get(null);
                if (!documentDecisions.ownsClick(peer, intent.getStringExtra(pkgKey), intent.getStringExtra(OplusRelayDirectory.EXTRA_DIRECTORY_TOKEN))) return null;
            } catch (Exception invalid) { return null; }
        }
        return peer != null && peer.projectionId().equals(intent.getStringExtra(OplusRelayDirectory.EXTRA_PROJECTION))
                && peer.sessionId().equals(intent.getStringExtra(OplusRelayDirectory.EXTRA_SESSION)) ? peer : null;
    }
    AppRelayCatalog.Candidate candidate(AppRelayPeer peer, String pkg) {
        return documentDecisions == null ? null : documentDecisions.candidate(peer, pkg);
    }
    CompletableFuture<JSONObject> xiaomiCatalog(String nativePeer, Object message) {
        try {
            AppRelayPeer peer = require(nativePeer);
            AppRelayCatalog.Update update = message != null && "com.xiaomi.mirror.message.RecentAppsMessage".equals(message.getClass().getName())
                    ? XiaomiRelayCatalog.recent(message) : XiaomiRelayCatalog.relay(message);
            return xiaomiCatalog(peer, update);
        } catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    CompletableFuture<JSONObject> xiaomiCatalog(AppRelayPeer peer, AppRelayCatalog.Update update) {
        try {
            if (!peer.equals(require(peer.nativeId()))) throw new IllegalStateException("stale_session");
            if (!documentReady.getAsBoolean()) update = new AppRelayCatalog.Update(update.clearAll(), update.candidates().stream()
                    .map(candidate -> candidate.kind() == AppRelayCatalog.Kind.DOCUMENT
                            ? new AppRelayCatalog.Candidate(candidate.packageName(), candidate.label(), candidate.kind(), candidate.visible(), false,
                                    candidate.nativeDestinationType(), candidate.activity()) : candidate).toList());
            return bridge.publishCatalog(peer, update);
        } catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    CompletableFuture<JSONObject> oplusCatalog(String nativePeer, List<?> profiles) {
        try { return bridge.publishCatalog(require(nativePeer), OplusRelayCatalog.profiles(profiles)); }
        catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    OplusRelayAdapter.Submission oplusSend(String nativePeer, byte[] payload, Object nativeCallback) {
        if (oplus == null) throw new IllegalStateException("invalid_relay_brand");
        AppRelayPeer peer = selected(nativePeer);
        return oplus.send(peer, payload, nativeCallback, () -> peer != null && peer.equals(selected(nativePeer)));
    }
    void oplusCancel(String requestId) { if (oplus != null) oplus.cancel(requestId); }
    CompletableFuture<AppRelayTasks.Result> requestSync(AppRelayPeer peer, String sourcePackage) {
        if (peer == null || !peer.equals(selected(peer.nativeId())))
            return CompletableFuture.failedFuture(new IllegalStateException("native_peer_unavailable"));
        String key = peer.sessionId() + ":" + sourcePackage;
        synchronized (syncs) {
            CompletableFuture<AppRelayTasks.Result> previous = syncs.get(key);
            if (previous != null) return previous.copy();
            if (syncs.size() >= 8) return CompletableFuture.failedFuture(new IllegalStateException("relay_busy"));
            String ownerToken;
            boolean document = documentDecisions != null && documentDecisions.document(peer, sourcePackage);
            try { ownerToken = documentDecisions == null ? "" : documentDecisions.clicked(peer, sourcePackage,
                    () -> !closed && peer.equals(selected(peer.nativeId())) && (!document || documentReady.getAsBoolean())); }
            catch (RuntimeException unavailable) { return CompletableFuture.failedFuture(unavailable); }
            CompletableFuture<AppRelayTasks.Result> future = bridge.requestSync(peer, sourcePackage, ownerToken);
            syncs.put(key, future);
            future.whenComplete((result, error) -> {
                if (documentDecisions != null && !ownerToken.isEmpty()) documentDecisions.finished(ownerToken, error == null ? result : null);
                synchronized (syncs) { syncs.remove(key, future); }
            });
            return future.copy();
        }
    }
    private AppRelayPeer require(String nativePeer) {
        AppRelayPeer peer = selected(nativePeer); if (peer == null) throw new IllegalStateException("native_peer_unavailable"); return peer;
    }
    @Override public void close() {
        closed = true;
        synchronized (syncs) { for (var future : java.util.List.copyOf(syncs.values())) future.cancel(true); syncs.clear(); }
        if (oplus != null) oplus.close();
    }
}
