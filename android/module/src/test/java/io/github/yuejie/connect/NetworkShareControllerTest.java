package io.github.yuejie.connect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.NetworkShareSession.*;

public class NetworkShareControllerTest {
    private static final String SESSION = "c".repeat(64);
    private static final NetworkShareController.Target TARGET = new NetworkShareController.Target("yj:synthetic", SESSION);
    private static View result(String request, Role role, Phase phase) {
        return new View(new Ticket(SESSION, request, 1), role, Upstream.WIFI, phase, 120_000, null);
    }
    private static NetworkShareEndpoint.Offer offer(String request) {
        return new NetworkShareEndpoint.Offer(SESSION, request, "YJ-0123456789abcdef", "a".repeat(32), Upstream.WIFI);
    }
    private static final class Local implements NetworkShareController.Local {
        final AtomicInteger joins = new AtomicInteger(), stops = new AtomicInteger();
        Role role = Role.CONSUMER;
        public CompletableFuture<NetworkShareEndpoint.Availability> availability() {
            return CompletableFuture.completedFuture(new NetworkShareEndpoint.Availability(true, true, Upstream.WIFI, false, null));
        }
        public CompletableFuture<NetworkShareEndpoint.Offer> startProvider(String session, String request, Upstream upstream, boolean allowed) {
            role = Role.PROVIDER; return CompletableFuture.completedFuture(offer(request));
        }
        public CompletableFuture<View> joinConsumer(String session, String request, NetworkShareEndpoint.Offer offer, boolean allowed) {
            role = Role.CONSUMER; joins.incrementAndGet(); return CompletableFuture.completedFuture(result(request, role, Phase.ACTIVE));
        }
        public CompletableFuture<View> confirmConsumer(String session, String request) { return CompletableFuture.completedFuture(result(request, role, Phase.ACTIVE)); }
        public CompletableFuture<View> stop(String session, String request) { stops.incrementAndGet(); return CompletableFuture.completedFuture(result(request, role, Phase.STOPPED)); }
        public CompletableFuture<View> renew(String session, String request) { return CompletableFuture.completedFuture(result(request, role, Phase.ACTIVE)); }
        public void close() {}
    }
    private static final class Remote implements NetworkShareController.Transport {
        boolean ready = true, holdStart, badConfirmation, failRestore, throwAvailability, emptyOffer, loseControlAfterStart, loseControlAfterConfirm;
        volatile String request;
        Role role = Role.PROVIDER;
        CompletableFuture<NetworkShareEndpoint.Offer> pending = new CompletableFuture<>();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final AtomicInteger stops = new AtomicInteger(), confirmations = new AtomicInteger();
        public boolean ready(NetworkShareController.Target t) { return ready; }
        public CompletableFuture<NetworkShareEndpoint.Availability> availability(NetworkShareController.Target t) {
            if (throwAvailability) throw new IllegalStateException("binder_disconnected");
            return CompletableFuture.completedFuture(new NetworkShareEndpoint.Availability(true, true, Upstream.WIFI, false, null));
        }
        public CompletableFuture<NetworkShareEndpoint.Offer> start(NetworkShareController.Target t, String request, Upstream upstream, boolean allowed) {
            this.request = request; role = Role.PROVIDER; started.complete(null);
            if (loseControlAfterStart) ready = false;
            return holdStart ? pending : CompletableFuture.completedFuture(emptyOffer ? null : offer(request));
        }
        public CompletableFuture<View> join(NetworkShareController.Target t, String request, NetworkShareEndpoint.Offer offer, boolean allowed) {
            role = Role.CONSUMER; return CompletableFuture.completedFuture(result(request, role, Phase.ACTIVE));
        }
        public CompletableFuture<View> confirm(NetworkShareController.Target t, String request) {
            if (loseControlAfterConfirm) ready = false;
            confirmations.incrementAndGet(); return CompletableFuture.completedFuture(result(badConfirmation ? "wrong" : request, role, Phase.ACTIVE));
        }
        public CompletableFuture<View> stop(NetworkShareController.Target t, String request) {
            stops.incrementAndGet(); return CompletableFuture.completedFuture(result(request, role, failRestore ? Phase.RECOVERY_REQUIRED : Phase.STOPPED));
        }
        public CompletableFuture<View> renew(NetworkShareController.Target t, String request) { return CompletableFuture.completedFuture(result(request, role, Phase.ACTIVE)); }
    }
    @Test public void validatesBothExecutorsBeforeNativeSuccess() throws Exception {
        Local local = new Local(); Remote remote = new Remote();
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            assertEquals(Phase.ACTIVE, c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.joins.get()); assertEquals(1, remote.confirmations.get());
            assertEquals(Phase.STOPPED, c.stop().get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.stops.get()); assertEquals(1, remote.stops.get());
        } finally { c.close(); }
    }
    @Test public void phoneCanProvideToRemoteTablet() throws Exception {
        Local local = new Local(); Remote remote = new Remote();
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            assertEquals(Phase.ACTIVE, c.startProviding(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS).phase());
            assertEquals(Role.PROVIDER, local.role); assertEquals(Role.CONSUMER, remote.role);
            assertEquals(Phase.STOPPED, c.stop().get(2, TimeUnit.SECONDS).phase());
        } finally { c.close(); }
    }
    @Test public void lateProviderOfferAfterCancelCannotJoinWifi() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.holdStart = true;
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            CompletableFuture<?> starting = c.start(TARGET, Upstream.WIFI, false);
            remote.started.get(2, TimeUnit.SECONDS); c.stop().get(2, TimeUnit.SECONDS);
            remote.pending.complete(offer(remote.request));
            // A queued stop is a deterministic worker barrier after the queued late completion.
            c.stop().get(2, TimeUnit.SECONDS);
            assertEquals(0, local.joins.get()); assertTrue(starting.isCompletedExceptionally());
        } finally { c.close(); }
    }
    @Test public void wrongReceiptRestoresBothSides() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.badConfirmation = true;
        CompletableFuture<NetworkShareController.Status> terminal = new CompletableFuture<>();
        NetworkShareController c = new NetworkShareController(local, remote, v -> { if (v.phase() == Phase.FAILED) terminal.complete(v); });
        try {
            assertThrows(Exception.class, () -> c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals(Phase.FAILED, terminal.get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.stops.get()); assertEquals(1, remote.stops.get());
        } finally { c.close(); }
    }
    @Test public void failedRestoreIsNotReportedStopped() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.failRestore = true;
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS);
            assertEquals(Phase.RECOVERY_REQUIRED, c.stop().get(2, TimeUnit.SECONDS).phase());
            remote.failRestore = false;
            assertEquals(Phase.STOPPED, c.stop().get(2, TimeUnit.SECONDS).phase());
        } finally { c.close(); }
    }
    @Test public void ordinaryLanIsNotSufficientToStart() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.ready = false;
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            assertThrows(Exception.class, () -> c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals(0, local.joins.get()); assertFalse(remote.started.isDone());
        } finally { c.close(); }
    }
    @Test public void synchronousTransportFailureCannotLeavePendingStart() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.throwAvailability = true;
        CompletableFuture<NetworkShareController.Status> terminal = new CompletableFuture<>();
        NetworkShareController c = new NetworkShareController(local, remote, v -> { if (v.phase() == Phase.FAILED) terminal.complete(v); });
        try {
            assertThrows(Exception.class, () -> c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals(Phase.FAILED, terminal.get(2, TimeUnit.SECONDS).phase());
            assertEquals(0, local.joins.get()); assertFalse(remote.started.isDone());
        } finally { c.close(); }
    }
    @Test public void missingOfferRestoresProviderWithoutJoining() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.emptyOffer = true;
        CompletableFuture<NetworkShareController.Status> terminal = new CompletableFuture<>();
        NetworkShareController c = new NetworkShareController(local, remote, v -> { if (v.phase() == Phase.FAILED) terminal.complete(v); });
        try {
            assertThrows(Exception.class, () -> c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals(Phase.FAILED, terminal.get(2, TimeUnit.SECONDS).phase());
            assertEquals(0, local.joins.get()); assertEquals(1, remote.stops.get());
        } finally { c.close(); }
    }
    @Test public void losingSessionAfterVerifiedStopDoesNotRestartCleanup() throws Exception {
        Local local = new Local(); Remote remote = new Remote();
        NetworkShareController c = new NetworkShareController(local, remote, v -> {});
        try {
            c.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS);
            c.stop().get(2, TimeUnit.SECONDS); c.sessionLost();
            assertEquals(Phase.STOPPED, c.stop().get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.stops.get()); assertEquals(1, remote.stops.get());
        } finally { c.close(); }
    }
    @Test public void nativeMappingsDoNotTreatReadyAsActive() {
        assertEquals("2", NetworkSharePolicy.oplus(Phase.READY, true).get("share_network_for_card"));
        assertEquals("0", NetworkSharePolicy.oplus(Phase.READY, true).get("share_network"));
        assertEquals(2, NetworkSharePolicy.xiaomi(Phase.READY, Upstream.WIFI, true));
        assertEquals(4, NetworkSharePolicy.xiaomi(Phase.ACTIVE, Upstream.WIFI, true));
        assertEquals(3, NetworkSharePolicy.xiaomi(Phase.ACTIVE, Upstream.CELLULAR, true));
        assertEquals(2, NetworkSharePolicy.xiaomi(Phase.STOPPING, Upstream.WIFI, true));
        assertFalse(NetworkSharePolicy.oplus(Phase.STOPPING, true).containsKey("share_network"));
        assertFalse(NetworkSharePolicy.oplus(Phase.RECOVERY_REQUIRED, true).containsKey("share_network"));
    }
    @Test public void controlLossAfterOfferRestoresProviderBeforeSwitchingLocalWifi() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.loseControlAfterStart = true;
        CompletableFuture<NetworkShareController.Status> ended = new CompletableFuture<>();
        NetworkShareController controller = new NetworkShareController(local, remote, s -> { if (s.phase() == Phase.FAILED) ended.complete(s); });
        try {
            assertThrows(Exception.class, () -> controller.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals("stable_control_lost", ended.get(2, TimeUnit.SECONDS).error());
            assertEquals(0, local.joins.get()); assertEquals(1, remote.stops.get());
        } finally { controller.close(); }
    }
    @Test public void confirmationCannotPublishActiveAfterControlBearerWasLost() throws Exception {
        Local local = new Local(); Remote remote = new Remote(); remote.loseControlAfterConfirm = true;
        CompletableFuture<NetworkShareController.Status> ended = new CompletableFuture<>();
        AtomicInteger active = new AtomicInteger();
        NetworkShareController controller = new NetworkShareController(local, remote, s -> {
            if (s.phase() == Phase.ACTIVE) active.incrementAndGet(); if (s.phase() == Phase.FAILED) ended.complete(s);
        });
        try {
            assertThrows(Exception.class, () -> controller.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
            assertEquals("stable_control_lost", ended.get(2, TimeUnit.SECONDS).error());
            assertEquals(0, active.get()); assertEquals(1, local.stops.get()); assertEquals(1, remote.stops.get());
        } finally { controller.close(); }
    }
    @Test public void incomingNativeEndpointStateCanBeCancelledFromItsLocalOemCard() throws Exception {
        Local local = new Local(); local.role = Role.PROVIDER;
        Remote remote = new Remote(); remote.role = Role.CONSUMER;
        CompletableFuture<NetworkShareController.Status> observed = new CompletableFuture<>();
        NetworkShareController controller = new NetworkShareController(local, remote, s -> { if (s.phase() == Phase.ACTIVE) observed.complete(s); });
        try {
            controller.observeLocal(TARGET, result("synthetic:incoming", Role.PROVIDER, Phase.ACTIVE));
            assertEquals("synthetic:incoming", observed.get(2, TimeUnit.SECONDS).request());
            assertEquals(Phase.STOPPED, controller.stop().get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.stops.get()); assertEquals(1, remote.stops.get());
            controller.observeLocal(TARGET, result("synthetic:incoming", Role.PROVIDER, Phase.ACTIVE));
            assertEquals(Phase.STOPPED, controller.stop().get(2, TimeUnit.SECONDS).phase());
            assertEquals(1, local.stops.get());
        } finally { controller.close(); }
    }
    @Test public void staleSessionEndpointObservationCannotPublishNativeSuccess() throws Exception {
        Local local = new Local(); Remote remote = new Remote();
        NetworkShareController controller = new NetworkShareController(local, remote, s -> {});
        try {
            controller.observeLocal(new NetworkShareController.Target("yj:synthetic", "f".repeat(64)), result("synthetic:old", Role.PROVIDER, Phase.ACTIVE));
            assertEquals(Phase.STOPPED, controller.stop().get(2, TimeUnit.SECONDS).phase());
            assertEquals(0, local.stops.get());
        } finally { controller.close(); }
    }
    @Test public void lateOlderLocalGenerationCannotReopenAnAlreadyStoppedNativeShare() throws Exception {
        Local local = new Local(); local.role = Role.PROVIDER;
        Remote remote = new Remote(); remote.role = Role.CONSUMER;
        NetworkShareController controller = new NetworkShareController(local, remote, s -> {});
        try {
            View newest = new View(new Ticket(SESSION, "synthetic:new", 4), Role.PROVIDER, Upstream.WIFI, Phase.ACTIVE, 99_999, null);
            controller.observeLocal(TARGET, newest); controller.stop().get(2, TimeUnit.SECONDS);
            controller.observeLocal(TARGET, result("synthetic:older", Role.PROVIDER, Phase.ACTIVE));
            assertEquals("synthetic:new", controller.stop().get(2, TimeUnit.SECONDS).request());
            assertEquals(1, local.stops.get());
        } finally { controller.close(); }
    }
    @Test public void duplicateCloseAndLateCallbacksCannotLeaveAStartWaitingForever() throws Exception {
        NetworkShareController controller = new NetworkShareController(new Local(), new Remote(), s -> {});
        controller.close(); controller.close(); controller.sessionLost();
        controller.observeLocal(TARGET, result("synthetic:late", Role.PROVIDER, Phase.ACTIVE));
        assertThrows(Exception.class, () -> controller.start(TARGET, Upstream.WIFI, false).get(2, TimeUnit.SECONDS));
        assertEquals(Phase.STOPPED, controller.stop().get(2, TimeUnit.SECONDS).phase());
    }
}
