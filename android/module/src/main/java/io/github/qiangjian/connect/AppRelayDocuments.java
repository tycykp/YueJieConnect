package io.github.qiangjian.connect;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Receives into a new, locally approved temporary content URI and provides read-only relay leases. */
final class AppRelayDocuments implements AppRelayEndpoint.Documents, AutoCloseable {
    private final Context context;
    private final FileTransferEngine.SessionGate gate;
    private final AppRelayFileTokens<Uri> tokens;
    private final AppRelayReceiveTokens<Uri> received;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qj-relay-files"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    AppRelayDocuments(Context context, String peer, String session, FileTransferEngine.SessionGate gate) {
        this.context = context; this.gate = gate;
        tokens = new AppRelayFileTokens<>(peer, session, gate, SystemClock::elapsedRealtime);
        received = new AppRelayReceiveTokens<>(peer, session, gate);
        timer.scheduleWithFixedDelay(this::maintain, 5, 5, TimeUnit.SECONDS);
    }
    // LOCAL native metadata-bound decision only. There is no remote operation that can call this.
    // FileTransferReceiveDocuments creates a new OEM-owned URI; the peer never chooses the URI.
    CompletableFuture<Void> authorizeTemporaryDocument(FileTransferEndpoint endpoint, FileTransferEngine.Key key,
            FileTransferEngine.Offer offer, java.util.function.BooleanSupplier nativeDecisionStillCurrent) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) try { timer.execute(() -> {
                try { received.discard(key); } catch (IOException cleanup) { logCleanup(); }
            }); } catch (java.util.concurrent.RejectedExecutionException closed) { /* close already owns all lease cleanup. */ }
        });
        try { timer.execute(() -> {
            try {
                if (result.isCancelled()) return;
                if (closed || !nativeDecisionStillCurrent.getAsBoolean()) throw new IOException("native_document_consent_required");
                received.authorize(key, offer, (k, o) -> FileTransferReceiveDocuments.createTemporary(context, endpoint, k, o,
                        candidate -> !closed && nativeDecisionStillCurrent.getAsBoolean() && gate.current(candidate)))
                        .whenComplete((ignored, error) -> { if (error == null) result.complete(null); else result.completeExceptionally(error); });
            } catch (Exception failure) { result.completeExceptionally(failure); }
        }); } catch (java.util.concurrent.RejectedExecutionException closed) { result.completeExceptionally(closed); }
        return result;
    }
    // This is a LOCAL approval entry point: newDestination must be a disposable new document.
    // It must not be an existing user document or a URI supplied by the remote payload.
    CompletableFuture<Void> authorizeTemporaryDocument(FileTransferEndpoint endpoint, FileTransferEngine.Key key,
                                                        FileTransferEngine.Offer offer, Uri newDestination) {
        try {
            FileTransferEngine.Sink destination = FileTransferUris.newDocumentSink(context, newDestination);
            FileTransferEngine.Sink tracked = tokens.track(key, offer, destination, newDestination,
                    uri -> FileTransferUris.verify(context, uri, offer, gate, key));
            CompletableFuture<Void> result = endpoint.authorize(key, offer, tracked);
            result.whenComplete((ignored, error) -> {
                if (error != null) try { tokens.discard(key); } catch (IOException cleanup) { logCleanup(); }
            });
            return result;
        } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override public AppRelayEndpoint.Document acquire(String token, String mime) throws IOException {
        // New native-decision path has a single file-owner lease. Legacy caller-created documents
        // use the old independent token store and never share ownership of these pending rows.
        try {
            AppRelayReceiveTokens.Read<Uri> read = received.acquire(token, mime);
            return new AppRelayEndpoint.Document() {
                public Uri uri() { try { return read.value(); } catch (IOException expired) { throw new IllegalStateException("document_expired"); } }
                public void close() { try { read.close(); } catch (IOException cleanup) { logCleanup(); } }
            };
        } catch (IOException missing) { if (!"file_token_unavailable".equals(missing.getMessage())) throw missing; }
        AppRelayFileTokens<Uri>.Lease lease = tokens.acquire(token, mime);
        return new AppRelayEndpoint.Document() {
            @Override public Uri uri() {
                try { return lease.value(); } catch (IOException expired) { throw new IllegalStateException("document_expired"); }
            }
            @Override public void close() { try { lease.close(); } catch (IOException cleanup) { logCleanup(); } }
        };
    }
    private void maintain() {
        try { tokens.maintain(); } catch (IOException cleanup) { logCleanup(); }
        try { received.maintain(); } catch (IOException cleanup) { logCleanup(); }
        if (closed && tokens.empty() && received.empty()) timer.shutdown();
    }
    CompletableFuture<Boolean> revokeTemporaryDocument(FileTransferEngine.Key key) {
        if (received.disposed(key)) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try { timer.execute(() -> {
            try { received.discard(key); result.complete(received.disposed(key)); }
            catch (IOException cleanup) { logCleanup(); result.complete(false); }
        }); } catch (java.util.concurrent.RejectedExecutionException retired) { result.complete(received.disposed(key)); }
        return result;
    }
    private static void logCleanup() { android.util.Log.w("QiangJianRelay", "temporary_document_cleanup_failed"); }
    @Override public void close() {
        closed = true;
        try { tokens.close(); } catch (IOException cleanup) { logCleanup(); }
        try { received.close(); } catch (IOException cleanup) { logCleanup(); }
        if (tokens.empty() && received.empty()) timer.shutdown(); // Failed deletion remains queued for a real retry.
    }
}
