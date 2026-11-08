package io.github.qiangjian.connect;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferSendCoordinatorTest {
    private File root, outgoing, incoming;
    private FileTransferEngine receiver;
    private FileTransferProtocol protocol;
    private FileTransferSendCoordinator coordinator;
    private final FileTransferEngine.Key key = FileTransferEngine.Key.create("synthetic-pad", "b".repeat(64));
    private final AtomicBoolean route = new AtomicBoolean(true);
    private final List<String> calls = new ArrayList<>();
    private CompletableFuture<Void> decision;
    private FileTransferEngine.Offer offered;
    private byte[] published;
    private boolean revokeAfterChunk;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("qj-file-consent-").toFile();
        outgoing = new File(root, "out"); incoming = new File(root, "in");
        receiver = new FileTransferEngine(incoming, ignored -> true, System::currentTimeMillis);
        protocol = new FileTransferProtocol(receiver);
        decision = new CompletableFuture<>();
        coordinator = create(Runnable::run);
    }
    private FileTransferSendCoordinator create(Executor preparer) {
        FileTransferClient client = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key id) { return true; }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key id, String op, JSONObject payload) {
                calls.add(op);
                try {
                    JSONObject ack = protocol.execute(id.peer(), id.session(), op, payload);
                    if (revokeAfterChunk && op.equals("file_chunk")) route.set(false);
                    return CompletableFuture.completedFuture(ack);
                } catch (Exception e) { return CompletableFuture.failedFuture(e); }
            }
        }, Runnable::run);
        return new FileTransferSendCoordinator(client, (id, offer) -> { offered = offer; return decision; }, preparer);
    }
    private FileTransferSendCoordinator.Source source(int count) {
        return (gate, id) -> {
            assertTrue(gate.current(id));
            byte[] bytes = new byte[count]; for (int i = 0; i < count; i++) bytes[i] = (byte) (i % 251);
            return FileTransferSource.snapshot(outgoing, new ByteArrayInputStream(bytes), "synthetic.bin", "application/octet-stream", count);
        };
    }
    private CompletableFuture<FileTransferEngine.Receipt> send(int count) { return coordinator.send(key, id -> route.get(), source(count)); }
    private void accept() throws Exception {
        receiver.authorize(key, offered, new FileTransferEngine.Sink() {
            public void publishVerified(File file, FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate,
                                        FileTransferEngine.Key id) throws IOException { published = Files.readAllBytes(file.toPath()); }
            public void rollback() { published = null; }
        });
        decision.complete(null);
    }
    private void failed(CompletableFuture<?> future, String code) throws Exception {
        try { future.get(); fail("must fail"); }
        catch (ExecutionException error) { assertEquals(code, error.getCause().getMessage()); }
    }
    private int snapshots() { return outgoing.exists() ? outgoing.list().length : 0; }
    @After public void cleanup() throws Exception {
        coordinator.close(); receiver.close();
        assertEquals(0, snapshots()); assertEquals(0, incoming.list().length);
        if (outgoing.exists()) Files.delete(outgoing.toPath());
        Files.delete(incoming.toPath()); Files.delete(root.toPath());
    }

    @Test public void noBytesBeforeDecisionThenRealReceiverReceipt() throws Exception {
        var result = send(7001);
        assertFalse(result.isDone()); assertTrue(calls.isEmpty()); assertEquals(1, snapshots());
        assertEquals(7001, offered.size());
        accept();
        assertEquals(7001, result.get().size()); assertEquals(7001, published.length);
        assertEquals(List.of("file_offer", "file_chunk", "file_chunk", "file_chunk", "file_chunk", "file_commit"), calls);
        assertEquals(0, snapshots());
    }
    @Test public void declinedConsentDeletesSnapshotWithoutSending() throws Exception {
        var result = send(5); decision.completeExceptionally(new IOException("declined"));
        failed(result, "receive_consent_failed"); assertTrue(calls.isEmpty()); assertEquals(0, snapshots());
    }
    @Test public void cancelWaitingConsentCancelsDecisionAndDeletesSnapshot() {
        var result = send(5); coordinator.cancel(key);
        assertTrue(result.isCancelled()); assertTrue(decision.isCancelled()); assertEquals(0, snapshots()); assertTrue(calls.isEmpty());
    }
    @Test public void invalidatedNativeRouteStopsBeforeConsent() {
        var result = send(5); route.set(false); coordinator.sessionChanged();
        assertTrue(result.isCancelled()); assertEquals(0, snapshots()); assertTrue(calls.isEmpty());
    }
    @Test public void nativeRouteChangesEvenWhileAuthenticatedSessionLives() throws Exception {
        revokeAfterChunk = true;
        var result = send(6000); accept(); failed(result, "stale_session");
        assertEquals(List.of("file_offer", "file_chunk", "file_cancel"), calls);
        assertNull(published); assertEquals(0, incoming.list().length); assertEquals(0, snapshots());
    }
    @Test public void cancelledQueuedPreparationStillConsumesCapacityUntilItExits() throws Exception {
        coordinator.close(); List<Runnable> queue = new ArrayList<>(); coordinator = create(queue::add);
        for (int i = 0; i < 4; i++) {
            var id = FileTransferEngine.Key.create(key.peer(), key.session());
            var result = coordinator.send(id, ignored -> true, source(1)); result.cancel(true);
        }
        failed(send(1), "file_busy"); assertEquals(4, queue.size());
        queue.forEach(Runnable::run); assertEquals(0, snapshots()); assertTrue(calls.isEmpty());
        var next = send(1); assertFalse(next.isDone()); assertEquals(5, queue.size());
        next.cancel(true); queue.get(4).run();
    }
    @Test public void duplicateTransferDoesNotReplacePendingConsent() throws Exception {
        var first = send(4); failed(send(2), "duplicate_transfer");
        assertFalse(first.isDone()); assertEquals(4, offered.size()); coordinator.cancel(key);
    }
    @Test public void rejectedPreparationReleasesCapacityAndNeverOpensSource() throws Exception {
        coordinator.close(); coordinator = create(command -> { throw new java.util.concurrent.RejectedExecutionException(); });
        for (int i = 0; i < 6; i++) failed(send(1), "file_busy");
        assertEquals(0, snapshots()); assertTrue(calls.isEmpty());
    }
    @Test public void lateUncancellableConsentAfterCloseNeverSends() {
        decision = new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        var result = send(1); coordinator.close(); decision.complete(null);
        assertTrue(result.isCancelled()); assertTrue(calls.isEmpty()); assertEquals(0, snapshots());
    }
}
