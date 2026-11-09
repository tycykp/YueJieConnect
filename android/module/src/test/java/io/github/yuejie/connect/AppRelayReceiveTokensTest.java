package io.github.yuejie.connect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppRelayReceiveTokensTest {
    @Test public void localNativeCancellationRevokesOnlyItsOwnToken() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicLong clock = new AtomicLong(); Destination destination = new Destination();
        var key = FileTransferEngine.Key.create("peer", SESSION); var offer = offer();
        try (var engine = new FileTransferEngine(temporary.newFolder(), ignored -> current.get(), clock::get);
             var tokens = new AppRelayReceiveTokens<String>("peer", SESSION, ignored -> current.get())) {
            tokens.authorize(key, offer, (k, o) -> lease(engine, k, o, destination, current, clock)).join();
            assertThrows(IOException.class, () -> tokens.discard(new FileTransferEngine.Key("foreign", key.session(), key.transfer())));
            tokens.discard(key); assertEquals(1, destination.deleted); assertTrue(tokens.empty());
            assertThrows(IOException.class, () -> engine.begin(key, offer));
        }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SESSION = "a".repeat(64);
    private static final byte[] BYTES = "synthetic new relay document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final class Destination implements FileTransferReceiveLease.Destination<String> {
        byte[] bytes;
        boolean cleanupFails;
        int deleted;
        public String value() { return "synthetic-local-document"; }
        public void publishVerified(File file, FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
            bytes = Files.readAllBytes(file.toPath());
        }
        public void verify(FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
            if (!Arrays.equals(BYTES, bytes)) throw new IOException("tampered");
        }
        public void rollback() throws IOException { if (cleanupFails) throw new IOException("cleanup_failed"); deleted++; bytes = null; }
    }
    private FileTransferEngine.Offer offer() throws Exception {
        return new FileTransferEngine.Offer("synthetic.txt", "text/plain", BYTES.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)));
    }
    private FileTransferReceiveLease<String> lease(FileTransferEngine engine, FileTransferEngine.Key key, FileTransferEngine.Offer offer,
            Destination destination, AtomicBoolean current, AtomicLong clock) {
        var lease = new FileTransferReceiveLease<>(key, offer, ignored -> current.get(), destination, new FileTransferReceiveLease.Authorizer() {
            public CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key k, FileTransferEngine.Offer o, FileTransferEngine.Sink target) {
                try { return CompletableFuture.completedFuture(engine.authorizeTracked(k, o, target)); }
                catch (IOException error) { return CompletableFuture.failedFuture(error); }
            }
            public CompletableFuture<Void> revoke(FileTransferEngine.Key k) {
                try { engine.cancel(k); return CompletableFuture.completedFuture(null); }
                catch (IOException error) { return CompletableFuture.failedFuture(error); }
            }
        }, clock::get);
        lease.authorize(); return lease;
    }
    @Test public void tokenRequiresFinalEngineCommitAndExactMimeThenSingleRead() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicLong clock = new AtomicLong(); Destination destination = new Destination();
        var key = FileTransferEngine.Key.create("peer", SESSION); var offer = offer();
        try (var engine = new FileTransferEngine(temporary.newFolder(), ignored -> current.get(), clock::get);
             var tokens = new AppRelayReceiveTokens<String>("peer", SESSION, ignored -> current.get())) {
            tokens.authorize(key, offer, (k, o) -> lease(engine, k, o, destination, current, clock)).join();
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer.mime()));
            engine.begin(key, offer); engine.append(key, 0, BYTES); engine.commit(key);
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), "image/png"));
            var read = tokens.acquire(key.transfer(), offer.mime()); assertEquals("synthetic-local-document", read.value());
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer.mime()));
            read.close(); tokens.maintain(); assertTrue(tokens.empty()); assertEquals(1, destination.deleted);
            assertThrows(IOException.class, read::value);
        }
    }
    @Test public void remotePeerAndDuplicateCannotCreateDestination() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicLong clock = new AtomicLong(); int[] created = {0};
        var key = FileTransferEngine.Key.create("peer", SESSION); var offer = offer();
        try (var engine = new FileTransferEngine(temporary.newFolder(), ignored -> true, clock::get);
             var tokens = new AppRelayReceiveTokens<String>("peer", SESSION, ignored -> true)) {
            AppRelayReceiveTokens.Factory<String> factory = (k, o) -> { created[0]++; return lease(engine, k, o, new Destination(), current, clock); };
            assertThrows(IOException.class, () -> tokens.authorize(FileTransferEngine.Key.create("foreign", SESSION), offer, factory));
            assertEquals(0, created[0]); tokens.authorize(key, offer, factory).join();
            assertThrows(IOException.class, () -> tokens.authorize(key, offer, factory)); assertEquals(1, created[0]);
        }
    }
    @Test public void revocationInvalidatesReadAndRetainsOnlyFileOwnersCleanupForRetry() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicLong clock = new AtomicLong(); Destination destination = new Destination();
        var key = FileTransferEngine.Key.create("peer", SESSION); var offer = offer();
        try (var engine = new FileTransferEngine(temporary.newFolder(), ignored -> current.get(), clock::get)) {
            var tokens = new AppRelayReceiveTokens<String>("peer", SESSION, ignored -> current.get());
            tokens.authorize(key, offer, (k, o) -> lease(engine, k, o, destination, current, clock)).join();
            engine.begin(key, offer); engine.append(key, 0, BYTES); engine.commit(key);
            destination.bytes = new byte[]{0}; assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer.mime()));
            destination.bytes = BYTES; var read = tokens.acquire(key.transfer(), offer.mime());
            current.set(false); destination.cleanupFails = true;
            assertThrows(IOException.class, read::value); assertThrows(IOException.class, tokens::close); assertFalse(tokens.empty());
            destination.cleanupFails = false; tokens.maintain(); assertTrue(tokens.empty()); assertEquals(1, destination.deleted);
        }
    }
}
