package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** AVC -> an authorized native camera consumer Surface. Does not own or create a display/window. */
final class CameraShareReceiver implements AutoCloseable {
    interface Permit { boolean valid(String sessionId, long streamId); }
    interface Listener {
        void rendered(String sessionId, long streamId, long timestampUs);
        void keyFrameNeeded(String sessionId, long streamId);
        void closed(String sessionId, long streamId, String reason);
    }
    private final String session;
    private final long stream;
    private final Permit permit;
    private final Listener listener;
    private volatile ScreenCastVideoDecoder decoder;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final HandlerThread watchdog = new HandlerThread("QjCameraReceiver");
    private final Handler worker;
    private volatile long lastFrameNs = System.nanoTime();

    CameraShareReceiver(String sessionId, long streamId, CameraShareConfig config, Surface nativeOutput,
                        Permit permit, Listener listener) throws IOException {
        new CameraShareLease(sessionId, Long.toString(streamId));
        if (streamId <= 0) throw new IllegalArgumentException("invalid_camera_stream");
        session = sessionId;
        stream = streamId;
        this.permit = Objects.requireNonNull(permit);
        this.listener = Objects.requireNonNull(listener);
        if (!permit.valid(session, stream)) throw new SecurityException("camera_consumer_not_authorized");
        watchdog.start();
        worker = new Handler(watchdog.getLooper());
        try { decoder = new ScreenCastVideoDecoder(stream, new ScreenCastFormat(config.width, config.height, config.fps, config.bitrate),
                nativeOutput, new ScreenCastVideoDecoder.Listener() {
                    @Override public void onFrameRendered(long pts, long nanoTime) {
                        if (valid()) {
                            lastFrameNs = System.nanoTime();
                            try { CameraShareReceiver.this.listener.rendered(session, stream, pts); }
                            catch (RuntimeException error) { end("camera_consumer_callback_failed"); }
                        }
                    }
                    @Override public void onKeyFrameNeeded() {
                        if (valid()) {
                            try { CameraShareReceiver.this.listener.keyFrameNeeded(session, stream); }
                            catch (RuntimeException error) { end("camera_consumer_callback_failed"); }
                        }
                    }
                    @Override public void onFailure(String code) { end(code); }
                    @Override public void onEnd() { end("camera_stream_ended"); }
                }); }
        catch (IOException | RuntimeException error) { closed.set(true); watchdog.quitSafely(); throw error; }
        if (closed.get()) decoder.close();
        else worker.postDelayed(this::check, 500);
    }

    private boolean valid() {
        if (closed.get()) return false;
        try {
            if (permit.valid(session, stream)) return true;
        } catch (RuntimeException ignored) { }
        end("camera_consumer_revoked");
        return false;
    }

    boolean offer(String sessionId, ScreenCastPacket packet) {
        if (!session.equals(sessionId) || packet == null || packet.streamId != stream || !valid()) return false;
        ScreenCastFrameQueue.Offer result = decoder.offer(packet);
        return result == ScreenCastFrameQueue.Offer.ACCEPTED;
    }

    private void check() {
        if (!valid()) return;
        if (System.nanoTime() - lastFrameNs > 8_000_000_000L) end("camera_frame_timeout");
        else worker.postDelayed(this::check, 500);
    }

    void sessionInvalidated(String sessionId) { if (session.equals(sessionId)) end("session_invalidated"); }

    private void end(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        // A decoder may fail immediately during construction. Its worker already releases itself.
        if (decoder != null) decoder.close();
        watchdog.quitSafely();
        try { listener.closed(session, stream, reason); } catch (RuntimeException ignored) { }
    }

    @Override public void close() { end("camera_stopped"); }
}
