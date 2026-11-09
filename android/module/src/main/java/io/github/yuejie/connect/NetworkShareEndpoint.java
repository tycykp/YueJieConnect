package io.github.yuejie.connect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Consumer;

import static io.github.yuejie.connect.NetworkShareSession.*;

/** Runs only inside the OEM executor, after bridge peer/session/operation/user authorization.
 * Uses a scoped Android 17 tethering request, not LocalOnlyHotspot or the Noise LAN route.
 * All writes/callbacks are serialized; a local lease bounds control loss while this process lives.
 * A private journal recovers synthetic resources on the next OEM executor creation.
 */
@SuppressWarnings("deprecation")
final class NetworkShareEndpoint implements NetworkShareController.Local {
    record Offer(String session, String request, String ssid, String passphrase, Upstream upstream) {
        Offer {
            if (session == null || !session.matches("[a-f0-9]{64}") || request == null
                    || !request.matches("[A-Za-z0-9_:-]{1,128}") || ssid == null
                    || !ssid.matches("YJ-[a-f0-9]{16}") || passphrase == null
                    || !passphrase.matches("[a-f0-9]{32}") || upstream == null)
                throw new IllegalArgumentException("invalid_network_offer");
        }
        @Override public String toString() { return "NetworkOffer[credentials redacted]"; }
    }
    record Availability(boolean provider, boolean consumer, Upstream upstream, boolean metered, String error) {}
    private final Context context;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final TetheringManager tethering;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "yj-network-share"); t.setDaemon(true); return t;
    });
    private final NetworkShareSession state = new NetworkShareSession();
    private final Set<TetheringInterface> tethered = new HashSet<>();
    private final SecureRandom random = new SecureRandom();
    private boolean snapshotReceived, registered, providerStarted, providerStartSettled, providerStartFailed, stopSucceeded;
    private boolean allowMetered;
    private volatile boolean closing;
    private boolean originalWifiEnabled;
    private Network actualUpstream, requestedUpstream;
    private TetheringManager.TetheringRequest nativeRequest;
    private SoftApConfiguration ownedAp;
    private Offer offer;
    private int previousNetwork = -1, addedNetwork = -1;
    private boolean consumerChanged, networkRemoved;
    private boolean preserveExternalChoice;
    private int wifiClients;
    private long restoreDeadline;
    private NetworkShareRecovery recovery;
    private final Consumer<View> listener;
    private View lastPublished;
    private String recoveryError, previousFingerprint;
    private boolean recoveredOwner, recoveryStarted, stopRequested, nativeStartIssued, consumerMutationIssued;
    private long snapshotGeneration, stopSnapshot;
    private String lastDiagnostic="";
    private CompletableFuture<Offer> startResult;
    private CompletableFuture<View> joinResult, stopResult, confirmResult;

    private final TetheringManager.TetheringEventCallback events = new TetheringManager.TetheringEventCallback() {
        @Override public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
            tethered.clear(); tethered.addAll(interfaces); snapshotReceived = true; snapshotGeneration++;
            View view = state.view();
            if (recoveredOwner && view != null && view.phase() == Phase.RECOVERY_REQUIRED && ownsAp())
                beginRestore("owner_process_restarted");
            tickSafely();
        }
        // System API on the reviewed Android 17 ROMs; verify presence before registering.
        // Intentionally no @Override: the public SDK omits this system callback.
        public void onUpstreamChanged(Network network) { actualUpstream = network; tickSafely(); }
        public void onClientsChanged(Collection<?> clients) {
            int count = 0;
            try {
                for (Object client : clients) {
                    int type = (Integer) client.getClass().getMethod("getTetheringType").invoke(client);
                    Collection<?> addresses = (Collection<?>) client.getClass().getMethod("getAddresses").invoke(client);
                    if (type == TetheringManager.TETHERING_WIFI && !addresses.isEmpty()) count++;
                }
                wifiClients = count;
            } catch (ReflectiveOperationException | RuntimeException error) { wifiClients = 0; }
        }
    };

    NetworkShareEndpoint(Context context) {
        this(context, view -> {});
    }
    NetworkShareEndpoint(Context context, Consumer<View> listener) {
        if (context == null || !("com.milink.service".equals(context.getPackageName())
                || "com.oplus.linker".equals(context.getPackageName())))
            throw new SecurityException("invalid_executor_context");
        this.context = context;
        this.listener = listener;
        wifi = context.getSystemService(WifiManager.class);
        connectivity = context.getSystemService(ConnectivityManager.class);
        tethering = context.getSystemService(TetheringManager.class);
        worker.setRemoveOnCancelPolicy(true);
        worker.execute(() -> {
            NetworkShareRecoveryStore store = null;
            try {
                store = new NetworkShareRecoveryStore(context);
                recovery = new NetworkShareRecovery(store, context.getPackageName(), android.os.Process.myUid());
                if (recovery.entry() != null) loadRecovery(recovery.entry());
            } catch (IOException | RuntimeException error) {
                recoveryError = "network_recovery_store_unavailable";
                recovery = null;
                if (store != null) try { store.close(); } catch (IOException ignored) { }
            }
            try {
                TetheringManager.TetheringEventCallback.class.getMethod("onUpstreamChanged", Network.class);
                TetheringManager.TetheringEventCallback.class.getMethod("onClientsChanged", Collection.class);
                if (tethering != null && permission("android.permission.TETHER_PRIVILEGED")) {
                    tethering.registerTetheringEventCallback(worker, events); registered = true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { registered = false; }
        });
        worker.scheduleWithFixedDelay(this::tickSafely, 200, 200, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Availability> availability() { return submit(this::availabilityNow); }
    CompletableFuture<View> status() { return submit(state::view); }

    /** Must follow the native user request and an authenticated remote capability/grant check. */
    public CompletableFuture<Offer> startProvider(String session, String request, Upstream upstream, boolean meteredAllowed) {
        CompletableFuture<Offer> result = new CompletableFuture<>();
        enqueue(result, () -> {
            bind(session);
            View previous = state.view();
            if (previous != null && previous.ticket().request().equals(request)) {
                if (previous.role() != Role.PROVIDER || previous.upstream() != upstream || allowMetered != meteredAllowed)
                    throw new IllegalArgumentException("request_id_reused");
                if (startResult != null) pipe(startResult, result);
                else throw new IllegalStateException("request_already_finished");
                return;
            }
            Availability available = availabilityNow();
            Ticket ticket = state.request(session, request, Role.PROVIDER, upstream, now());
            resetOwned(); allowMetered = meteredAllowed; startResult = result;
            try {
                if (!available.provider()) throw new IllegalStateException(available.error());
                if (upstream != available.upstream()) throw new IllegalStateException("upstream_unavailable");
                if ((available.metered() || upstream == Upstream.CELLULAR) && !meteredAllowed)
                    throw new SecurityException("metered_network_not_authorized");
                if (upstream == Upstream.WIFI && !meteredAllowed && mobileDataEnabled()) throw new IllegalStateException("cellular_fallback_enabled");
            } catch (RuntimeException error) { rejectBeforeChange(ticket); throw error; }
            if (!state.confirm(ticket, now())) throw new IllegalStateException("request_already_finished");
            requestedUpstream = connectivity.getActiveNetwork();
            originalWifiEnabled = wifi.isWifiEnabled();
            offer = new Offer(session, request, "YJ-" + secret(8), secret(16), upstream);
            ownedAp = new SoftApConfiguration.Builder()
                    .setWifiSsid(WifiSsid.fromBytes(offer.ssid().getBytes(StandardCharsets.UTF_8)))
                    .setPassphrase(offer.passphrase(), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK).build();
            try {
                nativeRequest = createTetheringRequest(ownedAp);
                recovery.prepare(recoveryEntry()); // Durable ownership before any native start is issued.
                nativeStartIssued = true;
                tethering.startTethering(nativeRequest, worker, new TetheringManager.StartTetheringCallback() {
                    @Override public void onTetheringStarted() {
                        if (!current(ticket)) return;
                        providerStarted = true; providerStartSettled = true;
                        if (state.view().phase() == Phase.RECOVERY_REQUIRED) beginRestore("late_provider_start");
                        else if (state.view().phase() == Phase.STOPPING) stopNativeProvider();
                        tickSafely();
                    }
                    @Override public void onTetheringFailed(int error) {
                        if (!current(ticket)) return;
                        providerStartSettled = true; providerStartFailed = true;
                        failAndRestore("tether_start_" + error);
                    }
                });
            } catch (IOException | RuntimeException error) {
                providerStartSettled = !nativeStartIssued; providerStartFailed = !nativeStartIssued;
                failAndRestore(error instanceof IOException ? "network_recovery_write_failed"
                        : error instanceof SecurityException ? "tether_permission_denied" : "tether_start_failed");
            }
        });
        return result;
    }

    /** Native Wi-Fi connection; adds only a temporary network and never changes cellular/VPN settings. */
    public CompletableFuture<View> joinConsumer(String session, String request, Offer remote, boolean meteredAllowed) {
        CompletableFuture<View> result = new CompletableFuture<>();
        enqueue(result, () -> {
            if (!session.equals(remote.session()) || !request.equals(remote.request()))
                throw new SecurityException("offer_identity_mismatch");
            bind(session);
            View old = state.view();
            if (old != null && old.ticket().request().equals(request)) {
                if (old.role() != Role.CONSUMER || !remote.equals(offer) || allowMetered != meteredAllowed)
                    throw new IllegalArgumentException("request_id_reused");
                if (joinResult != null) pipe(joinResult, result);
                else throw new IllegalStateException("request_already_finished");
                return;
            }
            Availability available = availabilityNow();
            Ticket ticket = state.request(session, request, Role.CONSUMER, remote.upstream(), now());
            resetOwned(); offer = remote; allowMetered = meteredAllowed; joinResult = result;
            try {
                if (!available.consumer()) throw new IllegalStateException("wifi_join_unavailable");
                if (remote.upstream() == Upstream.CELLULAR && !meteredAllowed)
                    throw new SecurityException("metered_network_not_authorized");
            } catch (RuntimeException error) { rejectBeforeChange(ticket); throw error; }
            if (!state.confirm(ticket, now())) throw new IllegalStateException("request_already_finished");
            try {
                WifiInfo oldWifi = wifi.getConnectionInfo();
                previousNetwork = oldWifi == null ? -1 : oldWifi.getNetworkId();
                originalWifiEnabled = wifi.isWifiEnabled();
                List<WifiConfiguration> configured = readConfiguredNetworks();
                if (configured == null) throw new SecurityException("wifi_snapshot_denied");
                for (WifiConfiguration c : configured) {
                    if (quote(remote.ssid()).equals(c.SSID)) throw new IllegalStateException("wifi_configuration_conflict");
                    if (c.networkId == previousNetwork) previousFingerprint = fingerprint(c);
                }
                if (previousNetwork >= 0 && previousFingerprint == null) throw new IllegalStateException("wifi_snapshot_incomplete");
                WifiConfiguration config = new WifiConfiguration();
                config.SSID = quote(remote.ssid()); config.preSharedKey = quote(remote.passphrase());
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                // Each offer has a fresh SSID, absent from the tablet's scan cache.
                // Request SSID-directed probes even though the AP broadcasts its name;
                // otherwise supplicant can wait for a later wildcard scan to discover it.
                // This temporary profile is removed by the normal ownership cleanup.
                config.hiddenSSID = true;
                consumerChanged = true;
                recovery.prepare(recoveryEntry()); // Covers death between addNetwork and recording its returned ID.
                consumerMutationIssued = true;
                addedNetwork = wifi.addNetwork(config);
                if (addedNetwork < 0) throw new IllegalStateException("wifi_add_denied");
                recovery.checkpoint(recoveryEntry());
                connectNetwork(addedNetwork); // OEM/system API, no enableNetwork(..., true) global disabling.
            } catch (IOException | ReflectiveOperationException | RuntimeException error) {
                if (!consumerMutationIssued) consumerChanged = false;
                failAndRestore(error instanceof IOException ? "network_recovery_write_failed"
                        : error instanceof SecurityException ? "wifi_permission_denied" : "wifi_join_failed");
            }
        });
        return result;
    }

    /** Bridge calls this only for a matching remote executor result after actual validated Wi-Fi join. */
    public CompletableFuture<View> confirmConsumer(String session, String request) {
        CompletableFuture<View> result = new CompletableFuture<>();
        enqueue(result, () -> {
            tick();
            Ticket t = ticket(session, request);
            if (state.view().role() != Role.PROVIDER) throw new IllegalStateException("not_provider");
            if (state.view().phase() == Phase.ACTIVE) { result.complete(state.view()); return; }
            if (state.view().phase() != Phase.READY) throw new IllegalStateException("provider_not_ready");
            if (confirmResult != null) pipe(confirmResult, result); else confirmResult = result;
            tick();
        });
        return result;
    }

    public CompletableFuture<View> renew(String session, String request) {
        return submit(() -> { tick(); state.renew(ticket(session, request), now()); return state.view(); });
    }
    public CompletableFuture<View> stop(String session, String request) {
        CompletableFuture<View> result = new CompletableFuture<>();
        enqueue(result, () -> {
            ticket(session, request);
            if (!state.ownsResource()) { result.complete(state.view()); return; }
            if (stopResult != null && !stopResult.isDone()) { pipe(stopResult, result); return; }
            stopResult = result; beginRestore(null);
        });
        return result;
    }

    /** Called on revocation, cancellation or lost authenticated session. Cleanup does not need a live peer. */
    void invalidate() {
        if (worker.isShutdown()) return;
        try { worker.execute(() -> { if (state.ownsResource()) beginRestore("session_lost"); }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    public synchronized void close() {
        if (closing) return;
        closing = true;
        worker.execute(() -> { if (state.ownsResource()) beginRestore("executor_closed"); else shutdown(); });
    }

    /** Local cleanup only. A persisted session never authorizes start, join, confirm or renewal. */
    CompletableFuture<View> retryRecovery() {
        CompletableFuture<View> result = new CompletableFuture<>();
        enqueue(result, () -> {
            if (!state.ownsResource()) { result.complete(state.view()); return; }
            if (stopResult != null && !stopResult.isDone()) { pipe(stopResult, result); return; }
            stopResult = result; beginRestore("owner_process_restarted");
        });
        return result;
    }

    private Availability availabilityNow() {
        if (recoveryError != null || recovery == null) return new Availability(false, false, null, false,
                recoveryError == null ? "network_recovery_initializing" : recoveryError);
        if (wifi == null || connectivity == null) return new Availability(false, false, null, false, "network_service_missing");
        if (!permission(Manifest.permission.ACCESS_NETWORK_STATE) || !permission(Manifest.permission.ACCESS_WIFI_STATE))
            return new Availability(false, false, null, false, "network_observation_permission_denied");
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        Upstream upstream = type(caps);
        boolean metered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        boolean busy = state.ownsResource();
        boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        boolean client = !closing && !busy && !vpn && wifi.isWifiEnabled() && permission("android.permission.CHANGE_WIFI_STATE")
                && permission(Manifest.permission.ACCESS_FINE_LOCATION)
                && (permission("android.permission.NETWORK_SETTINGS") || permission("android.permission.NETWORK_SETUP_WIZARD")
                || android.os.Process.myUid() == android.os.Process.SYSTEM_UID);
        boolean existingAp = tethered.stream().anyMatch(t -> t.getType() == TetheringManager.TETHERING_WIFI);
        String error = closing ? "executor_closed" : busy ? "network_busy" : !registered || !snapshotReceived
                ? "tether_observer_unavailable" : existingAp ? "hotspot_already_active" : upstream == null
                ? "upstream_unavailable" : wifi.isWifiEnabled() && !concurrentWifiAndHotspot()
                ? "wifi_hotspot_concurrency_unavailable" : null;
        return new Availability(error == null, client, upstream, metered, error);
    }

    private void tickSafely() {
        try { tick(); }
        catch (RuntimeException error) {
            if (state.ownsResource() && state.view().phase() == Phase.STOPPING && now() >= restoreDeadline) finishRestore(false);
            else if (state.ownsResource() && state.view().phase() != Phase.RECOVERY_REQUIRED) failAndRestore("network_observation_failed");
        } finally { publishState(); }
    }
    private void tick() {
        if (recovery == null) return;
        View v = state.view(); if (v == null || !state.ownsResource()) return;
        if (recoveredOwner && !recoveryStarted) { recoveryStarted = true; beginRestore("owner_process_restarted"); }
        if (state.expire(now()) != null) beginRestore(state.view().error());
        v = state.view();
        if (v.phase() == Phase.STOPPING) { checkRestored(); return; }
        if (v.phase() == Phase.RECOVERY_REQUIRED) return;
        if (v.role() == Role.PROVIDER) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(actualUpstream);
            boolean owned = ownsAp();
            if (v.phase() == Phase.STARTING) {
                if (providerStarted && owned && caps != null) {
                    if (!validUpstream(caps)) { failAndRestore("upstream_changed"); return; }
                    try {
                        recovery.checkpoint(recoveryEntry());
                        if (state.ready(v.ticket(), type(caps), true, now())) startResult.complete(offer);
                        else beginRestore(state.view().error());
                    } catch (IOException | RuntimeException error) { failAndRestore("network_recovery_write_failed"); }
                }
            } else if (v.phase() == Phase.READY || v.phase() == Phase.ACTIVE) {
                if (!owned || !validUpstream(caps)) failAndRestore("shared_network_lost");
                // The authenticated consumer confirms its exact joined network and validated
                // default route. ColorOS does not always expose DHCP clients to this UID;
                // an aggregate client count is neither a peer identity nor a liveness proof.
                else if (confirmResult != null && !confirmResult.isDone()) {
                    if (state.attached(v.ticket(), true, now())) confirmResult.complete(state.view());
                }
            }
        } else {
            WifiInfo info = wifi.getConnectionInfo();
            boolean joined = info != null && info.getNetworkId() == addedNetwork && quote(offer.ssid()).equals(info.getSSID());
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            WifiInfo routedWifi = caps != null && caps.getTransportInfo() instanceof WifiInfo w ? w : null;
            boolean validated = joined && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && (routedWifi != null && routedWifi.getNetworkId() == addedNetwork
                        || connectivity.getActiveNetwork()!=null && connectivity.getActiveNetwork().equals(currentWifiNetwork()))
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if(v.phase()==Phase.STARTING)diagnostic("consumer joined="+joined+" routed="+(routedWifi!=null&&routedWifi.getNetworkId()==addedNetwork)
                +" default_wifi="+(connectivity.getActiveNetwork()!=null&&connectivity.getActiveNetwork().equals(currentWifiNetwork()))
                +" validated="+(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
            if (validated && v.phase() == Phase.STARTING) {
                if (state.ready(v.ticket(), offer.upstream(), true, now()) && state.attached(v.ticket(), true, now()))
                    joinResult.complete(state.view());
                else beginRestore(state.view().error());
            } else if (!validated && v.phase() == Phase.ACTIVE) failAndRestore("shared_network_lost");
        }
    }

    private Network currentWifiNetwork() {
        try{return (Network)WifiManager.class.getMethod("getCurrentNetwork").invoke(wifi);}
        catch(ReflectiveOperationException|RuntimeException unavailable){return null;}
    }
    private void diagnostic(String value){
        if(!value.equals(lastDiagnostic)){lastDiagnostic=value;android.util.Log.i("YueJieNetwork",value);}
    }
    private boolean validUpstream(NetworkCapabilities caps) {
        return requestedUpstream != null && requestedUpstream.equals(actualUpstream)
                && type(caps) == state.view().upstream() && (allowMetered
                || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }
    private boolean ownsAp() {
        if (ownedAp == null) return false;
        for (TetheringInterface t : tethered) {
            SoftApConfiguration c = t.getSoftApConfiguration();
            if (t.getType() == TetheringManager.TETHERING_WIFI && c != null
                    && ownedAp.getWifiSsid().equals(c.getWifiSsid())
                    && ownedAp.getPassphrase().equals(c.getPassphrase())) return true;
        }
        return false;
    }
    private void failAndRestore(String error) { if (state.ownsResource()) beginRestore(error); }
    private void beginRestore(String error) {
        View v = state.view();
        if (!state.ownsResource()) return;
        if (v.phase() == Phase.STOPPING && restoreDeadline != 0) return;
        state.stop(v.ticket(), error);
        if (startResult != null && !startResult.isDone()) startResult.completeExceptionally(new IllegalStateException(error == null ? "cancelled" : error));
        if (joinResult != null && !joinResult.isDone()) joinResult.completeExceptionally(new IllegalStateException(error == null ? "cancelled" : error));
        if (confirmResult != null && !confirmResult.isDone()) confirmResult.completeExceptionally(new IllegalStateException(error == null ? "cancelled" : error));
        restoreDeadline = now() + 15_000;
        stopRequested = false; networkRemoved = false; preserveExternalChoice = false;
        if (v.role() == Role.PROVIDER) {
            if (!recoveredOwner && !nativeStartIssued) { providerStartSettled = true; stopSucceeded = true; }
            else stopNativeProvider();
        } else {
            restoreConsumer();
        }
    }
    private void stopNativeProvider() {
        // Android's request stop uses fuzzy matching. It is safe to issue only while the actual
        // observed AP matches our private synthetic credentials and no other Wi-Fi AP is present.
        if (!providerPlan().stop()) return;
        if (recoveredOwner && !recovery.entry().providerSettled()) {
            NetworkShareRecovery.Entry e = recovery.entry();
            try {
                recovery.checkpoint(new NetworkShareRecovery.Entry(e.ownerPackage(), e.ownerUid(), e.ticket(),
                        e.role(), e.upstream(), e.ssid(), e.passphrase(), e.addedNetwork(), e.previousNetwork(),
                        e.previousFingerprint(), e.originalWifiEnabled(), e.consumerChanged(), true));
            } catch (IOException | RuntimeException error) { return; }
        }
        if (nativeRequest == null) {
            try { nativeRequest = createTetheringRequest(ownedAp); }
            catch (RuntimeException error) { return; }
        }
        Ticket expected = state.view().ticket();
        stopRequested = true; stopSnapshot = snapshotGeneration;
        try {
            tethering.stopTethering(nativeRequest, worker, new TetheringManager.StopTetheringCallback() {
                @Override public void onStopTetheringSucceeded() { if (current(expected)) stopSucceeded = true; }
                @Override public void onStopTetheringFailed(int error) { if (current(expected)) stopSucceeded = false; }
            });
        } catch (RuntimeException ignored) { stopSucceeded = false; }
    }
    private void checkRestored() {
        View v = state.view();
        boolean done;
        if (v.role() == Role.PROVIDER) {
            stopNativeProvider();
            done = providerPlan().restored();
        } else {
            done = restoreConsumer();
        }
        if (!done && now() < restoreDeadline) return;
        finishRestore(done);
    }
    private void finishRestore(boolean done) {
        if (done && recovery != null) {
            try { recovery.clearVerified(true); }
            catch (IOException | RuntimeException error) { done = false; recoveryError = "network_recovery_write_failed"; }
        }
        state.restored(state.view().ticket(), done);
        if (stopResult != null) stopResult.complete(state.view());
        if (done) {
            offer = null; ownedAp = null; nativeRequest = null; startResult = null;
            recoveredOwner = false; recoveryError = null;
            if (closing) shutdown();
        }
    }
    private void rejectBeforeChange(Ticket ticket) {
        state.stop(ticket, "precondition_failed"); state.restored(ticket, true);
        offer = null;
    }

    private void connectNetwork(int networkId) throws ReflectiveOperationException {
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        Ticket expected = state.view().ticket();
        Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onFailure")) worker.execute(() -> {
                View current = state.view();
                if (current != null && current.ticket().equals(expected) && current.phase() == Phase.STARTING)
                    failAndRestore("wifi_connect_rejected");
            });
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("toString")) return "NetworkShareWifiCallback";
            return null;
        });
        try { WifiManager.class.getMethod("connect", int.class, listener).invoke(wifi, networkId, callback); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof SecurityException denied) throw denied;
            throw error;
        }
    }
    private boolean mobileDataEnabled() {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA)) return false;
        // Check the injected OEM Context on every read, not the module APK manifest or an old UID snapshot.
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("cellular_policy_permission_denied");
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        TelephonyManager phone = context.getSystemService(TelephonyManager.class);
        if (subscriptions == null || phone == null) throw new IllegalStateException("cellular_policy_unavailable");
        try {
            List<SubscriptionInfo> active = readActiveSubscriptions(subscriptions);
            if (active == null) throw new IllegalStateException("cellular_policy_unavailable");
            for (SubscriptionInfo subscription : active) {
                if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("cellular_policy_permission_revoked");
                if (phone.createForSubscriptionId(subscription.getSubscriptionId()).isDataEnabled()) return true;
            }
            return false;
        } catch (SecurityException revoked) { throw new SecurityException("cellular_policy_permission_denied"); }
    }
    // Lint uses the module APK manifest, but this read executes in the authenticated OEM host.
    // The host's revocable permission is checked immediately and the service may still reject it.
    @SuppressLint("MissingPermission")
    private List<SubscriptionInfo> readActiveSubscriptions(SubscriptionManager subscriptions) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("cellular_policy_permission_denied");
        try { return subscriptions.getActiveSubscriptionInfoList(); }
        catch (SecurityException revoked) { throw new SecurityException("cellular_policy_permission_denied"); }
    }
    // Signature permission is held (or denied) by the OEM host, not declared by the module APK.
    // This suppression covers only this checked constructor call, not endpoint callers or lifecycle.
    @SuppressLint("MissingPermission")
    private TetheringManager.TetheringRequest createTetheringRequest(SoftApConfiguration configuration) {
        // This signature permission belongs to the authenticated OEM host; never request it in our APK.
        if (context.checkSelfPermission("android.permission.TETHER_PRIVILEGED") != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("tether_permission_denied");
        try {
            return new TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
                    .setSoftApConfiguration(configuration).build();
        } catch (SecurityException revoked) { throw new SecurityException("tether_permission_denied"); }
    }
    // The OEM host must hold both grants now; do not infer them from injection, UID or old snapshots.
    // Lint's module-manifest requirement is inapplicable here; revocation remains handled below.
    @SuppressLint("MissingPermission")
    private List<WifiConfiguration> readConfiguredNetworks() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("wifi_permission_denied");
        try {
            List<WifiConfiguration> raw=wifi.getConfiguredNetworks();
            if(raw==null)return null;
            // Android 17 can expose WPA2 and its auto-upgraded WPA3 security profile as
            // separate rows with the same networkId. They are one owned saved network.
            java.util.Map<Integer,WifiConfiguration> unique=new java.util.LinkedHashMap<>();
            for(WifiConfiguration config:raw){
                WifiConfiguration previous=unique.get(config.networkId);
                if(previous==null){unique.put(config.networkId,new WifiConfiguration(config));continue;}
                if(!java.util.Objects.equals(previous.SSID,config.SSID)
                    || !java.util.Objects.equals(previous.preSharedKey,config.preSharedKey)
                    || creatorUid(previous)!=creatorUid(config)
                    || !java.util.Objects.equals(creatorPackage(previous),creatorPackage(config)))
                    throw new SecurityException("wifi_snapshot_conflict");
                previous.allowedKeyManagement.or(config.allowedKeyManagement);
            }
            return new java.util.ArrayList<>(unique.values());
        } catch(ReflectiveOperationException unavailable){throw new SecurityException("wifi_snapshot_denied");}
        catch (SecurityException revoked) { throw new SecurityException("wifi_permission_denied"); }
    }
    private boolean concurrentWifiAndHotspot() {
        try { return Boolean.TRUE.equals(WifiManager.class.getMethod("isStaApConcurrencySupported").invoke(wifi)); }
        catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    private static Upstream type(NetworkCapabilities caps) {
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null;
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? Upstream.WIFI
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? Upstream.CELLULAR : null;
    }
    private void bind(String session) { if (closing) throw new IllegalStateException("executor_closed"); state.bind(session); }
    private Ticket ticket(String session, String request) {
        View v = state.view();
        if (v == null || !v.ticket().session().equals(session) || !v.ticket().request().equals(request))
            throw new IllegalStateException("stale_request");
        return v.ticket();
    }
    private void resetOwned() {
        providerStarted = false; providerStartSettled = false; providerStartFailed = false; stopSucceeded = false; networkRemoved = false;
        consumerChanged = false; previousNetwork = -1; addedNetwork = -1; restoreDeadline = 0;
        preserveExternalChoice = false; wifiClients = 0;
        previousFingerprint = null; recoveredOwner = false; recoveryStarted = false; stopRequested = false;
        nativeStartIssued = false; consumerMutationIssued = false;
        ownedAp = null; nativeRequest = null; offer = null; startResult = null; joinResult = null; stopResult = null; confirmResult = null;
    }
    private boolean permission(String name) { return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED; }
    private String secret(int bytes) {
        byte[] data = new byte[bytes]; random.nextBytes(data); StringBuilder b = new StringBuilder();
        for (byte n : data) b.append(String.format(java.util.Locale.ROOT, "%02x", n & 255)); return b.toString();
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static String quote(String value) { return "\"" + value + "\""; }
    private <T> CompletableFuture<T> submit(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>(); enqueue(result, () -> result.complete(action.get())); return result;
    }
    private void enqueue(CompletableFuture<?> result, Runnable action) {
        if (worker.isShutdown() || worker.getQueue().size() > 64) { result.completeExceptionally(new IllegalStateException("executor_unavailable")); return; }
        try { worker.execute(() -> { try { action.run(); } catch (RuntimeException error) { result.completeExceptionally(error); }
            finally { publishState(); } }); }
        catch (RuntimeException error) { result.completeExceptionally(error); }
    }
    private static <T> void pipe(CompletableFuture<T> from, CompletableFuture<T> to) {
        from.whenComplete((value, error) -> { if (error == null) to.complete(value); else to.completeExceptionally(error); });
    }
    private void shutdown() {
        if (registered) { tethering.unregisterTetheringEventCallback(events); registered = false; }
        if (recovery != null) try { recovery.close(); } catch (IOException ignored) { }
        worker.shutdown();
    }
    private void publishState() {
        View view = state.view();
        if (view == null || view.equals(lastPublished)) return;
        lastPublished = view;
        try { listener.accept(view); } catch (RuntimeException ignored) { /* Cleanup is independent of native observers. */ }
    }

    private NetworkShareRecovery.Entry recoveryEntry() {
        View v = state.view();
        return new NetworkShareRecovery.Entry(context.getPackageName(), android.os.Process.myUid(), v.ticket(),
                v.role(), v.upstream(), offer.ssid(), offer.passphrase(), addedNetwork, previousNetwork,
                previousFingerprint, originalWifiEnabled, consumerChanged,
                providerStartFailed || providerStarted && ownsAp());
    }
    private void loadRecovery(NetworkShareRecovery.Entry entry) {
        state.recover(entry.ticket(), entry.role(), entry.upstream());
        offer = new Offer(entry.ticket().session(), entry.ticket().request(), entry.ssid(), entry.passphrase(), entry.upstream());
        previousNetwork = entry.previousNetwork(); previousFingerprint = entry.previousFingerprint();
        addedNetwork = entry.addedNetwork(); originalWifiEnabled = entry.originalWifiEnabled();
        consumerChanged = entry.consumerChanged(); providerStartSettled = entry.providerSettled();
        recoveredOwner = true; consumerMutationIssued = consumerChanged; nativeStartIssued = entry.role() == Role.PROVIDER;
        if (entry.role() == Role.PROVIDER) {
            ownedAp = new SoftApConfiguration.Builder()
                    .setWifiSsid(WifiSsid.fromBytes(entry.ssid().getBytes(StandardCharsets.UTF_8)))
                    .setPassphrase(entry.passphrase(), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK).build();
            // No start call, no fabricated callback. Stop is created only after an exact AP observation.
        }
    }
    private boolean current(Ticket expected) { return state.view() != null && state.view().ticket().equals(expected); }
    private boolean onlyOwnedAp() {
        for (TetheringInterface iface : tethered) {
            if (iface.getType() != TetheringManager.TETHERING_WIFI) continue;
            SoftApConfiguration config = iface.getSoftApConfiguration();
            if (ownedAp == null || config == null || !ownedAp.getWifiSsid().equals(config.getWifiSsid())
                    || !ownedAp.getPassphrase().equals(config.getPassphrase())) return false;
        }
        return true;
    }
    private NetworkShareRecoveryPlan.Provider providerPlan() {
        return NetworkShareRecoveryPlan.provider(new NetworkShareRecoveryPlan.ProviderProgress(
                nativeStartIssued, recoveredOwner, recovery != null && recovery.entry() != null && recovery.entry().providerSettled(),
                providerStartSettled, providerStartFailed, stopRequested, stopSucceeded, snapshotGeneration > stopSnapshot),
                new NetworkShareRecoveryPlan.ProviderSnapshot(snapshotReceived, ownsAp(), onlyOwnedAp(),
                        wifi != null && wifi.isWifiEnabled() == originalWifiEnabled, apIdentitiesKnown()));
    }
    private boolean apIdentitiesKnown() {
        for (TetheringInterface iface : tethered) {
            if (iface.getType() != TetheringManager.TETHERING_WIFI) continue;
            SoftApConfiguration config = iface.getSoftApConfiguration();
            if (config == null || config.getWifiSsid() == null || ownedAp != null
                    && ownedAp.getWifiSsid().equals(config.getWifiSsid()) && config.getPassphrase() == null) return false;
        }
        return true;
    }
    private boolean restoreConsumer() {
        if (!consumerMutationIssued && !recoveredOwner) return true;
        try {
            List<WifiConfiguration> configured = readConfiguredNetworks();
            if (configured == null) return false;
            List<NetworkShareRecoveryPlan.Config> snapshots = new java.util.ArrayList<>();
            for (WifiConfiguration config : configured) snapshots.add(new NetworkShareRecoveryPlan.Config(
                    config.networkId, unquote(config.SSID), unquote(config.preSharedKey), fingerprint(config),
                    creatorUid(config), creatorPackage(config), wpaPersonal(config)));
            WifiInfo info = wifi.getConnectionInfo();
            NetworkShareRecovery.Entry entry = recovery != null && recovery.entry() != null ? recovery.entry() : recoveryEntry();
            NetworkShareRecoveryPlan.Consumer plan = NetworkShareRecoveryPlan.consumer(entry, snapshots,
                    info == null ? -1 : info.getNetworkId(), info == null ? null : unquote(info.getSSID()));
            // Even when the previous connection was removed by the user, delete only our exact resource.
            if (plan.remove() >= 0 && !networkRemoved) networkRemoved = wifi.removeNetwork(plan.remove());
            if (plan.error() != null) {
                for(var config:snapshots)if(entry.ssid().equals(config.ssid()))diagnostic(plan.error()
                    +" uid_match="+(config.creatorUid()==entry.ownerUid())+" package_match="+entry.ownerPackage().equals(config.creatorPackage())
                    +" personal="+config.wpa2Only()+" id_match="+(config.id()==entry.addedNetwork())
                    +" credential_masked="+(config.credential()==null||"*".equals(config.credential()))
                    +" credential_match="+entry.passphrase().equals(config.credential()));
                return false;
            }
            if (plan.reconnect() >= 0 && !preserveExternalChoice) {
                connectNetwork(plan.reconnect()); preserveExternalChoice = true; // one attempt per recovery window
            }
            return plan.restored();
        } catch (ReflectiveOperationException | RuntimeException error) { return false; }
    }
    private static boolean wpaPersonal(WifiConfiguration config) {
        // Android may add SAE to a WPA2 profile during automatic security upgrade.
        // Keep the creator, generated identity, credential and network-id ownership checks.
        java.util.BitSet remaining=(java.util.BitSet)config.allowedKeyManagement.clone();
        remaining.clear(WifiConfiguration.KeyMgmt.WPA_PSK);remaining.clear(WifiConfiguration.KeyMgmt.SAE);
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)&&remaining.isEmpty();
    }
    private static String unquote(String value) {
        return value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
    private static String fingerprint(WifiConfiguration config) throws ReflectiveOperationException {
        // Persist a digest only; never store the user's original SSID or credentials in our ledger.
        String identity = config.SSID + "\n" + creatorUid(config) + "\n" + creatorPackage(config) + "\n" + config.allowedKeyManagement;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static int creatorUid(WifiConfiguration config) throws ReflectiveOperationException {
        return (Integer) WifiConfiguration.class.getField("creatorUid").get(config);
    }
    private static String creatorPackage(WifiConfiguration config) throws ReflectiveOperationException {
        return (String) WifiConfiguration.class.getField("creatorName").get(config);
    }
}
