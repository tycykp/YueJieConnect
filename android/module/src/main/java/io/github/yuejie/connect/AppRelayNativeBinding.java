package io.github.yuejie.connect;

import io.github.libxposed.api.XposedModule;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Shared owner supplies an actual selected DATA logical slot; this class assembles its business. */
final class AppRelayNativeBinding implements AutoCloseable {
    interface Selection {
        AppRelayPeer selected();
        String deviceName();
        boolean dataChannelReady();
        boolean ownsNativeSlot(AppRelayPeer peer);
        default boolean canPrepare(){return dataChannelReady();}
    }
    private final BridgeClient client;
    private final Selection selection;
    private final AppRelayBridge bridge;
    private final AppRelayDocumentDecisions documentDecisions = new AppRelayDocumentDecisions(android.os.SystemClock::elapsedRealtime);
    private final AppRelayDocumentNegotiation documentNegotiation;
    private AppRelayNativeDelegate delegate;
    private XiaomiRelaySource xiaomiSource;
    private OplusRelaySource oplusSource;
    private XiaomiRelayAdapter xiaomiAdapter;
    private XiaomiRelayClickAdapter xiaomiClick;
    private OplusRelayClickAdapter oplusClick;
    private volatile boolean installed, closed;
    AppRelayNativeBinding(XposedModule module, BridgeClient client, ClassLoader loader, int brand, Selection selection,
                          AppRelayBridge.DocumentsFactory documents, XiaomiRelayCodec.Files files, BooleanSupplier documentsReady) throws Exception {
        this.client = client; this.selection = selection;
        AppRelayPeer.Resolver resolver = nativeId -> {
            AppRelayPeer peer = selection.selected();
            return peer != null && peer.nativeId().equals(nativeId) && peer.matches(client.projection()) && selection.dataChannelReady() ? peer : null;
        };
        bridge = new AppRelayBridge(client, loader, brand, this::ready, documents, (projection, session) -> {
            AppRelayPeer peer = require(projection, session);
            BooleanSupplier current = () -> bridgeAuthorized(peer);
            BooleanSupplier owns = () -> selection.ownsNativeSlot(peer);
            AppRelayDirectory.Consumer nativeDirectory = brand == 1 ? new XiaomiRelayDirectory(client.context(), loader, peer, selection.deviceName(), current, owns, documentsReady)
                    : new OplusRelayDirectory(client.context(), loader, peer, selection.deviceName(), current, owns, documentsReady);
            return new AppRelayDirectory.Consumer() {
                public void apply(AppRelayCatalog.Update update) throws Exception {
                    if (update.clearAll()) { nativeDirectory.apply(update); documentDecisions.consumed(peer, update, ""); return; }
                    for (var value : update.candidates()) {
                        var part = new AppRelayCatalog.Update(false, java.util.List.of(value));
                        nativeDirectory.apply(part);
                        documentDecisions.consumed(peer, part, nativeDirectory instanceof OplusRelayDirectory oplus ? oplus.clickToken() : "");
                    }
                }
                public void clear() throws Exception { nativeDirectory.clear(); documentDecisions.clear(peer); }
            };
        }, (projection, session) -> {
            AppRelayPeer peer = require(projection, session);
            return brand == 1 ? xiaomiSource.forPeer(peer) : oplusSource.forPeer(peer);
        });
        documentNegotiation = new AppRelayDocumentNegotiation(bridge, documentDecisions);
        bridge.registrationReady(()->installed&&!closed&&selection.canPrepare()&&client.hasLocalGrant("app_relay"));
        try {
            OplusRelayAdapter oplus = brand == 2 ? new OplusRelayAdapter(client.context(), loader, bridge.sender()) : null;
            delegate = new AppRelayNativeDelegate(bridge, resolver, client::projection, this::ready, oplus, documentsReady);
            delegate.documentDecisions(documentDecisions, loader, brand);
            if (brand == 1) {
                xiaomiSource = new XiaomiRelaySource(loader, resolver, bridge::authorized, bridge.sender());
                xiaomiAdapter = new XiaomiRelayAdapter(module, client.context(), loader, bridge::authorized, client::projection,
                        bridge.sender(), files, resolver, delegate);
                xiaomiAdapter.sourceRequests(xiaomiSource);
                xiaomiClick = new XiaomiRelayClickAdapter(module, loader, delegate);
                xiaomiAdapter.install(); xiaomiClick.install();
            } else {
                oplusSource = new OplusRelaySource(module, client.context(), loader, resolver, selection::selected,
                        bridge::authorized, selection::deviceName, bridge, oplus);
                oplusClick = new OplusRelayClickAdapter(module, loader, delegate);
                oplusSource.install(); oplusClick.install();
            }
            installed = true;
            bridge.install();
        } catch (Exception failure) { close(); throw failure; }
    }
    private boolean ready() {
        AppRelayPeer peer = selection.selected();
        return installed && !closed && peer != null && peer.matches(client.projection()) && selection.dataChannelReady()
                && selection.ownsNativeSlot(peer) && client.hasLocalGrant("app_relay");
    }
    private boolean bridgeAuthorized(AppRelayPeer peer) { return peer.equals(selection.selected()) && ready() && bridge.authorized(); }
    private AppRelayPeer require(String projection, String session) {
        AppRelayPeer peer = selection.selected();
        if (peer == null || !peer.projectionId().equals(projection) || !peer.sessionId().equals(session) || !ready())
            throw new IllegalStateException("native_peer_unavailable");
        return peer;
    }
    boolean localResourcesPrepared() { return installed && !closed; }
    void nativeStateChanged() { bridge.nativeStateChanged(); documentDecisions.maintain(); }
    void nativeDocumentPrepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint endpoint, FileTransferNegotiation negotiation) {
        documentNegotiation.prepared(prepared, endpoint, negotiation);
    }
    void nativeDocumentRevoked(FileTransferEngine.Key key) { documentDecisions.revoked(key); }
    void nativeDocumentsRevoked() { documentDecisions.revokeDocuments(); }
    CompletableFuture<AppRelayEndpoint.Documents> documentsForSession(String projection, String session) {
        return bridge.documentsForSession(projection, session);
    }
    CompletableFuture<Void> whenRetired() { return bridge.whenRetired(); }
    @Override public void close() {
        if (closed) return; closed = true; documentDecisions.close();
        if (xiaomiSource != null) xiaomiSource.close();
        if (oplusSource != null) oplusSource.close();
        if (xiaomiAdapter != null) xiaomiAdapter.close();
        bridge.close();
        bridge.whenRetired().thenRun(() -> {
            if (xiaomiClick != null) xiaomiClick.close();
            if (oplusClick != null) oplusClick.close();
            if (delegate != null) delegate.close();
        });
    }
}
