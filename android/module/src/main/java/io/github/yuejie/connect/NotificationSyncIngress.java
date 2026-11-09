package io.github.yuejie.connect;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Session-scoped, bounded reassembly. Partial receipts prove buffering only; no OS operation occurs yet. */
final class NotificationSyncIngress implements AutoCloseable {
    interface Executor {
        CompletableFuture<byte[]> execute(String peer, String session, String request, String operation, byte[] bytes, long deadline);
        void cancel(String session, String request);
    }
    interface Gate { boolean allows(String peer, String session, String operation); }
    private final Executor executor;
    private final Gate gate;
    private final LongSupplier clock;
    private final Map<String, Transfer> transfers = new HashMap<>();
    private String peer = "", session = "";
    private boolean closed;
    private static final class Transfer {
        final NotificationSyncFragments.Part first;
        final String operation;
        final long deadline;
        final String[] digests;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        byte[] buffer;
        int next;
        boolean dispatched, fenced;
        Transfer(NotificationSyncFragments.Part part, String operation, long deadline) {
            first = new NotificationSyncFragments.Part(part.transfer(), part.index(), part.count(), part.length(), part.sha256(), new byte[0], part.abort());
            this.operation = operation; this.deadline = deadline;
            buffer = new byte[part.length()]; digests = new String[part.count()];
        }
    }
    NotificationSyncIngress(Executor executor, Gate gate, LongSupplier clock) {
        this.executor = executor; this.gate = gate; this.clock = clock;
    }
    synchronized void onProjection(String newPeer, String newSession) {
        String p = newPeer == null ? "" : newPeer, s = newSession == null ? "" : newSession;
        if (!s.isEmpty() && (p.isEmpty() || !s.matches("[0-9a-f]{64}"))) throw failure("stale_session");
        if (peer.equals(p) && session.equals(s)) return;
        clear(); peer = p; session = s;
    }
    synchronized void authorizationChanged() { clear(); }
    synchronized CompletableFuture<JSONObject> accept(String fromPeer, String fromSession, String operation,
            JSONObject payload, long deadline) {
        try {
            require(fromPeer, fromSession, operation);
            long now = clock.getAsLong();
            if (deadline <= now) throw failure("deadline_expired");
            maintain();
            NotificationSyncFragments.Part part = NotificationSyncFragments.parse(payload);
            Transfer transfer = transfers.get(part.transfer());
            if (part.abort()) {
                if (transfer != null && (!transfer.first.sha256().equals(part.sha256()) || !transfer.operation.equals(operation))) throw failure("transfer_conflict");
                if (transfer == null) {
                    if (transfers.size() >= NotificationSyncFragments.MAX_TRANSFERS) throw failure("capacity");
                    transfer = new Transfer(part, operation, now); transfers.put(part.transfer(), transfer);
                }
                stop(transfer, "outcome_unverified");
                // This acknowledges the transfer fence, never a rollback of an already performed PendingIntent.
                return CompletableFuture.completedFuture(new JSONObject().put("transfer_id", part.transfer())
                        .put("sha256", part.sha256()).put("state", "aborted"));
            }
            if (transfer == null) {
                if (part.index() != 0) throw failure("fragment_order");
                if (transfers.size() >= NotificationSyncFragments.MAX_TRANSFERS || active() >= NotificationSyncFragments.MAX_ACTIVE) throw failure("capacity");
                transfer = new Transfer(part, operation, Math.min(deadline, now + NotificationSyncFragments.LIFETIME_MS));
                transfers.put(part.transfer(), transfer);
            }
            if (!same(transfer, part, operation)) throw failure("transfer_conflict");
            if (transfer.fenced || transfer.result.isCompletedExceptionally()) throw failure("transfer_closed");
            if (now >= transfer.deadline) { stop(transfer, "deadline_expired"); throw failure("deadline_expired"); }
            if (part.index() < transfer.next) {
                if (!NotificationSyncFragments.hash(part.bytes()).equals(transfer.digests[part.index()])) throw failure("transfer_conflict");
            } else {
                if (part.index() != transfer.next) throw failure("fragment_order");
                byte[] bytes = part.bytes();
                System.arraycopy(bytes, 0, transfer.buffer, part.index() * NotificationSyncFragments.PART_BYTES, bytes.length);
                transfer.digests[part.index()] = NotificationSyncFragments.hash(bytes); transfer.next++;
            }
            if (part.index() != part.count() - 1) return CompletableFuture.completedFuture(NotificationSyncFragments.acknowledgement(part, null));
            if (!transfer.dispatched) dispatch(transfer, fromPeer, fromSession);
            Transfer exact = transfer;
            return transfer.result.thenApply(receipt -> {
                synchronized (this) {
                    require(fromPeer, fromSession, operation);
                    if (exact.fenced || transfers.get(part.transfer()) != exact) throw failure("stale_session");
                    try { return NotificationSyncFragments.acknowledgement(part, receipt); }
                    catch (JSONException invalid) { throw failure("invalid_result"); }
                }
            });
        } catch (JSONException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private void dispatch(Transfer transfer, String fromPeer, String fromSession) {
        byte[] bytes = transfer.buffer;
        transfer.buffer = null;
        try {
            if (!transfer.first.sha256().equals(NotificationSyncFragments.hash(bytes))) throw failure("digest_mismatch");
            NotificationSyncCodec.Frame frame = NotificationSyncCodec.decode(bytes);
            if (!transfer.operation.equals(NotificationSyncCodec.operation(frame))) throw failure("invalid_operation");
            require(fromPeer, fromSession, transfer.operation);
            transfer.dispatched = true;
            executor.execute(fromPeer, fromSession, transfer.first.transfer(), transfer.operation, bytes, transfer.deadline)
                    .whenComplete((receipt, error) -> {
                        synchronized (this) {
                            try {
                                require(fromPeer, fromSession, transfer.operation);
                                if (transfer.fenced || transfers.get(transfer.first.transfer()) != transfer || clock.getAsLong() >= transfer.deadline) throw failure("outcome_unverified");
                                if (error != null) throw failure("execution_failed");
                                NotificationSyncCodec.checkReceipt(frame, receipt); transfer.result.complete(receipt.clone());
                            } catch (RuntimeException failure) { transfer.result.completeExceptionally(failure); }
                        }
                    });
        } catch (RuntimeException failure) { transfer.result.completeExceptionally(failure); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    synchronized void cancel(String expectedSession, String transfer) {
        if (!session.equals(expectedSession)) return;
        Transfer found = transfers.get(transfer); if (found != null) stop(found, "outcome_unverified");
    }
    synchronized void maintain() {
        long now = clock.getAsLong();
        for (Transfer transfer : transfers.values()) if (!transfer.result.isDone()
                && (now >= transfer.deadline || !gate.allows(peer, session, transfer.operation))) stop(transfer, "outcome_unverified");
    }
    private long active() { return transfers.values().stream().filter(t -> !t.result.isDone()).count(); }
    private static boolean same(Transfer transfer, NotificationSyncFragments.Part part, String operation) {
        var first = transfer.first;
        return transfer.operation.equals(operation) && first.length() == part.length() && first.count() == part.count()
                && first.sha256().equals(part.sha256());
    }
    private void require(String fromPeer, String fromSession, String operation) {
        if (closed || session.isEmpty() || !peer.equals(fromPeer) || !session.equals(fromSession)
                || !NotificationSyncFragments.operation(operation) || !gate.allows(fromPeer, fromSession, operation)) throw failure("permission_denied");
    }
    private void stop(Transfer transfer, String reason) {
        transfer.fenced = true;
        if (transfer.buffer != null) { Arrays.fill(transfer.buffer, (byte) 0); transfer.buffer = null; }
        transfer.result.completeExceptionally(failure(reason));
        if (transfer.dispatched) executor.cancel(session, transfer.first.transfer());
    }
    private void clear() { for (Transfer transfer : transfers.values()) stop(transfer, "stale_session"); transfers.clear(); }
    private static IllegalStateException failure(String code) { return new IllegalStateException("notification_" + code); }
    @Override public synchronized void close() { if (closed) return; closed = true; clear(); peer = ""; session = ""; }
}
