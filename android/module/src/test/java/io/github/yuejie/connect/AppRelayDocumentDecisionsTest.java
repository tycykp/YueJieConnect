package io.github.yuejie.connect;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelayDocumentDecisionsTest {
    private static final String PKG = "test.synthetic.office";
    private static final AppRelayPeer PEER = new AppRelayPeer("actual-native", "peer", "a".repeat(64));
    private static final FileTransferEngine.Key KEY = new FileTransferEngine.Key("peer", "a".repeat(64), "1".repeat(32));
    private static final FileTransferEngine.Offer OFFER = new FileTransferEngine.Offer("synthetic.txt", "text/plain", 0,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private AppRelayCatalog.Update candidate(AppRelayCatalog.Kind kind) {
        return new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(PKG, "Synthetic office", kind, true, true, 1, "")));
    }
    @Test public void directoryAndRemoteMetadataAloneCannotAllocateAFile() {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0); AtomicInteger allocations = new AtomicInteger();
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32));
        var denied = decisions.approve("c".repeat(32), KEY, OFFER, (k,o,current) -> { allocations.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        assertTrue(denied.isCompletedExceptionally()); assertEquals(0, allocations.get());
        assertFalse(decisions.ownsClick(PEER, PKG, "c".repeat(32)));
        assertTrue(decisions.ownsClick(PEER, PKG, "b".repeat(32)));
    }
    @Test public void appClickDoesNotBecomeDocumentConsent() {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.APP), "b".repeat(32));
        assertEquals("", decisions.clicked(PEER, PKG, () -> true));
        assertTrue(decisions.approve("", KEY, OFFER, (k,o,c) -> { fail("APP cannot allocate document"); return null; }).isCompletedExceptionally());
    }
    @Test public void actualClickBindsPrepareAndOnlyRealLeaseAcceptanceApproves() throws Exception {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32));
        String token = decisions.clicked(PEER, PKG, () -> true); assertTrue(token.matches("[0-9a-f]{32}"));
        FileTransferNegotiation negotiation = new FileTransferNegotiation(k -> true, () -> 0);
        var meta = new FileTransferNegotiation.Metadata(FileTransferNegotiation.Purpose.APP_RELAY, token, OFFER);
        var prepared = negotiation.prepare(KEY, meta); assertFalse(prepared.approved());
        CompletableFuture<Void> nativeAccepted = new CompletableFuture<>();
        var result = decisions.approve(token, KEY, OFFER, (k,o,current) -> {
            assertTrue(negotiation.approve(prepared.prepared(), value -> value.equals(k) && current.getAsBoolean(), nativeAccepted, () -> true));
            return nativeAccepted;
        });
        assertFalse(result.isDone()); assertFalse(negotiation.prepare(KEY, meta).approved());
        nativeAccepted.complete(null); result.join(); assertTrue(negotiation.prepare(KEY, meta).approved());
    }
    @Test public void tokenCannotSwitchPeerTransferOrMetadataAndDuplicateDoesNotAllocateAgain() {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32)); String token = decisions.clicked(PEER, PKG, () -> true);
        AtomicInteger allocations = new AtomicInteger();
        var accepted = decisions.approve(token, KEY, OFFER, (k,o,c) -> { allocations.incrementAndGet(); return CompletableFuture.completedFuture(null); }); accepted.join();
        decisions.approve(token, KEY, OFFER, (k,o,c) -> { fail("duplicate allocated"); return null; }).join();
        var other = new FileTransferEngine.Key("peer", "a".repeat(64), "2".repeat(32));
        assertTrue(decisions.approve(token, other, OFFER, (k,o,c) -> null).isCompletedExceptionally());
        var foreign = new FileTransferEngine.Key("foreign", "a".repeat(64), "1".repeat(32));
        assertTrue(decisions.approve(token, foreign, OFFER, (k,o,c) -> null).isCompletedExceptionally()); assertEquals(1, allocations.get());
    }
    @Test public void nativeHideAfterClickDoesNotCancelButRevokedSessionDoes() {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32));
        var live = new java.util.concurrent.atomic.AtomicBoolean(true); String token = decisions.clicked(PEER, PKG, live::get);
        decisions.clear(PEER); // The real type9 acknowledgement withdraws the original icon.
        AtomicReference<BooleanSupplier> gate = new AtomicReference<>();
        decisions.approve(token, KEY, OFFER, (k,o,current) -> { gate.set(current); return CompletableFuture.completedFuture(null); }).join();
        assertTrue(gate.get().getAsBoolean()); live.set(false); decisions.maintain(); assertFalse(gate.get().getAsBoolean());
    }
    @Test public void successfulNativeLaunchRetainsReadLeaseUntilBoundedExpiryAndCloseRevokes() {
        AtomicLong clock = new AtomicLong(); AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(clock::get);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32)); String token = decisions.clicked(PEER, PKG, () -> true);
        AtomicReference<BooleanSupplier> gate = new AtomicReference<>();
        decisions.approve(token, KEY, OFFER, (k,o,c) -> { gate.set(c); return CompletableFuture.completedFuture(null); }).join();
        decisions.finished(token, new AppRelayTasks.Result("synthetic", AppRelayTasks.State.COMPLETED, "foreground_observed", PKG, 7));
        clock.set(299999); assertTrue(gate.get().getAsBoolean()); clock.set(300000); decisions.maintain(); assertFalse(gate.get().getAsBoolean());
        decisions.close(); assertThrows(IllegalStateException.class, () -> decisions.clicked(PEER, PKG, () -> true));
    }
    @Test public void fileReceiverCannotConsumeDocumentClickForClipboardPurpose() throws Exception {
        AppRelayDocumentDecisions decisions = new AppRelayDocumentDecisions(() -> 0);
        decisions.consumed(PEER, candidate(AppRelayCatalog.Kind.DOCUMENT), "b".repeat(32)); String token = decisions.clicked(PEER, PKG, () -> true);
        FileTransferNegotiation negotiation = new FileTransferNegotiation(k -> true, () -> 0);
        var prepared = negotiation.prepare(KEY, new FileTransferNegotiation.Metadata(FileTransferNegotiation.Purpose.CLIPBOARD, token, OFFER));
        new AppRelayDocumentNegotiation(null, decisions).prepared(prepared.prepared(), null, negotiation);
        var actualDocument = new FileTransferEngine.Key("peer", "a".repeat(64), "2".repeat(32));
        decisions.approve(token, actualDocument, OFFER, (k,o,current) -> CompletableFuture.completedFuture(null)).join();
        assertFalse(negotiation.prepare(KEY, prepared.prepared().metadata()).approved());
    }
}
