package io.github.yuejie.connect;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileTransferEngineTest {
    private File root;
    private FileTransferEngine engine;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(1);
    private final FileTransferEngine.Key key = new FileTransferEngine.Key("synthetic-peer", "a".repeat(64), "b".repeat(32));
    private int published, rolledBack;
    private byte[] stored;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("yj-files-test-").toFile();
        engine = new FileTransferEngine(root, k -> active.get() && k.session().equals(key.session()), clock::get);
    }
    @After public void cleanup() throws Exception {
        engine.close();
        assertEquals(0, root.list().length);
        Files.delete(root.toPath());
    }
    private FileTransferEngine.Offer offer(byte[] bytes) {
        return new FileTransferEngine.Offer("人工测试.bin", "application/octet-stream", bytes.length,
                FileTransferEngine.hex(FileTransferEngine.sha256().digest(bytes)));
    }
    private FileTransferEngine.Sink sink() {
        return new FileTransferEngine.Sink() {
            public void publishVerified(File file, FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate,
                                        FileTransferEngine.Key key) throws IOException {
                stored = Files.readAllBytes(file.toPath()); published++;
            }
            public void rollback() { stored = null; rolledBack++; }
        };
    }
    private void fails(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); } catch (IOException error) { assertEquals(code, error.getMessage()); }
    }
    private interface Action { void run() throws Exception; }

    @Test public void binaryMultiChunkRoundTripAndReceiptRetry() throws Exception {
        byte[] bytes = new byte[FileTransferEngine.CHUNK_BYTES * 2 + 17];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 13);
        FileTransferEngine.Offer offer = offer(bytes);
        engine.authorize(key, offer, sink());
        assertEquals(0, engine.begin(key, offer));
        for (int at = 0; at < bytes.length; at += FileTransferEngine.CHUNK_BYTES) {
            byte[] block = Arrays.copyOfRange(bytes, at, Math.min(at + FileTransferEngine.CHUNK_BYTES, bytes.length));
            assertEquals(at + block.length, engine.append(key, at, block));
        }
        FileTransferEngine.Receipt receipt = engine.commit(key);
        assertArrayEquals(bytes, stored);
        assertEquals(offer.sha256(), receipt.sha256());
        assertEquals(receipt, engine.commit(key));
        assertEquals(1, published);
        assertFalse(engine.cancel(key));
        active.set(false); engine.maintain();
        assertEquals(0, rolledBack);
    }

    @Test public void consentIsBoundToMetadataAndFullSession() throws Exception {
        var offer = offer(new byte[]{1});
        fails("receive_consent_required", () -> engine.begin(key, offer));
        engine.authorize(key, offer, sink());
        fails("consent_metadata_mismatch", () -> engine.begin(key, offer(new byte[]{2})));
        var other = new FileTransferEngine.Key(key.peer(), "c".repeat(64), key.transfer());
        fails("stale_session", () -> engine.begin(other, offer));
        fails("receive_consent_required", () -> engine.begin(new FileTransferEngine.Key("other-peer", key.session(), key.transfer()), offer));
        assertEquals(0, root.list().length);
    }

    @Test public void offsetOversizeAndPrematureCommitDoNotPublish() throws Exception {
        byte[] bytes = {1, 2}; var offer = offer(bytes);
        engine.authorize(key, offer, sink()); engine.begin(key, offer);
        fails("offset_mismatch", () -> engine.append(key, 1, new byte[]{1}));
        fails("invalid_chunk", () -> engine.append(key, 0, new byte[FileTransferEngine.CHUNK_BYTES + 1]));
        fails("file_length_exceeded", () -> engine.append(key, 0, new byte[]{1, 2, 3}));
        engine.append(key, 0, new byte[]{1});
        fails("offset_mismatch", () -> engine.append(key, 0, new byte[]{1}));
        fails("file_incomplete", () -> engine.commit(key));
        assertEquals(0, published);
        engine.append(key, 1, new byte[]{2}); engine.commit(key);
        assertArrayEquals(bytes, stored);
    }

    @Test public void hashMismatchDestroysOnlyStaging() throws Exception {
        var offer = offer(new byte[]{1});
        engine.authorize(key, offer, sink()); engine.begin(key, offer); engine.append(key, 0, new byte[]{2});
        fails("file_hash_mismatch", () -> engine.commit(key));
        assertEquals(0, published); assertEquals(1, rolledBack); assertEquals(0, root.list().length);
    }

    @Test public void emptyFilesStillRequireVerifiedCommit() throws Exception {
        var offer = offer(new byte[0]);
        engine.authorize(key, offer, sink()); engine.begin(key, offer); engine.commit(key);
        assertArrayEquals(new byte[0], stored); assertEquals(1, published);
    }

    @Test public void timeoutDisconnectCancelCleanResources() throws Exception {
        var offer = offer(new byte[]{1});
        engine.authorize(key, offer, sink()); engine.begin(key, offer);
        clock.addAndGet(FileTransferEngine.IDLE_MILLIS); engine.maintain();
        assertEquals(0, root.list().length);
        engine.authorize(key, offer, sink()); engine.begin(key, offer);
        active.set(false); engine.maintain(); assertEquals(0, root.list().length);
        active.set(true); engine.authorize(key, offer, sink()); engine.begin(key, offer);
        assertTrue(engine.cancel(key)); assertFalse(engine.cancel(key)); assertEquals(0, root.list().length);
    }

    @Test public void disconnectDuringNativePublishRollsBackNewObject() throws Exception {
        var offer = offer(new byte[0]);
        engine.authorize(key, offer, new FileTransferEngine.Sink() {
            public void publishVerified(File f, FileTransferEngine.Offer o, FileTransferEngine.SessionGate g, FileTransferEngine.Key k) {
                published++; active.set(false);
            }
            public void rollback() { rolledBack++; }
        });
        engine.begin(key, offer);
        fails("stale_session", () -> engine.commit(key));
        assertEquals(1, published); assertEquals(1, rolledBack); assertEquals(0, root.list().length);
    }

    @Test public void nativeWriteFailureNeverProducesReceipt() throws Exception {
        var offer = offer(new byte[0]);
        engine.authorize(key, offer, new FileTransferEngine.Sink() {
            public void publishVerified(File f, FileTransferEngine.Offer o, FileTransferEngine.SessionGate g, FileTransferEngine.Key k) throws IOException {
                throw new IOException("native_file_hash_mismatch");
            }
            public void rollback() { rolledBack++; }
        });
        engine.begin(key, offer);
        fails("native_file_hash_mismatch", () -> engine.commit(key)); assertEquals(1, rolledBack);
    }

    @Test public void grantsAndReceiptsHaveBoundedCapacity() throws Exception {
        for (int i = 0; i < FileTransferEngine.MAX_TRANSFERS; i++)
            engine.authorize(FileTransferEngine.Key.create(key.peer(), key.session()), offer(new byte[0]), sink());
        fails("file_busy", () -> engine.authorize(key, offer(new byte[0]), sink()));
    }

    @Test public void maliciousNamesAndInvalidSizesRejected() {
        for (String name : new String[]{"../escape", "C:\\test", "/absolute", "..", "a\u0000b", "\u202eevil"}) {
            try { new FileTransferEngine.Offer(name, "text/plain", 0, "0".repeat(64)); fail(name); }
            catch (IllegalArgumentException expected) { }
        }
        try { new FileTransferEngine.Offer("ok", "text/plain", FileTransferEngine.MAX_BYTES + 1, "0".repeat(64)); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void snapshotOwnsInputAndIsImmutable() throws Exception {
        byte[] bytes = {4, 5, 6}; AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(bytes) { @Override public void close() { closed.set(true); } };
        try (FileTransferSource source = FileTransferSource.snapshot(root, input, "synthetic.bin", "application/octet-stream", 3)) {
            bytes[0] = 99; assertTrue(closed.get());
            try (var stream = source.open()) { assertArrayEquals(new byte[]{4, 5, 6}, stream.readAllBytes()); }
        }
    }

    @Test public void snapshotSizeMismatchClosesInputAndRemovesPartial() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2}) { @Override public void close() { closed.set(true); } };
        fails("source_size_mismatch", () -> FileTransferSource.snapshot(root, input, "synthetic.bin", "application/octet-stream", 1));
        assertTrue(closed.get()); assertEquals(0, root.list().length);
    }

    @Test public void failedCleanupRetainsOwnershipForMaintenanceRetry() throws Exception {
        engine.authorize(key, offer(new byte[0]), new FileTransferEngine.Sink() {
            public void publishVerified(File f, FileTransferEngine.Offer o, FileTransferEngine.SessionGate g, FileTransferEngine.Key k) { }
            public void rollback() throws IOException { if (++rolledBack == 1) throw new IOException("temporary_provider_failure"); }
        });
        fails("temporary_provider_failure", () -> engine.cancel(key));
        engine.maintain();
        assertEquals(2, rolledBack);
        assertFalse(engine.cancel(key));
    }
}
