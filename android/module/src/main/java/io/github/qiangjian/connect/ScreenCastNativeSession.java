package io.github.qiangjian.connect;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One approved native owner and one negotiated stream. This is process-local assembly, not a
 * public wire operation or a substitute for the control owner's peer/proof/consent validation.
 * Control and endpoint methods must enqueue without blocking or synchronously reentering peers. */
public final class ScreenCastNativeSession implements AutoCloseable {
    public interface Control {
        void prepared(ScreenCastSession.Scope scope, ScreenCastFormat format);
        void rendered(ScreenCastSession.Receipt receipt);
        void requestKeyFrame(ScreenCastSession.Scope scope);
        void stopped(ScreenCastSession.Scope scope, String code);
    }
    public interface Endpoint {
        /** Nonblocking, bounded media enqueue. True is admission, not a render acknowledgement. */
        boolean offer(ScreenCastPacket packet);
        ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet);
        void requestKeyFrame();
        /** Revoke the input lane before releasing this owner's capture/window and media pipe.
         * Each method is attempted even if another cleanup method fails. */
        void revokeInput();
        void stopNative();
        void closeMedia();
    }
    public interface Listener {
        void onStreaming(ScreenCastSession.Receipt receipt);
        void onClosed(String code);
    }
    private final Object nativeOwner;
    private final ScreenCastSession.Scope scope;
    private final ScreenCastFormat format;
    private final ScreenCastSession.Role role;
    private final BooleanSupplier currentAndNativeConsented;
    private final Endpoint endpoint;
    private final Control control;
    private final Listener listener;
    private final ScreenCastSession session;
    private boolean closed, preparedSent, remoteStop;
    private int nativeDisplayId = -1;

    /** Create only after this request's real OEM consent. nativeOwner is the actual DeviceInfo
     * request or Mirror window, not a UID/name from a remote payload. The gate includes the same native
     * owner/peer/screen generation and this stream's consent. Never use pairing alone here. */
    public ScreenCastNativeSession(Object nativeOwner, ScreenCastSession.Scope scope,
            ScreenCastFormat format, ScreenCastSession.Role role,
            BooleanSupplier currentAndNativeConsented, LongSupplier elapsedRealtimeMs,
            Endpoint endpoint, Control control, Listener listener) {
        this.nativeOwner = Objects.requireNonNull(nativeOwner); this.scope = Objects.requireNonNull(scope);
        this.format = Objects.requireNonNull(format); this.role = Objects.requireNonNull(role);
        this.currentAndNativeConsented = Objects.requireNonNull(currentAndNativeConsented);
        this.endpoint = Objects.requireNonNull(endpoint); this.control = Objects.requireNonNull(control);
        this.listener = Objects.requireNonNull(listener);
        session = new ScreenCastSession(scope, format, role, this::approved, elapsedRealtimeMs,
                new ScreenCastSession.Transport() {
                    public boolean offer(ScreenCastSession.Scope ignored, ScreenCastPacket packet) {
                        return ScreenCastNativeSession.this.endpoint.offer(packet);
                    }
                    public void rendered(ScreenCastSession.Receipt receipt) {
                        ScreenCastNativeSession.this.control.rendered(receipt);
                    }
                    public void requestKeyFrame(ScreenCastSession.Scope ignored) {
                        ScreenCastNativeSession.this.control.requestKeyFrame(scope);
                    }
                }, new ScreenCastSession.Listener() {
                    public void onStreaming(ScreenCastSession.Receipt receipt) {
                        streamingNotification(receipt);
                    }
                    public void onClosed(String code) { finish(code); }
                });
    }

    private boolean approved() {
        if (closed) return false;
        try { return currentAndNativeConsented.getAsBoolean(); }
        catch (RuntimeException error) { return false; }
    }
    private void streamingNotification(ScreenCastSession.Receipt receipt) {
        try { listener.onStreaming(receipt); }
        catch (RuntimeException error) { session.fail("screen_callback_failed"); }
    }
    private boolean owner(Object actualOwner, ScreenCastSession.Role expected) {
        return actualOwner == nativeOwner && role == expected && !closed;
    }
    /** Pass this listener only to the bound request. A listener from a previous owner/stream
     * remains permanently fenced after close; SDK dispatch is never routed to onPrepared. */
    public OplusScreenSourceAdapter.Listener sourceListener(Object actualOwner) {
        if (!owner(actualOwner, ScreenCastSession.Role.SOURCE)) throw new IllegalArgumentException("screen_native_owner_mismatch");
        return new OplusScreenSourceAdapter.Listener() {
            public boolean offer(ScreenCastPacket packet) { return sourceOffer(actualOwner, packet); }
            public void onPrepared(int displayId) { sourcePrepared(actualOwner, displayId); }
            public void onStopped() { nativeStopped(actualOwner); }
            public void onFailure(String ignored) { nativeFailed(actualOwner); }
        };
    }
    public XiaomiScreenSinkAdapter.Listener sinkListener(Object actualOwner) {
        if (!owner(actualOwner, ScreenCastSession.Role.SINK)) throw new IllegalArgumentException("screen_native_owner_mismatch");
        return new XiaomiScreenSinkAdapter.Listener() {
            public void onPrepared() { sinkPrepared(actualOwner); }
            public void onFrameRendered(long pts, long nanoTime) { rendered(actualOwner, pts); }
            public void onKeyFrameNeeded() { keyFrameNeeded(actualOwner); }
            public void onStopped() { nativeStopped(actualOwner); }
            public void onFailure(String ignored) { nativeFailed(actualOwner); }
            public void onEnd() { nativeStopped(actualOwner); }
        };
    }
    private synchronized void sourcePrepared(Object actualOwner, int displayId) {
        if (!owner(actualOwner, ScreenCastSession.Role.SOURCE)) return;
        if (displayId < 0 || nativeDisplayId >= 0 && nativeDisplayId != displayId) {
            session.fail("screen_native_display_changed"); return;
        }
        nativeDisplayId = displayId; prepared();
    }
    private synchronized void sinkPrepared(Object actualOwner) {
        if (!owner(actualOwner, ScreenCastSession.Role.SINK)) return;
        // Each sink preparation owns a new decoder/Surface generation. The old render receipt
        // cannot authorize input on its replacement; renegotiate a fresh stream instead.
        if (preparedSent) { session.fail("screen_native_surface_changed"); return; }
        prepared();
    }
    private void prepared() {
        if (preparedSent || !session.localPrepared()) return;
        preparedSent = true;
        try { control.prepared(scope, format); }
        catch (RuntimeException error) { session.fail("screen_control_failed"); }
    }
    private synchronized boolean sourceOffer(Object actualOwner, ScreenCastPacket packet) {
        if (!owner(actualOwner, ScreenCastSession.Role.SOURCE)) return false;
        try { return session.offer(packet); }
        catch (RuntimeException error) { session.fail("screen_media_failed"); return false; }
    }
    private synchronized void rendered(Object actualOwner, long pts) {
        if (!owner(actualOwner, ScreenCastSession.Role.SINK)) return;
        try { session.frameRendered(pts); }
        catch (RuntimeException error) { session.fail("screen_control_failed"); }
    }
    private synchronized void keyFrameNeeded(Object actualOwner) {
        if (!owner(actualOwner, ScreenCastSession.Role.SINK)) return;
        try { session.keyFrameNeeded(); }
        catch (RuntimeException error) { session.fail("screen_control_failed"); }
    }
    private synchronized void nativeStopped(Object actualOwner) {
        if (actualOwner == nativeOwner && !closed) session.fail("screen_native_stopped");
    }
    private synchronized void nativeFailed(Object actualOwner) {
        if (actualOwner == nativeOwner && !closed) session.fail("screen_native_failed");
    }
    /** These inbound methods are for the authenticated control/media owner only. No remote
     * session, stream, receipt or format is repaired/coerced to match a local request. */
    public synchronized boolean remotePrepared(ScreenCastSession.Scope received, ScreenCastFormat accepted) {
        return session.remotePrepared(received, accepted);
    }
    public synchronized boolean remoteRendered(ScreenCastSession.Receipt receipt) { return session.renderedReceipt(receipt); }
    public synchronized ScreenCastFrameQueue.Offer receive(ScreenCastSession.Scope received, ScreenCastPacket packet) {
        try { return session.receive(received, packet, endpoint::receive); }
        catch (RuntimeException error) { session.fail("screen_media_failed"); return ScreenCastFrameQueue.Offer.CLOSED; }
    }
    public synchronized boolean remoteKeyFrame(ScreenCastSession.Scope received) {
        try { return session.keyFrameRequest(received, endpoint::requestKeyFrame); }
        catch (RuntimeException error) { session.fail("screen_media_failed"); return false; }
    }
    public synchronized boolean remoteStopped(ScreenCastSession.Scope received) {
        if (closed || !scope.equals(received)) return false;
        remoteStop = true; session.fail("screen_remote_stopped"); return true;
    }
    /** Input still requires an independent native focus and input grant. This method supplies
     * only the current owner/stream/actual-render part of that combined gate. */
    public synchronized boolean streaming(Object actualOwner, ScreenCastSession.Scope requested) {
        session.tick();
        return !closed && actualOwner == nativeOwner && scope.equals(requested)
                && session.state() == ScreenCastSession.State.STREAMING;
    }
    public synchronized void tick() { session.tick(); }
    public ScreenCastSession.Scope scope() { return scope; }
    public synchronized ScreenCastSession.State state() { return session.state(); }
    private synchronized void finish(String code) {
        if (closed) return;
        closed = true;
        // Set the fence first: native stop and control callbacks may synchronously call back.
        attempt(endpoint::revokeInput); attempt(endpoint::stopNative); attempt(endpoint::closeMedia);
        if (!remoteStop) attempt(() -> control.stopped(scope, code));
        attempt(() -> listener.onClosed(code));
    }
    private static void attempt(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { } }
    @Override public synchronized void close() { session.close(); }
}
