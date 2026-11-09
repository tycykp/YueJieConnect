package io.github.yuejie.connect;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.Method;

/** Surface adapter for the sampled VirtualCamera.OpenCameraOptions native preview contract. */
final class XiaomiCameraPreview implements AutoCloseable, IBinder.DeathRecipient {
    static final String OPTIONS = "com.xiaomi.dist.camera.kit.VirtualCamera$OpenCameraOptions";
    private final IBinder consumer;
    private CameraShareReceiver receiver;
    private boolean closed;

    XiaomiCameraPreview(ClassLoader nativeLoader, String sessionId, long streamId,
                        CameraShareConfig config, Object nativeOptions, IBinder nativeConsumer,
                        CameraShareReceiver.Permit permit, CameraShareReceiver.Listener listener)
            throws ReflectiveOperationException, IOException, RemoteException {
        Class<?> options = Class.forName(OPTIONS, false, nativeLoader);
        if (!options.isInstance(nativeOptions)) throw new IllegalArgumentException("invalid_native_camera_options");
        // This preview adapter does not claim still capture, YUV ImageReader or HAL injection support.
        if (options.getMethod("getCaptureSurface").invoke(nativeOptions) != null)
            throw new IllegalArgumentException("camera_still_capture_not_implemented");
        Object size = options.getMethod("getPreviewSize").invoke(nativeOptions);
        if (size == null) throw new IllegalArgumentException("native_camera_preview_size_missing");
        Method width = size.getClass().getMethod("getWidth");
        Method height = size.getClass().getMethod("getHeight");
        if ((int) width.invoke(size) != config.width || (int) height.invoke(size) != config.height)
            throw new IllegalArgumentException("native_camera_preview_size_mismatch");
        Surface surface = (Surface) options.getMethod("getPreviewSurface").invoke(nativeOptions);
        if (surface == null || !surface.isValid()) throw new IllegalArgumentException("native_camera_surface_missing");
        if (nativeConsumer == null || !nativeConsumer.isBinderAlive()) throw new RemoteException("native_camera_consumer_dead");
        consumer = nativeConsumer;
        // Caller must already have passed OEM UID/package/RulesConfig checks and per-stream authorization.
        consumer.linkToDeath(this, 0);
        try {
            synchronized (this) {
                if (closed || !consumer.isBinderAlive()) throw new RemoteException("native_camera_consumer_dead");
                receiver = new CameraShareReceiver(sessionId, streamId, config, surface, permit, listener);
            }
        } catch (IOException | RemoteException | RuntimeException error) {
            close();
            throw error;
        }
    }

    synchronized boolean offer(String sessionId, ScreenCastPacket packet) {
        return !closed && receiver != null && receiver.offer(sessionId, packet);
    }

    synchronized void sessionInvalidated(String sessionId) {
        if (receiver != null) receiver.sessionInvalidated(sessionId);
    }

    @Override public void binderDied() { close(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (receiver != null) receiver.close();
        try { consumer.unlinkToDeath(this, 0); } catch (java.util.NoSuchElementException ignored) { }
    }
}
