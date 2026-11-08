package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Serialized by the endpoint's main looper. Only verified playback is reported as ringing. */
final class DeviceFinderController {
    static final long MAX_DURATION_MS = 30_000;
    interface Backend {
        void start(String peerName, String session) throws Exception;
        boolean isRinging() throws Exception;
        void stop() throws Exception;
        default boolean isReleased() throws Exception { return !isRinging(); }
        default boolean isBusy() throws Exception { return isRinging(); }
    }
    record Result(boolean ringing, long remainingMs, String reason, boolean replayed) {}
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private final Backend backend;
    private final LongSupplier clock;
    private record Receipt(String fingerprint, String error) {}
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private String session = "", reason = "idle";
    private String activeRequest;
    private long deadline;
    private boolean owned, closed, stopping;
    private String stopReason;

    DeviceFinderController(Backend backend, LongSupplier clock) {
        this.backend = Objects.requireNonNull(backend);
        this.clock = Objects.requireNonNull(clock);
    }

    void bind(String next) throws Exception {
        next = next == null ? "" : next;
        if (session.equals(next)) { if (stopping) stopOwned(stopReason); return; }
        // Invalidate old authorization before cleanup; cleanup_pending must not require a second bind.
        session = next;
        receipts.clear();
        stopOwned("session_ended");
    }

    Result execute(String sessionId, String requestId, String action, long durationMs, String peerName) throws Exception {
        if (closed || session.isEmpty() || !session.equals(sessionId)) throw new Failure("stale_session");
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128) throw new Failure("invalid_request_id");
        if (!"start".equals(action) && !"stop".equals(action) && !"state".equals(action)) throw new Failure("invalid_action");
        if ("start".equals(action) && (durationMs < 1 || durationMs > MAX_DURATION_MS)) throw new Failure("invalid_duration");
        tick();
        String fingerprint = action + ":" + durationMs;
        Receipt previous = receipts.get(requestId);
        if (previous != null) {
            if (previous.fingerprint != null && !previous.fingerprint.equals(fingerprint)) throw new Failure("request_conflict");
            if (previous.error != null) throw new Failure(previous.error);
            return snapshot(true);
        }
        if ("state".equals(action)) return snapshot(false);
        // Do not evict old receipts in a live session: an evicted start could replay after stop.
        // Reserve the final receipt for stop; a full ledger must never trap a running alarm.
        if (receipts.size() >= ("start".equals(action) ? 255 : 256)) throw new Failure("request_capacity");
        receipts.put(requestId, new Receipt(fingerprint, null));
        try {
        if ("start".equals(action) && !owned) {
            if (backend.isBusy()) throw new Failure("finder_busy");
            owned = true; // Failed/partially completed starts must also be cleaned up.
            activeRequest = requestId;
            deadline = clock.getAsLong() + durationMs;
            try {
                backend.start(peerName, session);
                if (!backend.isRinging()) throw new Failure("playback_unverified");
                reason = "started";
            } catch (Exception error) {
                try { stopOwned("start_failed"); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        } else if ("stop".equals(action)) stopOwned("stopped");
        return snapshot(false);
        } catch (Exception failure) {
            if (failure instanceof Failure f && "cleanup_pending".equals(f.code)) {
                receipts.remove(requestId);
                throw failure;
            }
            receipts.put(requestId, new Receipt(fingerprint, failure instanceof Failure f ? f.code : "finder_execution_failed"));
            throw failure;
        }
    }

    void tick() throws Exception {
        if (!owned) return;
        if (stopping) stopOwned(stopReason);
        else if (clock.getAsLong() >= deadline) stopOwned("timeout");
        else if (!backend.isRinging()) stopOwned("native_stopped");
    }

    void cancel() throws Exception { stopOwned("cancelled"); }
    void cancel(String sessionId, String requestId) throws Exception {
        if (!session.equals(sessionId) || requestId == null || requestId.isEmpty() || requestId.length() > 128) return;
        // Unknown cancellations also consume receipts. Preserve the same final slot reserved for stop;
        // once full, an unrecorded start cannot execute because the start capacity check rejects it.
        if (receipts.size() < 255 || receipts.containsKey(requestId)) receipts.put(requestId, new Receipt(null, "cancelled"));
        // Persist cancellation before cleanup may throw. A new session can reuse the old owner's
        // wire request number while its resources are still being released.
        if (Objects.equals(activeRequest, requestId)) cancel();
    }

    static String terminalFailureCode(Exception failure, boolean closed) {
        if (!(failure instanceof Failure finder)) return "finder_execution_failed";
        // This code is an internal retry signal, never a terminal wire failure.
        if ("cleanup_pending".equals(finder.code)) return closed ? "stale_session" : "request_timeout";
        return finder.code;
    }

    void close() throws Exception {
        closed = true;
        session = "";
        receipts.clear();
        stopOwned("closed");
    }

    private void stopOwned(String why) throws Exception {
        if (owned) {
            stopping = true;
            stopReason = why;
            backend.stop();
            if (backend.isRinging() || !backend.isReleased()) throw new Failure("cleanup_pending");
            owned = false;
            stopping = false;
        }
        deadline = 0;
        activeRequest = null;
        reason = why;
    }

    private Result snapshot(boolean replayed) throws Exception {
        if (!owned && backend.isBusy()) throw new Failure("finder_busy");
        boolean playing = owned && backend.isRinging();
        return new Result(playing, playing ? Math.max(0, deadline - clock.getAsLong()) : 0, reason, replayed);
    }
}
