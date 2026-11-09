package io.github.qiangjian.connect;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consumes a fresh system-approved MediaProjection. It never creates or reuses a consent token. */
public final class ScreenCastProjectionSource implements AutoCloseable {
    private final MediaProjection projection;
    private final HandlerThread callbacks = new HandlerThread("qj-screen-consent");
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScreenCastVideoEncoder encoder;
    private VirtualDisplay display;
    private final MediaProjection.Callback revoked = new MediaProjection.Callback() {
        @Override public void onStop() { close(); }
    };

    /** Caller must have a foreground service of mediaProjection type and normal user consent. */
    public ScreenCastProjectionSource(MediaProjection projection, long streamId, ScreenCastFormat format,
                                      int densityDpi, ScreenCastVideoEncoder.Listener listener) throws IOException {
        if (densityDpi < 72 || densityDpi > 1000) throw new IllegalArgumentException("invalid_density");
        this.projection = Objects.requireNonNull(projection);
        callbacks.start();
        try {
            synchronized (this) {
                encoder = new ScreenCastVideoEncoder(streamId, format, new ScreenCastVideoEncoder.Listener() {
                    public boolean offer(ScreenCastPacket packet) { return !closed.get() && listener.offer(packet); }
                    public void onFailure(String code) {
                        new Handler(callbacks.getLooper()).post(() -> { close(); listener.onFailure(code); });
                    }
                });
                projection.registerCallback(revoked, new Handler(callbacks.getLooper()));
                if (closed.get()) throw new IllegalStateException("capture_revoked");
                display = projection.createVirtualDisplay("QJ authorized screen", format.width(), format.height(), densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoder.inputSurface(), null, null);
                if (display == null) throw new IllegalStateException("capture_display_unavailable");
            }
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }
    public synchronized void requestKeyFrame() { if (!closed.get() && encoder != null) encoder.requestKeyFrame(); }
    public synchronized int displayId(){return display==null?-1:display.getDisplay().getDisplayId();}
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { if (display != null) { display.release(); display = null; } }
        finally {
            try { if (encoder != null) encoder.close(); }
            finally {
                try { projection.unregisterCallback(revoked); projection.stop(); }
                finally { callbacks.quitSafely(); }
            }
        }
    }
}
