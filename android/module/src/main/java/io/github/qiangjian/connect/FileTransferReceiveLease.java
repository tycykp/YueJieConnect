package io.github.qiangjian.connect;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Owns exactly one disposable local destination, from a native receive decision to consumption.
 * All creation, claim, maintain and close calls require a worker; none display UI or grant consent. */
public final class FileTransferReceiveLease<T> implements AutoCloseable {
    public static final long RECEIVE_MILLIS = 60_000, CONSUME_MILLIS = 300_000;
    public interface Authorizer {
        CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key key,
                FileTransferEngine.Offer offer, FileTransferEngine.Sink sink);
        CompletableFuture<Void> revoke(FileTransferEngine.Key key);
    }
    public interface Destination<T> extends FileTransferEngine.Sink {
        T value();
        void verify(FileTransferEngine.Offer offer, FileTransferEngine.SessionGate gate,
                    FileTransferEngine.Key key) throws IOException;
    }
    private final FileTransferEngine.Key key;
    private final FileTransferEngine.Offer offer;
    private final FileTransferEngine.SessionGate gate;
    private final Destination<T> destination;
    private final Authorizer authorizer;
    private final LongSupplier clock;
    private final CompletableFuture<Void> accepted = new CompletableFuture<>();
    private final CompletableFuture<FileTransferEngine.Receipt> committed = new CompletableFuture<>();
    private long deadline;
    private boolean released, deleted, claimed, published;

    /** destination must have been created for this local decision, never supplied by the peer. */
    public FileTransferReceiveLease(FileTransferEngine.Key key, FileTransferEngine.Offer offer,
            FileTransferEngine.SessionGate localNativeDecisionStillCurrent, Destination<T> newlyCreatedDestination,
            Authorizer authorizer, LongSupplier clock) {
        this.key = Objects.requireNonNull(key); this.offer = Objects.requireNonNull(offer);
        gate = Objects.requireNonNull(localNativeDecisionStillCurrent);
        destination = Objects.requireNonNull(newlyCreatedDestination);
        this.authorizer = Objects.requireNonNull(authorizer); this.clock = Objects.requireNonNull(clock);
        deadline = clock.getAsLong() + RECEIVE_MILLIS;
    }

    /** Exactly one invocation by the local receive owner. The first future is ownership transfer,
     * while committed() waits for the engine's final verified receipt, not merely sink publication. */
    public synchronized void authorize() {
        if (published) throw new IllegalStateException("receive_already_started");
        published = true;
        try {
            requireCurrent();
            authorizer.authorize(key, offer, new FileTransferEngine.Sink() {
                @Override public void publishVerified(File staged, FileTransferEngine.Offer actual,
                        FileTransferEngine.SessionGate engineGate, FileTransferEngine.Key actualKey) throws IOException {
                    synchronized (FileTransferReceiveLease.this) {
                        requireCurrent();
                        if (!key.equals(actualKey) || !offer.equals(actual)) throw new IOException("consent_metadata_mismatch");
                        destination.publishVerified(staged, actual, k -> current() && engineGate.current(k), actualKey);
                        requireCurrent();
                    }
                }
                @Override public void rollback() throws IOException {
                    synchronized (FileTransferReceiveLease.this) { release(); }
                }
            }).whenComplete((authorization, failure) -> {
                synchronized (FileTransferReceiveLease.this) {
                    if (failure != null) { failAuthorization(failure); return; }
                    if (authorization == null || !key.equals(authorization.key()) || !offer.equals(authorization.offer())) {
                        failAuthorization(new IOException("invalid_file_authorization")); return;
                    }
                    if (!current()) {
                        failAuthorization(new IOException("stale_session"));
                        authorizer.revoke(key); return;
                    }
                    // Engine owns the receiving inactivity timer and refreshes it on each chunk.
                    deadline = Long.MAX_VALUE;
                    authorization.committed().whenComplete((receipt, error) -> {
                        synchronized (FileTransferReceiveLease.this) {
                            if (error != null || !current() || receipt == null || !key.equals(receipt.key())
                                    || offer.size() != receipt.size() || !offer.sha256().equals(receipt.sha256())) {
                                released = true;
                                committed.completeExceptionally(new IOException("file_receive_failed"));
                            } else {
                                deadline = clock.getAsLong() + CONSUME_MILLIS;
                                committed.complete(receipt);
                            }
                        }
                    });
                    accepted.complete(null);
                }
            });
        } catch (RuntimeException | IOException failure) { failAuthorization(failure); }
    }

    /** Copies prevent a caller cancelling the owner futures or claiming success for the owner. */
    public CompletableFuture<Void> accepted() { return accepted.copy(); }
    public CompletableFuture<FileTransferEngine.Receipt> committed() { return committed.copy(); }
    public FileTransferEngine.Key key() { return key; }
    public FileTransferEngine.Offer offer() { return offer; }

    /** Local single-use handoff, with a second readback to catch mutation after commit. */
    public synchronized T claim(FileTransferEngine.Key actualKey, FileTransferEngine.Offer actualOffer) throws IOException {
        requireCurrent();
        if (!key.equals(actualKey) || !offer.equals(actualOffer)) throw new IOException("consent_metadata_mismatch");
        if (!committed.isDone() || committed.isCompletedExceptionally()) throw new IOException("file_not_committed");
        if (claimed) throw new IOException("file_already_claimed");
        destination.verify(offer, k -> current(), key);
        requireCurrent(); claimed = true;
        deadline = clock.getAsLong() + CONSUME_MILLIS;
        return destination.value();
    }

    /** The same local owner may serve repeated read-only provider opens after its single claim.
     * Each open rechecks the exact identity, route, expiry and stored bytes. This does not extend
     * the consumption lifetime, re-authorize a transfer or grant access to another application. */
    public synchronized T reopenClaimed(FileTransferEngine.Key actualKey,
            FileTransferEngine.Offer actualOffer) throws IOException {
        requireCurrent();
        if (!key.equals(actualKey) || !offer.equals(actualOffer)) throw new IOException("consent_metadata_mismatch");
        if (!claimed) throw new IOException("file_not_claimed");
        destination.verify(offer, k -> current(), key);
        requireCurrent();
        return destination.value();
    }

    public synchronized boolean current() {
        try { return !released && clock.getAsLong() < deadline && gate.current(key); }
        catch (RuntimeException unavailable) { return false; }
    }
    public synchronized boolean disposed() { return deleted; }
    public synchronized void maintain() throws IOException { if (!current()) close(); }
    private void requireCurrent() throws IOException { if (!current()) throw new IOException("stale_session"); }

    private void failAuthorization(Throwable failure) {
        accepted.completeExceptionally(failure); committed.completeExceptionally(failure);
        released = true;
        // Cleanup failures retain destination ownership and are retried by maintain()/close().
        try { release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }
    private void release() throws IOException {
        released = true;
        committed.completeExceptionally(new IOException("file_cancelled"));
        if (!accepted.isDone()) accepted.completeExceptionally(new IOException("file_cancelled"));
        if (!deleted) { destination.rollback(); deleted = true; }
    }
    @Override public synchronized void close() throws IOException {
        released = true;
        if (published) authorizer.revoke(key); // Enqueued; never wait while holding the sink lock.
        release();
    }
}
