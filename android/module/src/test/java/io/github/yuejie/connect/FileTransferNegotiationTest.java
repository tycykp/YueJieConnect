package io.github.yuejie.connect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

public class FileTransferNegotiationTest {
    private final AtomicLong clock = new AtomicLong(1);
    private final AtomicBoolean allowed = new AtomicBoolean(true), nativeDecision = new AtomicBoolean(true);
    private final AtomicReference<String> session = new AtomicReference<>("a".repeat(64));
    private FileTransferNegotiation negotiation;
    private File root;
    private FileTransferEngine engine;
    private byte[] received;
    private FileTransferEngine.Key key(int serial) {
        return new FileTransferEngine.Key("synthetic-pad", session.get(), String.format("%032x", serial));
    }
    private FileTransferNegotiation.Metadata metadata(int serial, FileTransferNegotiation.Purpose purpose) {
        return new FileTransferNegotiation.Metadata(purpose, String.format("%032x", serial),
                new FileTransferEngine.Offer("synthetic.bin", "application/octet-stream", 3,
                    FileTransferEngine.hex(FileTransferEngine.sha256().digest(new byte[]{1,2,3}))));
    }
    @Before public void setup() throws Exception {
        negotiation = new FileTransferNegotiation(k -> allowed.get() && session.get().equals(k.session()), clock::get);
        negotiation.sessionChanged(session.get());
        root = Files.createTempDirectory("yj-file-prepare-").toFile();
        engine = new FileTransferEngine(root, k -> allowed.get() && session.get().equals(k.session()), clock::get);
    }
    @After public void cleanup() throws Exception {
        negotiation.close(); engine.close();
        assertEquals(0, root.list().length); Files.delete(root.toPath());
    }
    private interface Action { void run() throws Exception; }
    private void fails(String code, Action action) throws Exception {
        try { action.run(); fail(code); } catch (IOException error) { assertEquals(code, error.getMessage()); }
    }
    @Test public void preparedMetadataIsNotAuthorizationOrApproval() throws Exception {
        var key = key(1); var metadata = metadata(1, FileTransferNegotiation.Purpose.CLIPBOARD);
        var first = negotiation.prepare(key, metadata);
        assertFalse(first.approved()); assertTrue(first.firstSeen()); assertEquals(0, root.list().length);
        fails("receive_consent_required", () -> engine.begin(key, metadata.offer()));
        assertSame(first.prepared(), negotiation.find(FileTransferNegotiation.Purpose.CLIPBOARD, metadata.nativeOwnerToken()));
        assertNull(negotiation.find(FileTransferNegotiation.Purpose.DRAG, metadata.nativeOwnerToken()));
        assertFalse(negotiation.prepare(key, metadata).firstSeen());
        assertSame(first.prepared(), negotiation.prepare(key, metadata).prepared());
    }
    @Test public void actualLocalEngineAcceptancePrecedesApprovedAndVerifiedByteResult() throws Exception {
        var key = key(1); var metadata = metadata(1, FileTransferNegotiation.Purpose.CLIPBOARD);
        var prepared = negotiation.prepare(key, metadata).prepared();
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        assertTrue(negotiation.approve(prepared, k -> nativeDecision.get(), accepted, () -> true));
        assertFalse(negotiation.prepare(key, metadata).approved());
        engine.authorizeTracked(key, metadata.offer(), new FileTransferEngine.Sink() {
            public void publishVerified(File staged, FileTransferEngine.Offer actual,
                    FileTransferEngine.SessionGate gate, FileTransferEngine.Key k) throws IOException {
                received = Files.readAllBytes(staged.toPath());
                assertEquals(actual.sha256(), FileTransferEngine.hex(FileTransferEngine.sha256().digest(received)));
            }
            public void rollback() { received = null; }
        });
        accepted.complete(null); assertTrue(negotiation.prepare(key, metadata).approved());
        engine.begin(key, metadata.offer()); engine.append(key, 0, new byte[]{1,2,3});
        assertEquals(metadata.offer().sha256(), engine.commit(key).sha256());
        assertArrayEquals(new byte[]{1,2,3}, received);
    }
    @Test public void foreignPreparedObjectAndMetadataCannotTakeAnExistingDecision() throws Exception {
        var key = key(1); var metadata = metadata(1, FileTransferNegotiation.Purpose.DRAG);
        var prepared = negotiation.prepare(key, metadata).prepared();
        assertFalse(negotiation.approve(new FileTransferNegotiation.Prepared(key, metadata), k -> true,
                CompletableFuture.completedFuture(null), () -> true));
        fails("consent_metadata_mismatch", () -> negotiation.prepare(key, metadata(1, FileTransferNegotiation.Purpose.APP_RELAY)));
        var different = new FileTransferNegotiation.Metadata(metadata.purpose(), metadata.nativeOwnerToken(),
                new FileTransferEngine.Offer("other.bin", metadata.offer().mime(), 3, metadata.offer().sha256()));
        fails("consent_metadata_mismatch", () -> negotiation.prepare(key, different));
        assertFalse(negotiation.prepare(key, metadata).approved());
        assertTrue(negotiation.approve(prepared, k -> true, CompletableFuture.completedFuture(null), () -> true));
    }
    @Test public void nativeDecisionMustRemainCurrentAndLateAcceptedCannotReviveFailure() throws Exception {
        var key = key(1); var metadata = metadata(1, FileTransferNegotiation.Purpose.DRAG);
        var prepared = negotiation.prepare(key, metadata).prepared();
        CompletableFuture<Void> accepted = new CompletableFuture<>(); AtomicInteger cleaned = new AtomicInteger();
        nativeDecision.set(false);
        assertFalse(negotiation.approve(prepared, k -> nativeDecision.get(), accepted, () -> {cleaned.incrementAndGet();return true;}));
        nativeDecision.set(true);
        assertTrue(negotiation.approve(prepared, k -> nativeDecision.get(), accepted, () -> {cleaned.incrementAndGet();return true;}));
        nativeDecision.set(false); negotiation.maintain();
        assertEquals(1, cleaned.get()); accepted.complete(null); nativeDecision.set(true);
        fails("stale_session", () -> negotiation.prepare(key, metadata));
        assertEquals(1, cleaned.get());
    }
    @Test public void rejectedEndpointAcceptanceNeverBecomesApproved() throws Exception {
        var key = key(1); var metadata = metadata(1, FileTransferNegotiation.Purpose.APP_RELAY);
        var prepared = negotiation.prepare(key, metadata).prepared();
        assertTrue(negotiation.approve(prepared, k -> true,
                CompletableFuture.failedFuture(new IOException("synthetic-denied")), () -> true));
        fails("receive_consent_failed", () -> negotiation.prepare(key, metadata));
        assertNull(negotiation.find(metadata.purpose(), metadata.nativeOwnerToken()));
    }
    @Test public void preparePollCannotExtendDecisionDeadlineButApprovedReceiveCanProgress() throws Exception {
        var drag = metadata(1, FileTransferNegotiation.Purpose.DRAG); var dragKey=key(1);
        negotiation.prepare(dragKey, drag); clock.addAndGet(29_000); negotiation.prepare(dragKey, drag);
        clock.addAndGet(1_001); fails("request_timeout", () -> negotiation.prepare(dragKey, drag));
        var clipboard = metadata(2, FileTransferNegotiation.Purpose.CLIPBOARD); var clipboardKey=key(2);
        var prepared = negotiation.prepare(clipboardKey, clipboard).prepared();
        clock.addAndGet(200_000); assertFalse(negotiation.prepare(clipboardKey, clipboard).approved());
        assertTrue(negotiation.approve(prepared, k -> nativeDecision.get(), CompletableFuture.completedFuture(null), () -> true));
        clock.addAndGet(400_000); assertTrue(negotiation.prepare(clipboardKey, clipboard).approved());
    }
    @Test public void cleanupFailureRetainsCapacityAndRetriesWithoutReentrantRelease() throws Exception {
        FileTransferNegotiation.Prepared first = null;
        for (int i=1;i<=4;i++) {var p=negotiation.prepare(key(i),metadata(i,FileTransferNegotiation.Purpose.DRAG)).prepared();if(i==1)first=p;}
        AtomicBoolean cleaned = new AtomicBoolean(); AtomicInteger attempts = new AtomicInteger();
        assertTrue(negotiation.approve(first, k -> true, new CompletableFuture<>(), () -> {
            attempts.incrementAndGet(); negotiation.maintain(); return cleaned.get();
        }));
        negotiation.revoke(key(1)); assertEquals(1, attempts.get());
        fails("file_busy", () -> negotiation.prepare(key(5),metadata(5,FileTransferNegotiation.Purpose.DRAG)));
        cleaned.set(true); negotiation.maintain();
        assertNotNull(negotiation.prepare(key(5),metadata(5,FileTransferNegotiation.Purpose.DRAG)));
    }
    @Test public void releasedKeysAndOwnerTokensCannotBeReusedWithinSessionBudget() throws Exception {
        var original = negotiation.prepare(key(1), metadata(1,FileTransferNegotiation.Purpose.CLIPBOARD)).prepared();
        assertTrue(negotiation.released(original));
        fails("file_transfer_released", () -> negotiation.prepare(key(1),original.metadata()));
        fails("native_file_owner_reused", () -> negotiation.prepare(key(2),original.metadata()));
        for (int i=2;i<=256;i++) assertTrue(negotiation.released(negotiation.prepare(key(i),metadata(i,FileTransferNegotiation.Purpose.CLIPBOARD)).prepared()));
        fails("file_transfer_limit", () -> negotiation.prepare(key(257),metadata(257,FileTransferNegotiation.Purpose.CLIPBOARD)));
    }
    @Test public void grantRevocationOrSameSessionReconnectDoesNotResetTombstones() throws Exception {
        var oldKey=key(1); var metadata=metadata(1,FileTransferNegotiation.Purpose.CLIPBOARD);
        var prepared=negotiation.prepare(oldKey,metadata).prepared(); negotiation.released(prepared);
        allowed.set(false); negotiation.sessionChanged(null); negotiation.maintain();
        allowed.set(true); negotiation.sessionChanged(session.get());
        fails("file_transfer_released", () -> negotiation.prepare(oldKey,metadata));
        session.set("b".repeat(64)); negotiation.sessionChanged(session.get());
        assertTrue(negotiation.prepare(key(1),metadata).firstSeen());
    }
    @Test public void competingNativeOpensCanBindOnlyOneActualAuthorization() throws Exception {
        var metadata=metadata(1,FileTransferNegotiation.Purpose.CLIPBOARD);
        var prepared=negotiation.prepare(key(1),metadata).prepared();
        AtomicInteger winners = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        Runnable decide=() -> {try {start.await();if(negotiation.approve(prepared,k->true,new CompletableFuture<>(),()->true))winners.incrementAndGet();}
            catch(InterruptedException e){throw new AssertionError(e);}};
        Thread one=new Thread(decide),two=new Thread(decide);one.start();two.start();start.countDown();one.join(2_000);two.join(2_000);
        assertFalse(one.isAlive());assertFalse(two.isAlive());assertEquals(1,winners.get());
    }
}
