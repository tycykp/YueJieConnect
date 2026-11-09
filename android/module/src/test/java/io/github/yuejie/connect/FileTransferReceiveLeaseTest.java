package io.github.yuejie.connect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferReceiveLeaseTest {
    final FileTransferEngine.Key key = new FileTransferEngine.Key("synthetic-peer", "a".repeat(64), "b".repeat(32));
    final AtomicBoolean current = new AtomicBoolean(true);
    final AtomicLong clock = new AtomicLong(1);
    File root;
    FileTransferEngine engine;
    FileTransferReceiveLease<byte[]> lease;
    byte[] stored;
    int deleted, verifyCalls;
    boolean cleanupFails, corrupt, revokeDuringPublish;
    CompletableFuture<FileTransferEngine.Authorization> deferred;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("yj-receive-lease-").toFile();
        engine = new FileTransferEngine(root, k -> current.get(), clock::get);
    }
    @After public void cleanup() throws Exception {
        cleanupFails = false;
        if (lease != null) lease.close();
        engine.close();
        assertEquals(0, root.list().length); Files.delete(root.toPath());
    }
    FileTransferEngine.Offer offer(byte[] bytes) {
        return new FileTransferEngine.Offer("synthetic.bin", "application/octet-stream", bytes.length,
                FileTransferEngine.hex(FileTransferEngine.sha256().digest(bytes)));
    }
    FileTransferReceiveLease<byte[]> prepare(byte[] bytes) {
        return new FileTransferReceiveLease<>(key, offer(bytes), k -> current.get(), new FileTransferReceiveLease.Destination<>() {
            public byte[] value() { return stored; }
            public void publishVerified(File file, FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate,
                    FileTransferEngine.Key key) throws IOException {
                stored = Files.readAllBytes(file.toPath());
                if (revokeDuringPublish) current.set(false);
                if (!gate.current(key)) throw new IOException("stale_session");
            }
            public void verify(FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
                verifyCalls++;
                if (corrupt) throw new IOException("native_file_hash_mismatch");
            }
            public void rollback() throws IOException {
                if (cleanupFails) throw new IOException("destination_cleanup_failed");
                deleted++; stored = null;
            }
        }, new FileTransferReceiveLease.Authorizer() {
            public CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key k,
                    FileTransferEngine.Offer o, FileTransferEngine.Sink sink) {
                try {
                    FileTransferEngine.Authorization a = engine.authorizeTracked(k, o, sink);
                    if (deferred != null) { deferred.complete(a); return new CompletableFuture<>(); }
                    return CompletableFuture.completedFuture(a);
                } catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
            public CompletableFuture<Void> revoke(FileTransferEngine.Key k) {
                try { engine.cancel(k); return CompletableFuture.completedFuture(null); }
                catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
            }
        }, clock::get);
    }
    void transfer(byte[] bytes) throws Exception {
        engine.begin(key, offer(bytes));
        for (int at = 0; at < bytes.length; at += FileTransferEngine.CHUNK_BYTES)
            engine.append(key, at, Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + FileTransferEngine.CHUNK_BYTES)));
        engine.commit(key);
    }
    void fails(String code, Action action) throws Exception {
        try { action.run(); fail(code); } catch (IOException failure) { assertEquals(code, failure.getMessage()); }
    }
    interface Action { void run() throws Exception; }

    @Test public void commitThenSingleClaimReturnsActualBytesAndDeletesOnce() throws Exception {
        byte[] bytes = new byte[FileTransferEngine.CHUNK_BYTES * 2 + 13];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
        lease = prepare(bytes); lease.authorize(); lease.accepted().join();
        assertFalse(lease.committed().isDone());
        fails("file_not_committed", () -> lease.claim(key, offer(bytes)));
        transfer(bytes);
        assertEquals(engine.commit(key), lease.committed().join());
        assertArrayEquals(bytes, lease.claim(key, offer(bytes))); assertEquals(1, verifyCalls);
        fails("file_already_claimed", () -> lease.claim(key, offer(bytes)));
        lease.close(); lease.close(); assertEquals(1, deleted);
    }
    @Test public void emptyFileHasRealCommitAndCancelledObserverDoesNotCancelOwner() throws Exception {
        byte[] bytes = new byte[0]; lease = prepare(bytes); lease.authorize();
        lease.committed().cancel(false); lease.accepted().cancel(false);
        transfer(bytes); assertArrayEquals(bytes, lease.claim(key, offer(bytes)));
    }
    @Test public void noNativeDecisionCleansDestinationAndCannotAuthorize() throws Exception {
        byte[] bytes = {1}; current.set(false); lease = prepare(bytes); lease.authorize();
        assertTrue(lease.accepted().isCompletedExceptionally()); assertTrue(lease.disposed()); assertEquals(1, deleted);
        current.set(true); fails("receive_consent_required", () -> engine.begin(key, offer(bytes)));
    }
    @Test public void endpointFailureKeepsFailedDeletionOwnedForRetry() throws Exception {
        byte[] bytes = {1}; engine.close(); cleanupFails = true; lease = prepare(bytes); lease.authorize();
        assertTrue(lease.accepted().isCompletedExceptionally()); assertFalse(lease.disposed());
        cleanupFails = false; lease.maintain(); assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void cancellationBeforeCommitCannotPublishOrClaim() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); engine.begin(key, offer(bytes));
        lease.close(); assertTrue(lease.committed().isCompletedExceptionally());
        fails("receive_consent_required", () -> engine.append(key, 0, bytes));
        fails("stale_session", () -> lease.claim(key, offer(bytes))); assertEquals(1, deleted);
    }
    @Test public void postCommitMutationAndWrongSessionCannotBeClaimed() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); transfer(bytes);
        FileTransferEngine.Key other = new FileTransferEngine.Key("synthetic-other", key.session(), key.transfer());
        fails("consent_metadata_mismatch", () -> lease.claim(other, offer(bytes)));
        corrupt = true; fails("native_file_hash_mismatch", () -> lease.claim(key, offer(bytes)));
        corrupt = false; assertArrayEquals(bytes, lease.claim(key, offer(bytes)));
    }
    @Test public void engineInactivityExpiresReceivingAndRetainsCleanupRetry() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); engine.begin(key, offer(bytes));
        clock.addAndGet(FileTransferEngine.IDLE_MILLIS + 1); engine.maintain(); lease.maintain();
        assertTrue(lease.committed().isCompletedExceptionally()); assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void chunkProgressCanOutliveInitialDecisionDeadline() throws Exception {
        byte[] bytes = {1,2}; lease = prepare(bytes); lease.authorize(); engine.begin(key, offer(bytes));
        clock.addAndGet(40_000); engine.append(key, 0, new byte[]{1});
        clock.addAndGet(40_000); engine.append(key, 1, new byte[]{2});
        engine.commit(key); assertArrayEquals(bytes, lease.claim(key, offer(bytes)));
    }
    @Test public void readyDocumentExpiresAndSessionRevokeDeletesCommittedObject() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); transfer(bytes);
        clock.addAndGet(FileTransferReceiveLease.CONSUME_MILLIS + 1); lease.maintain();
        assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void finalEngineRouteCheckFailureNeverResolvesCommitSuccess() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); revokeDuringPublish = true;
        fails("stale_session", () -> transfer(bytes));
        assertTrue(lease.committed().isCompletedExceptionally()); assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void checksumFailureDoesNotExposeDestination() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); engine.begin(key, offer(bytes));
        engine.append(key, 0, new byte[]{2}); fails("file_hash_mismatch", () -> engine.commit(key));
        assertTrue(lease.committed().isCompletedExceptionally()); assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void sameNativeOwnerCanReopenVerifiedBytesAfterSingleClaim() throws Exception {
        byte[] bytes = {1, 7, 3}; lease = prepare(bytes); lease.authorize();
        fails("file_not_claimed", () -> lease.reopenClaimed(key, offer(bytes)));
        transfer(bytes);
        fails("file_not_claimed", () -> lease.reopenClaimed(key, offer(bytes)));
        assertArrayEquals(bytes, lease.claim(key, offer(bytes)));
        assertArrayEquals(bytes, lease.reopenClaimed(key, offer(bytes)));
        assertArrayEquals(bytes, lease.reopenClaimed(key, offer(bytes)));
        assertEquals(3, verifyCalls);
        fails("file_already_claimed", () -> lease.claim(key, offer(bytes)));
    }
    @Test public void reopenedReadRejectsChangedIdentityMetadataAndBytes() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); transfer(bytes); lease.claim(key, offer(bytes));
        FileTransferEngine.Key other = new FileTransferEngine.Key("synthetic-other", key.session(), key.transfer());
        fails("consent_metadata_mismatch", () -> lease.reopenClaimed(other, offer(bytes)));
        FileTransferEngine.Offer altered = new FileTransferEngine.Offer("different.bin", "application/octet-stream", 1, offer(bytes).sha256());
        fails("consent_metadata_mismatch", () -> lease.reopenClaimed(key, altered));
        assertEquals(1, verifyCalls);
        corrupt = true; fails("native_file_hash_mismatch", () -> lease.reopenClaimed(key, offer(bytes)));
        assertEquals(2, verifyCalls);
    }
    @Test public void reopenedReadCannotExtendConsumerLeaseOrSurviveRouteRevocation() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); transfer(bytes); lease.claim(key, offer(bytes));
        clock.addAndGet(FileTransferReceiveLease.CONSUME_MILLIS - 1);
        lease.reopenClaimed(key, offer(bytes));
        clock.addAndGet(2);
        fails("stale_session", () -> lease.reopenClaimed(key, offer(bytes)));
        lease.maintain(); assertTrue(lease.disposed()); assertEquals(1, deleted);
    }
    @Test public void reopenedReadAfterNativeRevokeOrCloseNeverExposesDestination() throws Exception {
        byte[] bytes = {1}; lease = prepare(bytes); lease.authorize(); transfer(bytes); lease.claim(key, offer(bytes));
        current.set(false); fails("stale_session", () -> lease.reopenClaimed(key, offer(bytes)));
        assertEquals(1, verifyCalls);
        lease.close(); current.set(true);
        fails("stale_session", () -> lease.reopenClaimed(key, offer(bytes)));
    }
}
