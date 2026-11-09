package io.github.yuejie.connect;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** File metadata receiver. It cannot allocate a URI unless an actual DOCUMENT click issued its token. */
final class AppRelayDocumentNegotiation {
    private final AppRelayBridge bridge;
    private final AppRelayDocumentDecisions decisions;
    AppRelayDocumentNegotiation(AppRelayBridge bridge, AppRelayDocumentDecisions decisions) { this.bridge = bridge; this.decisions = decisions; }
    void prepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint endpoint, FileTransferNegotiation negotiation) {
        if (prepared.metadata().purpose() != FileTransferNegotiation.Purpose.APP_RELAY) return;
        var key = prepared.key();
        decisions.approve(prepared.metadata().nativeOwnerToken(), key, prepared.metadata().offer(), (actualKey, offer, current) -> {
            CompletableFuture<Void> accepted = new CompletableFuture<>();
            final class Cleanup implements FileTransferNegotiation.Cleanup {
                volatile AppRelayDocuments owner;
                volatile boolean lookupDone, revoked;
                CompletableFuture<Boolean> disposal;
                public synchronized boolean release() {
                    revoked = true;
                    if (owner == null) return lookupDone;
                    if (disposal == null || disposal.isCompletedExceptionally() || (disposal.isDone() && !disposal.getNow(false))) disposal = owner.revokeTemporaryDocument(key);
                    return disposal.getNow(false);
                }
            }
            Cleanup cleanup = new Cleanup();
            boolean matched = negotiation.approve(prepared, k -> key.equals(k) && !cleanup.revoked && current.getAsBoolean(), accepted, cleanup);
            if (!matched) return CompletableFuture.failedFuture(new IOException("native_document_consent_required"));
            bridge.documentsForSession(key.peer(), key.session()).whenComplete((documents, error) -> {
                try {
                    if (error != null) { accepted.completeExceptionally(error); return; }
                    if (!(documents instanceof AppRelayDocuments owner)) {
                        accepted.completeExceptionally(new IOException("native_document_consent_required")); return;
                    }
                    cleanup.owner = owner;
                    owner.authorizeTemporaryDocument(endpoint, key, offer, () -> !cleanup.revoked && current.getAsBoolean())
                            .whenComplete((ignored, failed) -> {
                                if (failed != null) accepted.completeExceptionally(failed); else accepted.complete(null);
                            });
                } finally { cleanup.lookupDone = true; }
            });
            return accepted;
        });
    }
}
