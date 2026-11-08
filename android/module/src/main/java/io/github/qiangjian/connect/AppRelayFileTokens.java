package io.github.qiangjian.connect;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Local consent registry for disposable relay documents; a transfer ID alone grants nothing. */
final class AppRelayFileTokens<T> implements AutoCloseable {
    interface Verify<T> { void verify(T value) throws IOException; }
    private final class Entry {
        final FileTransferEngine.Key key;
        final FileTransferEngine.Offer offer;
        final FileTransferEngine.Sink sink;
        final T value;
        final Verify<T> verify;
        long deadline;
        boolean verified, claimed, released;
        Entry(FileTransferEngine.Key key, FileTransferEngine.Offer offer, FileTransferEngine.Sink sink, T value, Verify<T> verify) {
            this.key = key; this.offer = offer; this.sink = sink; this.value = value; this.verify = verify;
            deadline = clock.getAsLong() + 60000;
        }
    }
    final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean ended;
        Lease(Entry entry) { this.entry = entry; }
        T value() throws IOException {
            synchronized (AppRelayFileTokens.this) {
                if (ended || entry.released || clock.getAsLong() >= entry.deadline) throw new IOException("document_expired");
                requireCurrent(entry.key); return entry.value;
            }
        }
        @Override public void close() throws IOException {
            synchronized (AppRelayFileTokens.this) { ended = true; entry.released = true; remove(entry); }
        }
    }
    private final String peer, session;
    private final FileTransferEngine.SessionGate gate;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean closed;
    AppRelayFileTokens(String peer, String session, FileTransferEngine.SessionGate gate, LongSupplier clock) {
        this.peer = peer; this.session = session; this.gate = gate; this.clock = clock;
    }
    /** Called by local native approval, never by a remote payload handler. */
    synchronized FileTransferEngine.Sink track(FileTransferEngine.Key key, FileTransferEngine.Offer offer,
                                               FileTransferEngine.Sink destination, T value, Verify<T> verify) throws IOException {
        maintain(); requireCurrent(key);
        if (entries.containsKey(key.transfer())) throw new IOException("document_already_authorized");
        if (entries.size() >= 4) throw new IOException("document_capacity");
        Entry entry = new Entry(key, offer, destination, value, verify); entries.put(key.transfer(), entry);
        return new FileTransferEngine.Sink() {
            @Override public void publishVerified(File staged, FileTransferEngine.Offer actual,
                                                  FileTransferEngine.SessionGate stillCurrent, FileTransferEngine.Key actualKey) throws IOException {
                synchronized (AppRelayFileTokens.this) {
                    requireCurrent(key);
                    if (entry.released || !key.equals(actualKey) || !offer.equals(actual)) throw new IOException("document_metadata_mismatch");
                    destination.publishVerified(staged, actual, stillCurrent, actualKey);
                    requireCurrent(key);
                    entry.verified = true; entry.deadline = clock.getAsLong() + 300000;
                }
            }
            @Override public void rollback() throws IOException {
                synchronized (AppRelayFileTokens.this) { entry.released = true; remove(entry); }
            }
        };
    }
    synchronized Lease acquire(String token, String mime) throws IOException {
        maintain();
        Entry entry = entries.get(token);
        if (entry == null || !entry.verified || entry.released) throw new IOException("document_not_committed");
        requireCurrent(entry.key);
        if (!entry.offer.mime().equals(mime)) throw new IOException("document_mime_mismatch");
        if (entry.claimed) throw new IOException("document_already_claimed");
        entry.verify.verify(entry.value); // Detect destination mutation since the file commit.
        requireCurrent(entry.key);
        entry.claimed = true; entry.deadline = clock.getAsLong() + 300000;
        return new Lease(entry);
    }
    synchronized void discard(FileTransferEngine.Key key) throws IOException {
        Entry entry = entries.get(key.transfer());
        if (entry != null && entry.key.equals(key)) { entry.released = true; remove(entry); }
    }
    synchronized void maintain() throws IOException {
        IOException failure = null;
        for (Entry entry : java.util.List.copyOf(entries.values())) {
            if (closed || entry.released || !gate.current(entry.key) || clock.getAsLong() >= entry.deadline) {
                entry.released = true;
                try { remove(entry); } catch (IOException error) { failure = error; }
            }
        }
        if (failure != null) throw failure;
    }
    private void remove(Entry entry) throws IOException {
        if (entries.get(entry.key.transfer()) != entry) return;
        entry.sink.rollback(); // Only disposable, newly authorized destinations belong here.
        entries.remove(entry.key.transfer());
    }
    private void requireCurrent(FileTransferEngine.Key key) throws IOException {
        if (closed || !peer.equals(key.peer()) || !session.equals(key.session()) || !gate.current(key)) throw new IOException("stale_session");
    }
    synchronized boolean empty() { return entries.isEmpty(); }
    @Override public synchronized void close() throws IOException { closed = true; maintain(); }
}
