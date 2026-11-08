package io.github.qiangjian.connect;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds native source preparation and waits for an actual receiver decision before streaming. */
public final class FileTransferSendCoordinator implements AutoCloseable {
    public interface Source {
        FileTransferSource snapshot(FileTransferEngine.SessionGate gate, FileTransferEngine.Key key) throws Exception;
    }
    public interface Consent {
        /** Completes only after the receiver's native decision and endpoint.authorize succeed.
         * This is a binding for the consent flow, not a new wire operation or pairing approval. */
        CompletableFuture<Void> request(FileTransferEngine.Key key, FileTransferEngine.Offer offer);
    }
    private static final class Work {
        final FileTransferEngine.Key key;
        final FileTransferEngine.SessionGate route;
        final CompletableFuture<FileTransferEngine.Receipt> result = new CompletableFuture<>();
        final AtomicBoolean finished = new AtomicBoolean();
        FileTransferSource source;
        CompletableFuture<?> pending;
        boolean preparing = true;
        Work(FileTransferEngine.Key key, FileTransferEngine.SessionGate route) { this.key = key; this.route = route; }
    }
    private final FileTransferClient client;
    private final Consent consent;
    private final Executor sourceWorker;
    private final ConcurrentHashMap<FileTransferEngine.Key, Work> active = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public FileTransferSendCoordinator(FileTransferClient client, Consent consent, Executor sourceWorker) {
        this.client = Objects.requireNonNull(client);
        this.consent = Objects.requireNonNull(consent);
        this.sourceWorker = Objects.requireNonNull(sourceWorker);
    }

    /** route must check the selected native owner/peer/channel as well as the authenticated session. */
    public synchronized CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key,
            FileTransferEngine.SessionGate route, Source source) {
        if (closed || !route.current(key)) return failed("stale_session");
        if (active.containsKey(key)) return failed("duplicate_transfer");
        if (active.size() >= FileTransferEngine.MAX_TRANSFERS) return failed("file_busy");
        Work work = new Work(key, route);
        active.put(key, work);
        work.result.whenComplete((receipt, error) -> finish(work));
        try { sourceWorker.execute(() -> prepare(work, source)); }
        catch (RuntimeException rejected) {
            synchronized (work) { work.preparing = false; }
            work.result.completeExceptionally(new IOException("file_busy"));
        }
        return work.result;
    }

    private void prepare(Work work, Source source) {
        FileTransferSource snapshot = null;
        try {
            requireCurrent(work);
            snapshot = source.snapshot(key -> current(work), work.key);
            FileTransferEngine.Offer offer = snapshot.offer();
            synchronized (work) {
                requireCurrent(work);
                work.source = snapshot;
                snapshot = null;
            }
            CompletableFuture<Void> decision = Objects.requireNonNull(consent.request(work.key, offer));
            // A silent native consent flow cannot retain source URIs/snapshots indefinitely.
            decision.orTimeout(30, TimeUnit.SECONDS);
            synchronized (work) {
                if (!current(work)) { decision.cancel(true); throw new IOException("file_cancelled"); }
                work.pending = decision;
            }
            decision.whenComplete((ignored, failure) -> {
                if (failure != null) { work.result.completeExceptionally(new IOException("receive_consent_failed")); return; }
                transmit(work);
            });
        } catch (Exception failure) { work.result.completeExceptionally(failure); }
        finally {
            if (snapshot != null) try { snapshot.close(); } catch (IOException ignored) { }
            synchronized (work) {
                work.preparing = false;
                if (work.result.isDone()) active.remove(work.key, work);
            }
        }
    }

    private void transmit(Work work) {
        try {
            CompletableFuture<FileTransferEngine.Receipt> sent;
            synchronized (work) {
                requireCurrent(work);
                FileTransferSource owned = work.source;
                if (owned == null) throw new IOException("source_unavailable");
                work.source = null;
                // FileTransferClient takes ownership even on executor rejection/capacity failure.
                sent = client.send(work.key, owned, ignored -> current(work));
                work.pending = sent;
            }
            sent.whenComplete((receipt, error) -> {
                if (error != null) work.result.completeExceptionally(error);
                else if (!current(work)) work.result.completeExceptionally(new IOException("stale_session"));
                else work.result.complete(receipt);
            });
        } catch (Exception failure) { work.result.completeExceptionally(failure); }
    }

    private boolean current(Work work) { return !closed && !work.result.isDone() && work.route.current(work.key); }
    private void requireCurrent(Work work) throws IOException {
        if (!current(work)) throw new IOException("stale_session");
    }
    private void finish(Work work) {
        if (!work.finished.compareAndSet(false, true)) return;
        synchronized (work) {
            if (work.pending != null && !work.pending.isDone()) work.pending.cancel(true);
            if (work.source != null) {
                try { work.source.close(); } catch (IOException ignored) { }
                work.source = null;
            }
            // A cancelled but still blocked source must continue to consume a preparation slot.
            if (!work.preparing) active.remove(work.key, work);
        }
    }
    public void cancel(FileTransferEngine.Key key) {
        Work work = active.get(key);
        if (work != null) work.result.cancel(true);
    }
    public void sessionChanged() {
        for (Work work : active.values()) if (!work.route.current(work.key)) work.result.cancel(true);
    }
    @Override public synchronized void close() {
        closed = true;
        for (Work work : active.values()) work.result.cancel(true);
    }
    private static CompletableFuture<FileTransferEngine.Receipt> failed(String code) {
        return CompletableFuture.failedFuture(new IOException(code));
    }
}
