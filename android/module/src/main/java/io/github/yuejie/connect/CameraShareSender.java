package io.github.yuejie.connect;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Camera source + the shared real AVC encoder; bridge supplies a bounded authenticated camera channel. */
final class CameraShareSender implements AutoCloseable {
    interface Transport {
        // Must return immediately. False means bounded-queue backpressure, not remote failure/success.
        boolean offer(String sessionId, String streamId, ScreenCastPacket packet);
    }
    interface Listener {
        void capturing(CameraShareLease lease);
        void streaming(CameraShareLease lease);
        void closed(String sessionId, String streamId, String reason);
        default void availabilityChanged(String cameraId, boolean available) { }
    }
    private final CameraShareSource source;
    private final CameraShareSource.Permit permit;
    private final Transport transport;
    private final Listener listener;
    private Run active;
    private boolean closed;

    private static final class Run {
        final String session, stream;
        final long mediaId;
        final CameraShareConfig config;
        ScreenCastVideoEncoder encoder;
        CameraShareLease lease;
        boolean stopping, reportedCapture, reportedStreaming;
        long lastRenderedAt = System.nanoTime();
        Run(String session, long mediaId, CameraShareConfig config) {
            new CameraShareLease(session, Long.toString(mediaId));
            if (mediaId <= 0) throw new IllegalArgumentException("invalid_camera_stream");
            this.session = session;
            this.stream = Long.toString(mediaId);
            this.mediaId = mediaId;
            this.config = Objects.requireNonNull(config);
        }
    }

    CameraShareSender(Context context, CameraShareSource.Permit permit, Transport transport, Listener listener) {
        this.permit = Objects.requireNonNull(permit);
        this.transport = Objects.requireNonNull(transport);
        this.listener = Objects.requireNonNull(listener);
        source = new CameraShareSource(context, permit, new CameraShareSource.Listener() {
            @Override public void availabilityChanged(String cameraId, boolean available) {
                listener.availabilityChanged(cameraId, available);
            }
            @Override public void captured(CameraShareLease lease, long timestampUs) {
                boolean report = false;
                synchronized (CameraShareSender.this) {
                    if (active != null && active.lease == lease && !active.stopping) {
                        if (System.nanoTime() - active.lastRenderedAt > 8_000_000_000L) {
                            fail(active, "camera_delivery_timeout");
                        } else if (!active.reportedCapture) {
                            active.reportedCapture = true;
                            report = true;
                        }
                    }
                }
                if (report) listener.capturing(lease);
            }
            @Override public void closed(CameraShareLease lease, String reason) {
                Run run;
                synchronized (CameraShareSender.this) {
                    run = active;
                    if (run == null || run.lease != lease) return;
                    run.stopping = true;
                }
                // Source's closed callback guarantees CameraDevice no longer uses the encoder Surface.
                run.encoder.close();
                synchronized (CameraShareSender.this) { if (active == run) active = null; }
                listener.closed(run.session, run.stream, reason);
            }
        });
    }

    List<CameraShareConfig.Camera> cameras() throws CameraAccessException { return source.cameras(); }
    Boolean available(String cameraId) { return source.available(cameraId); }

    synchronized CameraShareLease start(String sessionId, long streamId, CameraShareConfig config)
            throws IOException, CameraAccessException {
        return startWithStill(sessionId, null, streamId, config, null);
    }

    synchronized CameraShareLease startWithStill(String sessionId, String peerId, long streamId,
            CameraShareConfig config, CameraShareStillSession.Size stillSize) throws IOException, CameraAccessException {
        if (closed) throw new IllegalStateException("camera_sender_closed");
        if (active != null) throw new IllegalStateException("camera_busy");
        Run run = new Run(sessionId, streamId, config);
        config.negotiate(source.cameras());
        if (!permit.valid(run.session, run.stream, config.cameraId)) throw new SecurityException("camera_consent_required");
        active = run;
        try {
            run.encoder = new ScreenCastVideoEncoder(streamId,
                    new ScreenCastFormat(config.width, config.height, config.fps, config.bitrate),
                    new ScreenCastVideoEncoder.Listener() {
                        @Override public boolean offer(ScreenCastPacket packet) { return send(run, packet); }
                        @Override public void onFailure(String code) { fail(run, code); }
                    });
            run.lease = source.startWithStill(run.session, peerId, run.stream, config, run.encoder.inputSurface(), stillSize);
            return run.lease;
        } catch (IOException | RuntimeException error) {
            active = null;
            if (run.encoder != null) run.encoder.close();
            throw error;
        }
    }

    java.util.concurrent.CompletableFuture<CameraShareStillSession.Frame> captureStill(
            CameraShareStillRequest request, CameraShareStillSource.Permit permit) {
        synchronized (this) {
            if (active == null || active.stopping || closed || active.mediaId != request.streamId()
                    || !active.session.equals(request.sessionId()) || !active.config.cameraId.equals(request.cameraId()))
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("camera_still_scope_expired"));
        }
        return source.captureStill(request, permit);
    }

    private synchronized boolean send(Run run, ScreenCastPacket packet) {
        if (active != run || closed || run.stopping || run.lease == null
                || !run.lease.matches(run.session, run.stream)) return false;
        if (!permit.valid(run.session, run.stream, run.config.cameraId)) {
            fail(run, "camera_consent_revoked");
            return false;
        }
        boolean frame = !packet.isConfig() && !packet.isEnd();
        if (frame) run.lease.sent(packet.presentationTimeUs);
        boolean offered = transport.offer(run.session, run.stream, packet);
        if (frame && !offered) run.lease.dropped(packet.presentationTimeUs);
        return offered;
    }

    private synchronized void fail(Run run, String code) {
        if (active != run || run.stopping) return;
        run.stopping = true;
        source.abort(run.session, run.stream, code);
    }

    /** Bridge must authenticate both ids and bind this receipt to the camera stream, never a screen stream. */
    void rendered(String sessionId, long streamId, long presentationTimeUs) {
        CameraShareLease report = null;
        synchronized (this) {
            Run run = active;
            if (run != null && run.mediaId == streamId && !run.stopping && run.lease != null
                    && run.lease.rendered(sessionId, Long.toString(streamId), presentationTimeUs)) {
                run.lastRenderedAt = System.nanoTime();
                if (!run.reportedStreaming) {
                    run.reportedStreaming = true;
                    report = run.lease;
                }
            }
        }
        if (report != null) listener.streaming(report);
    }

    synchronized void requestKeyFrame(String sessionId, long streamId) {
        if (active != null && !active.stopping && active.mediaId == streamId && active.session.equals(sessionId))
            active.encoder.requestKeyFrame();
    }

    synchronized void stop(String sessionId, long streamId) {
        if (active != null && active.mediaId == streamId && active.session.equals(sessionId)) {
            active.stopping = true;
            source.stop(sessionId, active.stream);
        }
    }

    synchronized void sessionInvalidated(String sessionId) {
        if (active != null && active.session.equals(sessionId)) active.stopping = true;
        source.sessionInvalidated(sessionId);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) active.stopping = true;
        source.close();
    }
}
