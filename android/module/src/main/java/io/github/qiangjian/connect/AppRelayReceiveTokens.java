package io.github.qiangjian.connect;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Relay tokens over the file owner's real receive lease. This class never deletes the destination itself. */
final class AppRelayReceiveTokens<T> implements AutoCloseable {
    interface Factory<T> { FileTransferReceiveLease<T> create(FileTransferEngine.Key key, FileTransferEngine.Offer offer) throws IOException; }
    interface Read<T> extends AutoCloseable { T value() throws IOException; void close() throws IOException; }
    private final String peer, session;
    private final FileTransferEngine.SessionGate gate;
    private final Map<String, FileTransferReceiveLease<T>> entries = new LinkedHashMap<>();
    private boolean closed;
    AppRelayReceiveTokens(String peer, String session, FileTransferEngine.SessionGate gate) {
        new FileTransferEngine.Key(peer, session, "0".repeat(32));
        this.peer = peer; this.session = session; this.gate = gate;
    }
    synchronized CompletableFuture<Void> authorize(FileTransferEngine.Key key, FileTransferEngine.Offer offer, Factory<T> factory) throws IOException {
        require(key);
        if (entries.size() >= 4) throw new IOException("relay_document_capacity");
        if (entries.containsKey(key.transfer())) throw new IOException("duplicate_relay_document");
        FileTransferReceiveLease<T> lease = factory.create(key, offer);
        entries.put(key.transfer(), lease); // Retain cleanup ownership even if acceptance already failed.
        if (!key.equals(lease.key()) || !offer.equals(lease.offer())) {
            lease.close(); throw new IOException("consent_metadata_mismatch");
        }
        return lease.accepted();
    }
    synchronized Read<T> acquire(String token, String mime) throws IOException {
        FileTransferEngine.Key key = new FileTransferEngine.Key(peer, session, token);
        require(key);
        FileTransferReceiveLease<T> lease = entries.get(token);
        if (lease == null) throw new IOException("file_token_unavailable");
        if (!lease.offer().mime().equals(mime)) throw new IOException("document_mime_mismatch");
        T value = lease.claim(key, lease.offer());
        return new Read<>() {
            public T value() throws IOException { if (!lease.current()) throw new IOException("document_expired"); return value; }
            public void close() throws IOException { lease.close(); }
        };
    }
    private void require(FileTransferEngine.Key key) throws IOException {
        if (closed || !peer.equals(key.peer()) || !session.equals(key.session()) || !gate.current(key)) throw new IOException("stale_session");
    }
    synchronized void discard(FileTransferEngine.Key key) throws IOException {
        if (!peer.equals(key.peer()) || !session.equals(key.session())) throw new IOException("stale_session");
        FileTransferReceiveLease<T> lease = entries.get(key.transfer());
        if (lease != null) { lease.close(); if (lease.disposed()) entries.remove(key.transfer(), lease); }
    }
    synchronized boolean disposed(FileTransferEngine.Key key) {
        return peer.equals(key.peer()) && session.equals(key.session()) && !entries.containsKey(key.transfer());
    }
    synchronized void maintain() throws IOException {
        IOException failure = null;
        for (var iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            FileTransferReceiveLease<T> lease = iterator.next().getValue();
            try { if (closed) lease.close(); else lease.maintain(); }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            if (lease.disposed()) iterator.remove();
        }
        if (failure != null) throw failure;
    }
    synchronized boolean empty() { return entries.isEmpty(); }
    @Override public synchronized void close() throws IOException { closed = true; maintain(); }
}
