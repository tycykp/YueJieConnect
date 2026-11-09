package io.github.yuejie.connect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Session-scoped receive executor. All paths and destinations are chosen locally. */
public final class FileTransferEngine implements AutoCloseable {
    public static final int CHUNK_BYTES = 3072;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int MAX_TRANSFERS = 4;
    public static final long IDLE_MILLIS = 60_000;

    public record Key(String peer, String session, String transfer) {
        public Key {
            if (peer == null || peer.isBlank() || peer.length() > 256
                    || session == null || !session.matches("[0-9a-f]{64}")
                    || transfer == null || !transfer.matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("invalid_transfer_identity");
        }
        public static Key create(String peer, String session) {
            return new Key(peer, session, UUID.randomUUID().toString().replace("-", ""));
        }
    }

    public record Offer(String name, String mime, long size, String sha256) {
        public Offer {
            if (name == null || name.isBlank() || name.length() > 160 || name.equals(".") || name.equals("..")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x202e || c == 0x202d))
                throw new IllegalArgumentException("invalid_file_name");
            if (mime == null || mime.length() > 128 || !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
                throw new IllegalArgumentException("invalid_mime");
            if (size < 0 || size > MAX_BYTES) throw new IllegalArgumentException("file_size_limit");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_sha256");
        }
    }

    /** Receipt contains no recipient URI/path. The sink retains its own local result. */
    public record Receipt(Key key, long size, String sha256) {}

    /** Local ownership acknowledgement, separate from the eventual verified commit. */
    public record Authorization(Key key, Offer offer, CompletionStage<Receipt> committed) {}

    public interface SessionGate { boolean current(Key key); }

    /** Must copy/publish, read back and verify destination, and throw on any native failure.
     * rollback must affect only the new object authorized for this exact transfer.
     * The supplied temporary file is valid only for the duration of publishVerified. */
    public interface Sink {
        void publishVerified(File verifiedFile, Offer offer, SessionGate stillCurrent, Key key) throws IOException;
        void rollback() throws IOException;
    }

    private static final class Entry {
        final Offer offer;
        final Sink sink;
        File file;
        FileOutputStream output;
        MessageDigest digest;
        long offset, touched;
        Receipt receipt;
        final CompletableFuture<Receipt> committed = new CompletableFuture<>();
        boolean aborted, destinationOwned = true;
        Entry(Offer offer, Sink sink, long now) { this.offer = offer; this.sink = sink; this.touched = now; }
    }

    private final File root;
    private final SessionGate sessions;
    private final LongSupplier clock;
    private final Map<Key, Entry> entries = new HashMap<>();
    private boolean closed;

    public FileTransferEngine(File privateDirectory, SessionGate sessions, LongSupplier monotonicMillis) throws IOException {
        root = privateDirectory.getCanonicalFile();
        Files.createDirectories(root.toPath());
        if (!root.isDirectory()) throw new IOException("staging_unavailable");
        this.sessions = Objects.requireNonNull(sessions);
        clock = Objects.requireNonNull(monotonicMillis);
    }

    /** Call only after a local native receive decision, never from a remote request handler. */
    public synchronized void authorize(Key key, Offer offer, Sink localDestination) throws IOException {
        authorizeTracked(key, offer, localDestination);
    }

    /** The returned completion resolves only after publish, final route check and staging cleanup. */
    public synchronized Authorization authorizeTracked(Key key, Offer offer, Sink localDestination) throws IOException {
        maintain(); requireCurrent(key);
        if (entries.containsKey(key)) throw new IOException("transfer_already_authorized");
        if (entries.size() >= MAX_TRANSFERS) throw new IOException("file_busy");
        Entry entry = new Entry(Objects.requireNonNull(offer), Objects.requireNonNull(localDestination), clock.getAsLong());
        entries.put(key, entry);
        return new Authorization(key, offer, entry.committed.minimalCompletionStage());
    }

    public synchronized long begin(Key key, Offer offer) throws IOException {
        Entry e = get(key);
        if (!e.offer.equals(offer)) throw new IOException("consent_metadata_mismatch");
        if (e.receipt != null) return e.receipt.size();
        if (e.file == null) {
            try {
                e.file = File.createTempFile("yj-in-", ".part", root);
                e.output = new FileOutputStream(e.file);
                e.digest = sha256();
            } catch (IOException error) { abort(key, e); throw error; }
        }
        e.touched = clock.getAsLong();
        return e.offset;
    }

    /** Exactly one acknowledged block at a time; an offset mismatch never appends bytes. */
    public synchronized long append(Key key, long offset, byte[] bytes) throws IOException {
        Entry e = get(key);
        if (e.output == null || e.receipt != null) throw new IOException("transfer_not_writable");
        if (bytes == null || bytes.length == 0 || bytes.length > CHUNK_BYTES) throw new IOException("invalid_chunk");
        if (offset != e.offset) throw new IOException("offset_mismatch");
        if (bytes.length > e.offer.size() - e.offset) throw new IOException("file_length_exceeded");
        try {
            e.output.write(bytes);
            e.digest.update(bytes);
            e.offset += bytes.length;
            e.touched = clock.getAsLong();
            return e.offset;
        } catch (IOException error) { abort(key, e); throw error; }
    }

    public synchronized Receipt commit(Key key) throws IOException {
        Entry e = get(key);
        if (e.receipt != null) return e.receipt; // Bounded, same-session idempotence for lost final ACK.
        if (e.output == null || e.offset != e.offer.size()) throw new IOException("file_incomplete");
        try {
            e.output.getFD().sync();
            e.output.close(); e.output = null;
            if (!e.offer.sha256().equals(hex(e.digest.digest()))) throw new IOException("file_hash_mismatch");
            requireCurrent(key);
            e.sink.publishVerified(e.file, e.offer, sessions, key);
            requireCurrent(key);
            Files.delete(e.file.toPath()); e.file = null;
            e.receipt = new Receipt(key, e.offset, e.offer.sha256());
            e.touched = clock.getAsLong();
            e.committed.complete(e.receipt);
            return e.receipt;
        } catch (IOException | RuntimeException error) {
            try { abort(key, e); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** True means staging was cancelled. A committed destination is never removed by remote cancel. */
    public synchronized boolean cancel(Key key) throws IOException {
        Entry e = entries.get(key);
        if (e == null) return false;
        if (e.receipt != null) return false;
        abort(key, e);
        return true;
    }

    /** Local executor bookkeeping only, so auxiliary native-result records share this same bound. */
    synchronized boolean retains(Key key) { return entries.containsKey(key); }

    /** Invoke on every session/capability revocation and periodically even when no RPC arrives. */
    public synchronized void maintain() throws IOException {
        IOException failure = null;
        long now = clock.getAsLong();
        for (Map.Entry<Key, Entry> item : new HashMap<>(entries).entrySet()) {
            if (closed || item.getValue().aborted || !sessions.current(item.getKey()) || now - item.getValue().touched >= IDLE_MILLIS) {
                try { abort(item.getKey(), item.getValue()); }
                catch (IOException error) { failure = error; }
            }
        }
        if (failure != null) throw failure;
    }

    private Entry get(Key key) throws IOException {
        maintain(); requireCurrent(key);
        Entry e = entries.get(key);
        if (e == null) throw new IOException("receive_consent_required");
        if (e.aborted) throw new IOException("transfer_cleanup_pending");
        return e;
    }

    private void requireCurrent(Key key) throws IOException {
        if (closed || !sessions.current(key)) throw new IOException("stale_session");
    }

    private void abort(Key key, Entry e) throws IOException {
        e.aborted = true;
        e.committed.completeExceptionally(new IOException("file_cancelled"));
        IOException failure = null;
        try { if (e.output != null) { e.output.close(); e.output = null; } } catch (IOException error) { failure = error; }
        if (e.destinationOwned && e.receipt == null) {
            try { e.sink.rollback(); e.destinationOwned = false; } catch (IOException error) { failure = error; }
        }
        try { if (e.file != null) { Files.deleteIfExists(e.file.toPath()); e.file = null; } } catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
        entries.remove(key);
    }

    @Override public synchronized void close() throws IOException { closed = true; maintain(); }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
