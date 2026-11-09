package io.github.yuejie.connect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelayFileTokensTest {
    private static final String SESSION = "a".repeat(64);
    private static final byte[] BYTES = "synthetic relay document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final class Sink implements FileTransferEngine.Sink {
        byte[] received;
        boolean cleanupFails;
        int deletions;
        @Override public void publishVerified(File file, FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws IOException {
            received = Files.readAllBytes(file.toPath());
        }
        @Override public void rollback() throws IOException { if (cleanupFails) throw new IOException("cleanup_failed"); received = null; deletions++; }
    }
    @Test public void realEngineCommitRequiredBeforeSingleUseLease() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicLong clock = new AtomicLong();
        var key = FileTransferEngine.Key.create("peer", SESSION); Sink sink = new Sink();
        var offer = offer();
        try (var tokens = new AppRelayFileTokens<String>("peer", SESSION, ignored -> current.get(), clock::get);
             var engine = new FileTransferEngine(Files.createTempDirectory("relay-test").toFile(), ignored -> current.get(), clock::get)) {
            engine.authorize(key, offer, tokens.track(key, offer, sink, "synthetic", value -> { if (!java.util.Arrays.equals(BYTES, sink.received)) throw new IOException("tampered"); }));
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer.mime()));
            engine.begin(key, offer); engine.append(key, 0, BYTES); engine.commit(key);
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), "image/png"));
            var lease = tokens.acquire(key.transfer(), offer.mime()); assertEquals("synthetic", lease.value());
            assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer.mime()));
            lease.close(); assertNull(sink.received); assertThrows(IOException.class, lease::value);
        }
    }
    @Test public void bindsMetadataAndRechecksBytes() throws Exception {
        var key = FileTransferEngine.Key.create("peer", SESSION); Sink sink = new Sink();
        try (var tokens = new AppRelayFileTokens<String>("peer", SESSION, ignored -> true, () -> 0)) {
            assertThrows(IOException.class, () -> tokens.track(FileTransferEngine.Key.create("foreign", SESSION), offer(), sink, "", value -> {}));
            var tracked = tokens.track(key, offer(), sink, "", value -> { throw new IOException("tampered"); });
            File file = File.createTempFile("relay-test", ".txt"); Files.write(file.toPath(), BYTES);
            try {
                assertThrows(IOException.class, () -> tracked.publishVerified(file, offer(), ignored -> true, FileTransferEngine.Key.create("peer", SESSION)));
                tracked.publishVerified(file, offer(), ignored -> true, key);
                assertThrows(IOException.class, () -> tokens.acquire(key.transfer(), offer().mime()));
            } finally { Files.delete(file.toPath()); }
        }
    }
    @Test public void revocationRetriesFailedCleanupAndInvalidatesLease() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); Sink sink = new Sink();
        var key = FileTransferEngine.Key.create("peer", SESSION);
        var tokens = new AppRelayFileTokens<String>("peer", SESSION, ignored -> current.get(), () -> 0);
        var tracked = tokens.track(key, offer(), sink, "", value -> {});
        File file = File.createTempFile("relay-test", ".txt"); Files.write(file.toPath(), BYTES);
        try {
            tracked.publishVerified(file, offer(), ignored -> true, key);
            var lease = tokens.acquire(key.transfer(), offer().mime());
            current.set(false); sink.cleanupFails = true;
            assertThrows(IOException.class, lease::value); assertThrows(IOException.class, tokens::close); assertFalse(tokens.empty());
            sink.cleanupFails = false; tokens.maintain(); assertTrue(tokens.empty()); assertEquals(1, sink.deletions);
        } finally { Files.delete(file.toPath()); tokens.close(); }
    }
    @Test public void unclaimedDocumentsExpire() throws Exception {
        AtomicLong clock = new AtomicLong(); Sink sink = new Sink();
        try (var tokens = new AppRelayFileTokens<String>("peer", SESSION, ignored -> true, clock::get)) {
            tokens.track(FileTransferEngine.Key.create("peer", SESSION), offer(), sink, "", value -> {});
            clock.set(60000); tokens.maintain(); assertTrue(tokens.empty()); assertEquals(1, sink.deletions);
        }
    }
    private static FileTransferEngine.Offer offer() throws Exception {
        return new FileTransferEngine.Offer("synthetic.txt", "text/plain", BYTES.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)));
    }
}
