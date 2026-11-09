package io.github.qiangjian.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Session-scoped video control. Transport callbacks must enqueue without synchronous RPC/reentry.
 * A source becomes STREAMING only after a matched remote render receipt; a sink only after its
 * decoder's real onFrameRendered callback. No wall-clock value from the peer is trusted. */
public final class ScreenCastSession implements AutoCloseable {
    public enum Role { SOURCE, SINK }
    public enum State { PREPARING, WAITING_FIRST_FRAME, STREAMING, CLOSED }
    public record Scope(String sessionId, long streamId) {
        public Scope {
            if (sessionId == null || !sessionId.matches("[0-9a-f]{64}") || streamId <= 0)
                throw new IllegalArgumentException("invalid_screen_scope");
        }
    }
    public record Receipt(Scope scope, long sequence, long presentationTimeUs) {
        public Receipt {
            Objects.requireNonNull(scope);
            if (sequence <= 0 || presentationTimeUs < 0) throw new IllegalArgumentException("invalid_render_receipt");
        }
    }
    public interface Transport {
        boolean offer(Scope scope, ScreenCastPacket packet);
        void rendered(Receipt receipt);
        void requestKeyFrame(Scope scope);
    }
    public interface Listener {
        void onStreaming(Receipt firstRendered);
        /** Must release the native source/sink and revoke associated remote input. Called once. */
        void onClosed(String code);
    }
    public interface Receiver { ScreenCastFrameQueue.Offer offer(ScreenCastPacket packet); }
    private static final int MAX_RECEIPTS = 256;
    private final Scope scope;
    private final ScreenCastFormat format;
    private final Role role;
    private final BooleanSupplier allowed;
    private final LongSupplier clock;
    private final Transport transport;
    private final Listener listener;
    private final Map<Long, Long> frames = new LinkedHashMap<>();
    private State state = State.PREPARING;
    private boolean localReady, remoteReady;
    private long firstFrameDeadline, lastSequence, lastRenderedSequence, lastKeyRequest = -1;

    public ScreenCastSession(Scope scope, ScreenCastFormat negotiatedFormat, Role role,
            BooleanSupplier currentSessionAndScreenAllowed, LongSupplier elapsedRealtimeMs,
            Transport transport, Listener listener) {
        this.scope = Objects.requireNonNull(scope); format = Objects.requireNonNull(negotiatedFormat);
        this.role = Objects.requireNonNull(role); allowed = Objects.requireNonNull(currentSessionAndScreenAllowed);
        clock = Objects.requireNonNull(elapsedRealtimeMs); this.transport = Objects.requireNonNull(transport);
        this.listener = Objects.requireNonNull(listener);
        firstFrameDeadline = clock.getAsLong() + 10_000; // Includes permission/prepare, not unlimited waiting.
    }

    /** Call after actual codec/Surface preparation, never on request submission alone. */
    public synchronized boolean localPrepared() {
        if (!current()) return false;
        localReady = true; ready(); return true;
    }
    /** Accept only the negotiated format on the same authenticated session and stream. */
    public synchronized boolean remotePrepared(Scope receivedScope, ScreenCastFormat acceptedFormat) {
        if (!scope.equals(receivedScope) || !current()) return false;
        if (!format.equals(acceptedFormat)) { fail("screen_format_mismatch"); return false; }
        remoteReady = true; ready(); return true;
    }
    private void ready() {
        if (state == State.PREPARING && localReady && remoteReady) {
            state = State.WAITING_FIRST_FRAME; firstFrameDeadline = clock.getAsLong() + 5_000;
        }
    }

    /** Wire this directly to ScreenCastVideoEncoder.Listener.offer. False triggers its backpressure recovery. */
    public synchronized boolean offer(ScreenCastPacket packet) {
        Objects.requireNonNull(packet);
        if (role != Role.SOURCE || !usable(packet)) return false;
        lastSequence = packet.sequence;
        if (!transport.offer(scope, packet)) return false;
        remember(packet);
        if (packet.isEnd()) fail("screen_ended");
        return true;
    }

    /** Invoke only with frames from the authenticated binary channel. Decoder owns its bounded queue. */
    public synchronized ScreenCastFrameQueue.Offer receive(Scope receivedScope, ScreenCastPacket packet, Receiver receiver) {
        Objects.requireNonNull(packet); Objects.requireNonNull(receiver);
        if (role != Role.SINK || !scope.equals(receivedScope)) return ScreenCastFrameQueue.Offer.STALE;
        if (!current()) return ScreenCastFrameQueue.Offer.CLOSED;
        if (!usable(packet)) return ScreenCastFrameQueue.Offer.STALE;
        lastSequence = packet.sequence;
        ScreenCastFrameQueue.Offer result = receiver.offer(packet);
        if (result == ScreenCastFrameQueue.Offer.ACCEPTED) remember(packet);
        if (result == ScreenCastFrameQueue.Offer.NEED_KEY_FRAME) keyFrameNeeded();
        return result;
    }
    private boolean usable(ScreenCastPacket packet) {
        return current() && state != State.PREPARING && packet.streamId == scope.streamId && packet.sequence > lastSequence;
    }
    private void remember(ScreenCastPacket packet) {
        // A repeated codec configuration does not invalidate pictures already in flight.
        if (packet.isConfig()) return;
        if (packet.isEnd()) return;
        // A PTS must identify exactly one picture for MediaCodec's rendered callback.
        if (frames.containsValue(packet.presentationTimeUs)) { fail("ambiguous_frame_timestamp"); return; }
        frames.put(packet.sequence, packet.presentationTimeUs);
        if (frames.size() > MAX_RECEIPTS) frames.remove(frames.keySet().iterator().next());
    }

    /** Call ONLY from the current decoder's actual onFrameRendered; prepared/output-buffer callbacks do not count. */
    public synchronized boolean frameRendered(long presentationTimeUs) {
        if (role != Role.SINK || !current() || state == State.PREPARING) return false;
        for (Map.Entry<Long, Long> frame : frames.entrySet()) if (frame.getValue() == presentationTimeUs) {
            Receipt receipt = new Receipt(scope, frame.getKey(), presentationTimeUs);
            boolean first=state!=State.STREAMING;
            if (!accept(receipt)) return false;
            // The first native render proves readiness. Ongoing liveness belongs to the binary
            // media channel; repeated RPC receipts exhaust its session-long replay ledger.
            if(first)transport.rendered(receipt);return true;
        }
        return false;
    }
    public synchronized boolean renderedReceipt(Receipt receipt) {
        Objects.requireNonNull(receipt);
        return role == Role.SOURCE && current() && state != State.PREPARING && accept(receipt);
    }
    private boolean accept(Receipt receipt) {
        if (!scope.equals(receipt.scope) || receipt.sequence <= lastRenderedSequence
                || !Objects.equals(frames.get(receipt.sequence), receipt.presentationTimeUs)) return false;
        lastRenderedSequence = receipt.sequence;
        frames.entrySet().removeIf(entry -> entry.getKey() <= receipt.sequence);
        if (state != State.STREAMING) { state = State.STREAMING; listener.onStreaming(receipt); }
        return true;
    }

    public synchronized void keyFrameNeeded() {
        if (role != Role.SINK || !current() || state == State.PREPARING) return;
        long now = clock.getAsLong();
        if (lastKeyRequest < 0 || now - lastKeyRequest >= 500) {
            lastKeyRequest = now; transport.requestKeyFrame(scope);
        }
    }
    /** Authenticated control message -> current encoder. The caller supplies its requestKeyFrame method. */
    public synchronized boolean keyFrameRequest(Scope receivedScope, Runnable requestEncoderKeyFrame) {
        Objects.requireNonNull(requestEncoderKeyFrame);
        if (role != Role.SOURCE || !scope.equals(receivedScope) || !current() || state == State.PREPARING) return false;
        long now = clock.getAsLong();
        if (lastKeyRequest >= 0 && now - lastKeyRequest < 500) return false;
        lastKeyRequest = now; requestEncoderKeyFrame.run(); return true;
    }
    /** Call on the existing session timer at least once per second, including when no media arrives. */
    public synchronized void tick() { current(); }
    private boolean current() {
        if (state == State.CLOSED) return false;
        boolean granted;
        try { granted = allowed.getAsBoolean(); } catch (RuntimeException error) { granted = false; }
        if (!granted) { fail("screen_permission_revoked"); return false; }
        if (state != State.STREAMING && clock.getAsLong() >= firstFrameDeadline) {
            fail(state == State.PREPARING ? "screen_prepare_timeout" : "first_frame_timeout"); return false;
        }
        return true;
    }
    public synchronized State state() { return state; }
    public synchronized void fail(String code) {
        Objects.requireNonNull(code);
        if (state == State.CLOSED) return;
        state = State.CLOSED; frames.clear(); listener.onClosed(code);
    }
    @Override public void close() { fail("screen_closed"); }
}
