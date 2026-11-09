package io.github.yuejie.connect;

import io.github.libxposed.api.XposedModule;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
            boolean appNoticeEnabled = brand == 1 || client.context().getResources().getConfiguration().smallestScreenWidthDp < 600;
            return new AppRelayDirectory.Consumer() {
                private String appNotice = "";
                public void apply(AppRelayCatalog.Update update) throws Exception {
                    if (update.clearAll()) { clear(); return; }
                    for (var value : update.candidates()) {
                        if (BrowserRelayOffer.ownsPackage(value.packageName())) {
                            if (value.kind() != AppRelayCatalog.Kind.BROWSER) throw new IllegalArgumentException("invalid_browser_offer");
                            updateBrowserNotice(peer, value.packageName(), value.eligible(true, true), "");
                            continue;
                        }
                        var part = new AppRelayCatalog.Update(false, java.util.List.of(value));
                        if (appNoticeEnabled && value.kind() == AppRelayCatalog.Kind.APP) {
                            boolean show = value.eligible(true, true);
                            String label = "";
                            if (show) {
                                if (nativeDirectory instanceof XiaomiRelayDirectory xiaomi) xiaomi.requireEnabled();
                                else OplusRelayPermission.check(client.context(), loader, value.packageName());
                                var pm = client.context().getPackageManager();
                                var app = pm.getApplicationInfo(value.packageName(), 0);
                                if (!app.enabled || (app.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0)
                                    throw new IllegalStateException("target_app_disabled");
                                label = AppRelayCatalog.text(pm.getApplicationLabel(app).toString(), 256);
                                if (!current.getAsBoolean()) throw new IllegalStateException("stale_session");
                            }
                            updateBrowserNotice(peer, value.packageName(), show, label);
                            appNotice = show ? value.packageName() : appNotice.equals(value.packageName()) ? "" : appNotice;
                            documentDecisions.consumed(peer, part, "");
                            continue;
                        }
                        if (value.eligible(true, true)) hideAppNotice();
                        nativeDirectory.apply(part);
                        documentDecisions.consumed(peer, part, nativeDirectory instanceof OplusRelayDirectory oplus ? oplus.clickToken() : "");
                    }
                }
                private void hideAppNotice() throws Exception {
                    if (appNotice.isEmpty()) return;
                    String previous = appNotice;
                    appNotice = "";
                    if (current.getAsBoolean()) updateBrowserNotice(peer, previous, false, "");
                }
                public void clear() throws Exception {
                    hideAppNotice();
                    nativeDirectory.clear();
                    documentDecisions.clear(peer);
                }
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
                xiaomiAdapter.sourceRequests(xiaomiSource, selection::selected);
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
    boolean browserRelayReady() { return bridge.authorized(); }
    CompletableFuture<org.json.JSONObject> publishBrowserOffer(AppRelayPeer peer, BrowserRelayOffer offer) {
        return bridge.publishBrowserOffer(peer, offer);
    }
    void openSharedOffer(AppRelayPeer peer, String pkg) {
        if (!bridgeAuthorized(peer)) return;
        boolean browser = BrowserRelayOffer.ownsPackage(pkg);
        if (!browser) {
            AppRelayCatalog.Candidate candidate = delegate.candidate(peer, pkg);
            if (candidate == null || candidate.kind() != AppRelayCatalog.Kind.APP) return;
        }
        AppRelayEvents.emit(browser ? AppRelayEvents.Event.BROWSER_NOTICE_CLICK : AppRelayEvents.Event.APP_NOTICE_CLICK,
                browser ? "browser-click" : "app-click", AppRelayEvents.Outcome.PENDING);
        var request = browser ? bridge.requestSync(peer, pkg) : delegate.requestSync(peer, pkg);
        request.whenComplete((result, failure) -> {
            if (result != null) AppRelayEvents.emit(AppRelayEvents.Event.NATIVE_CLICK_RESULT, result.requestId(), AppRelayEvents.outcome(result));
        });
    }
    private void updateBrowserNotice(AppRelayPeer peer, String pkg, boolean show, String label) throws Exception {
        var command = new org.json.JSONObject().put("op", "browser_relay_notice").put("session", peer.sessionId())
                .put("package", pkg).put("show", show).put("app_label", label);
        // Incoming catalog work owns the relay scope. Complete the Binder reply on
        // its worker so a simultaneous outgoing share can wait for that scope safely.
        var result = client.browserRelay(command).get(4, TimeUnit.SECONDS);
        if (result == null || !"ok".equals(result.optString("status"))) {
            String code = result == null ? "no_reply" : result.optString("code");
            if (code.matches("[a-z_]{1,64}")) ScreenCastLog.w("YueJieRelay", "app_notice_rejected " + code);
            throw new IllegalStateException("notification_unavailable");
        }
    }
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
