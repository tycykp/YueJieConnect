package io.github.qiangjian.connect;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.NetworkShareSession.*;

/** Synthetic authenticated-envelope/lease fixtures, not evidence of a real stable control bearer. */
public class NetworkShareRpcTest {
    private static final String SESSION = "b".repeat(64), SHARE = "synthetic:share";
    private static final NetworkShareController.Target TARGET = new NetworkShareController.Target("qj:synthetic", SESSION);
    private static NetworkShareEndpoint.Offer offer() {
        return new NetworkShareEndpoint.Offer(SESSION, SHARE, "QJ-0123456789abcdef", "a".repeat(32), Upstream.WIFI);
    }
    private static Map<String, Object> receipt(Role role, Phase phase, String error) {
        return NetworkShareWire.receipt(new View(new Ticket(SESSION, SHARE, 1), role, Upstream.WIFI, phase, 0, error));
    }
    private static final class Link implements NetworkShareRpc.Link {
        Object lease = new Object(); boolean throwing, hold, rotateBeforeDispatch; int calls;
        NetworkShareController.Target sentTarget; String requestId; Map<String, Object> payload;
        Map<String, ?> response = NetworkShareWire.offer(offer());
        CompletableFuture<NetworkShareRpc.Reply> pending = new CompletableFuture<>();
        public Object stableLease(NetworkShareController.Target t) { return lease; }
        public CompletableFuture<NetworkShareRpc.Reply> request(NetworkShareController.Target target, String request,
                Object expectedLease, Map<String, Object> body) {
            if (throwing) throw new IllegalStateException("link_closed");
            if (rotateBeforeDispatch) lease = new Object();
            if (expectedLease != null && expectedLease != lease) throw new IllegalStateException("stable_control_lost");
            calls++; sentTarget = target; requestId = request; payload = body;
            return hold ? pending : CompletableFuture.completedFuture(new NetworkShareRpc.Reply(target, request, response));
        }
    }
    @Test public void ordinaryLanWithoutVerifiedLeaseNeverDispatchesStart() {
        Link link = new Link(); link.lease = null; NetworkShareRpc rpc = new NetworkShareRpc(link);
        assertFalse(rpc.ready(TARGET));
        assertThrows(Exception.class, () -> rpc.start(TARGET, SHARE, Upstream.WIFI, false).get());
        assertEquals(0, link.calls);
    }
    @Test public void distinctRpcIdsKeepTheLongShareIdentityAndSessionOutOfPayload() throws Exception {
        Link link = new Link(); NetworkShareRpc rpc = new NetworkShareRpc(link);
        assertEquals(offer(), rpc.start(TARGET, SHARE, Upstream.WIFI, false).get());
        String first = link.requestId;
        link.response = receipt(Role.PROVIDER, Phase.ACTIVE, null); rpc.confirm(TARGET, SHARE).get();
        assertNotEquals(first, link.requestId); assertEquals(SHARE, link.payload.get("share_id"));
        assertFalse(link.payload.containsKey("session_id")); assertEquals(TARGET, link.sentTarget);
    }
    @Test public void wrongEnvelopeRequestAndTargetCannotCompleteAnOffer() {
        for (boolean wrongRequest : new boolean[]{true, false}) {
            Link link = new Link(); link.hold = true;
            CompletableFuture<?> result = new NetworkShareRpc(link).start(TARGET, SHARE, Upstream.WIFI, false);
            NetworkShareController.Target target = wrongRequest ? TARGET : new NetworkShareController.Target("qj:other", SESSION);
            link.pending.complete(new NetworkShareRpc.Reply(target, wrongRequest ? "other" : link.requestId, link.response));
            assertThrows(Exception.class, result::get);
        }
    }
    @Test public void replacementLeaseInSameSessionCannotAdoptLateSuccess() {
        Link link = new Link(); link.hold = true;
        CompletableFuture<?> result = new NetworkShareRpc(link).start(TARGET, SHARE, Upstream.WIFI, false);
        link.lease = new Object();
        link.pending.complete(new NetworkShareRpc.Reply(TARGET, link.requestId, link.response));
        assertThrows(Exception.class, result::get);
    }
    @Test public void stopAfterRevocationPreservesUnverifiedRecovery() throws Exception {
        Link link = new Link(); link.lease = null; link.response = receipt(Role.CONSUMER, Phase.RECOVERY_REQUIRED, "network_restore_unverified");
        View view = new NetworkShareRpc(link).stop(TARGET, SHARE).get();
        assertEquals(Phase.RECOVERY_REQUIRED, view.phase()); assertEquals("network_restore_unverified", view.error());
        assertEquals(1, link.calls);
    }
    @Test public void pendingCleanupCannotBecomeStoppedAndActiveCannotCarryAnError() {
        Link link = new Link(); NetworkShareRpc rpc = new NetworkShareRpc(link);
        link.response = receipt(Role.PROVIDER, Phase.STOPPING, null);
        assertThrows(Exception.class, () -> rpc.stop(TARGET, SHARE).get());
        link.response = receipt(Role.PROVIDER, Phase.ACTIVE, "network_restore_unverified");
        assertThrows(Exception.class, () -> rpc.confirm(TARGET, SHARE).get());
    }
    @Test public void offerAndConsumerReceiptMustMatchRequestedUpstreamAndRole() {
        Link link = new Link(); NetworkShareRpc rpc = new NetworkShareRpc(link);
        assertThrows(Exception.class, () -> rpc.start(TARGET, SHARE, Upstream.CELLULAR, true).get());
        link.response = receipt(Role.PROVIDER, Phase.ACTIVE, null);
        assertThrows(Exception.class, () -> rpc.join(TARGET, SHARE, offer(), false).get());
    }
    @Test public void wrongOfferIdentityIsRejectedBeforeCredentialsLeaveTheProcess() {
        Link link = new Link(); NetworkShareRpc rpc = new NetworkShareRpc(link);
        assertThrows(Exception.class, () -> rpc.join(TARGET, "other", offer(), false).get());
        assertEquals(0, link.calls);
    }
    @Test public void noImplicitCellularConsentAndNoSynchronousFailureEscapesFuture() {
        Link link = new Link(); NetworkShareRpc rpc = new NetworkShareRpc(link);
        assertThrows(Exception.class, () -> rpc.start(TARGET, SHARE, Upstream.CELLULAR, false).get());
        assertEquals(0, link.calls);
        link.throwing = true; assertTrue(rpc.start(TARGET, SHARE, Upstream.WIFI, false).isCompletedExceptionally());
    }
    @Test public void replyDiagnosticNeverContainsSyntheticCredentials() {
        assertFalse(new NetworkShareRpc.Reply(TARGET, "r", NetworkShareWire.offer(offer())).toString().contains(offer().passphrase()));
    }
    @Test public void leaseReplacementBetweenPreflightAndDispatchCannotSendNativeStart() {
        Link link = new Link(); link.rotateBeforeDispatch = true;
        assertTrue(new NetworkShareRpc(link).start(TARGET, SHARE, Upstream.WIFI, false).isCompletedExceptionally());
        assertEquals(0, link.calls);
    }
}
