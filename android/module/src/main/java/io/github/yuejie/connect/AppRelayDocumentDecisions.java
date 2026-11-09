package io.github.yuejie.connect;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Local original directory click -> one metadata-bound document. Remote prepare cannot issue it. */
final class AppRelayDocumentDecisions implements AutoCloseable {
    interface Authorizer {
        CompletableFuture<Void> authorize(FileTransferEngine.Key key, FileTransferEngine.Offer offer, BooleanSupplier nativeDecisionCurrent);
    }
    private record Slot(AppRelayPeer peer, AppRelayCatalog.Candidate candidate, String clickToken) {}
    private static final class Decision {
        final AppRelayPeer peer;
        final String pkg;
        final BooleanSupplier nativeCurrent;
        volatile long expires;
        volatile boolean revoked, retained;
        FileTransferEngine.Key key;
        FileTransferEngine.Offer offer;
        CompletableFuture<Void> accepted;
        Decision(AppRelayPeer peer, String pkg, BooleanSupplier nativeCurrent, long expires) {
            this.peer = peer; this.pkg = pkg; this.nativeCurrent = nativeCurrent; this.expires = expires;
        }
    }
    private final LongSupplier clock;
    private final Map<String, Decision> decisions = new HashMap<>();
    private Slot slot;
    private volatile boolean closed;
    AppRelayDocumentDecisions(LongSupplier clock) { this.clock = clock; }
    synchronized void consumed(AppRelayPeer peer, AppRelayCatalog.Update update, String clickToken) {
        if (closed) throw new IllegalStateException("stale_session");
        if (update.clearAll()) { clear(peer); return; }
        for (var candidate : update.candidates()) {
            if (candidate.eligible(true, true)) slot = new Slot(peer, candidate, clickToken);
            else if (slot != null && slot.peer.equals(peer) && slot.candidate.packageName().equals(candidate.packageName())) slot = null;
        }
    }
    synchronized void clear(AppRelayPeer peer) { if (slot != null && slot.peer.equals(peer)) slot = null; }
    synchronized AppRelayCatalog.Candidate candidate(AppRelayPeer peer, String pkg) {
        return !closed && slot != null && slot.peer.equals(peer) && slot.candidate.packageName().equals(pkg) ? slot.candidate : null;
    }
    synchronized boolean ownsClick(AppRelayPeer peer, String pkg, String token) {
        return !closed && slot != null && slot.peer.equals(peer) && slot.candidate.packageName().equals(pkg)
                && token != null && token.matches("[0-9a-f]{32}") && token.equals(slot.clickToken);
    }
    synchronized boolean document(AppRelayPeer peer, String pkg) {
        return slot != null && slot.peer.equals(peer) && slot.candidate.packageName().equals(pkg) && slot.candidate.kind() == AppRelayCatalog.Kind.DOCUMENT;
    }
    synchronized void revoked(FileTransferEngine.Key key) {
        for (Decision value : decisions.values()) if (key.equals(value.key)) value.revoked = true;
        maintain();
    }
    synchronized void revokeDocuments() { for (Decision value : decisions.values()) value.revoked = true; maintain(); }
    synchronized String clicked(AppRelayPeer peer, String pkg, BooleanSupplier nativeDecisionCurrent) {
        if (closed || slot == null || !slot.peer.equals(peer) || !slot.candidate.packageName().equals(pkg) || !nativeDecisionCurrent.getAsBoolean())
            throw new IllegalStateException("native_document_consent_required");
        if (slot.candidate.kind() != AppRelayCatalog.Kind.DOCUMENT) return "";
        maintain();
        if (decisions.size() >= 4) throw new IllegalStateException("relay_document_capacity");
        // A new original click supersedes an unfinished request, but never revokes a document
        // already opened by its real target activity merely because a directory icon changes.
        for (Decision previous : decisions.values()) if (previous.peer.equals(peer) && !previous.retained) previous.revoked = true;
        String token = UUID.randomUUID().toString().replace("-", "");
        decisions.put(token, new Decision(peer, pkg, nativeDecisionCurrent, clock.getAsLong() + 30 * 60 * 1000));
        return token;
    }
    synchronized CompletableFuture<Void> approve(String ownerToken, FileTransferEngine.Key key, FileTransferEngine.Offer offer, Authorizer authorizer) {
        AppRelaySync.validateOwnerToken(ownerToken);
        Decision decision = decisions.get(ownerToken);
        if (ownerToken.isEmpty() || decision == null || !live(decision) || !decision.peer.projectionId().equals(key.peer())
                || !decision.peer.sessionId().equals(key.session()))
            return CompletableFuture.failedFuture(new IOException("native_document_consent_required"));
        if (decision.key != null) return decision.key.equals(key) && decision.offer.equals(offer) ? decision.accepted.copy()
                : CompletableFuture.failedFuture(new IOException("consent_metadata_mismatch"));
        decision.key = key; decision.offer = offer; decision.accepted = new CompletableFuture<>();
        try {
            authorizer.authorize(key, offer, () -> live(decision)).whenComplete((ignored, error) -> {
                if (error != null) decision.accepted.completeExceptionally(error);
                else if (!live(decision)) decision.accepted.completeExceptionally(new IOException("native_document_consent_required"));
                else decision.accepted.complete(null);
            });
        } catch (RuntimeException error) { decision.accepted.completeExceptionally(error); }
        return decision.accepted.copy();
    }
    synchronized void finished(String token, AppRelayTasks.Result result) {
        Decision decision = decisions.get(token);
        if (decision == null) return;
        if (result != null && result.state() == AppRelayTasks.State.COMPLETED && "foreground_observed".equals(result.code()) && result.taskId() >= 0) {
            decision.retained = true; decision.expires = clock.getAsLong() + 5 * 60 * 1000;
        } else decision.revoked = true;
        maintain();
    }
    private boolean live(Decision decision) {
        // Native/current includes the session and user grant. Keep checking it after launch too;
        // retention permits the UI icon to disappear, not a revoked session to keep reading.
        try { return !closed && !decision.revoked && clock.getAsLong() < decision.expires && decision.nativeCurrent.getAsBoolean(); }
        catch (RuntimeException revoked) { return false; }
    }
    synchronized void maintain() {
        decisions.entrySet().removeIf(entry -> {
            Decision value = entry.getValue();
            if (live(value)) return false;
            value.revoked = true;
            if (value.accepted != null) value.accepted.completeExceptionally(new IOException("native_document_consent_required"));
            return true;
        });
    }
    @Override public synchronized void close() {
        closed = true; slot = null;
        for (Decision value : decisions.values()) {
            value.revoked = true;
            if (value.accepted != null) value.accepted.completeExceptionally(new IOException("native_document_consent_required"));
        }
        decisions.clear();
    }
}
